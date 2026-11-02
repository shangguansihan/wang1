package com.yuanbao.cdburner.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.app.PendingIntent

/**
 * USB Host 通信层：在用户态认领光驱的 USB 接口，通过 Bulk-Only Transport
 * （CBW -> 数据 -> CSW）把 SCSI/MMC 命令透传给刻录机。
 *
 * 注意：安卓没有光驱内核驱动，所以不走系统 mount，而是我们自己发裸命令。
 */
class UsbMassStorage(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var outEp: UsbEndpoint? = null
    private var inEp: UsbEndpoint? = null
    private var tagCounter = 1

    /** 单条命令的超时（毫秒）。写盘/擦除会临时调大。 */
    var timeoutMs = 60_000

    fun getManager(): UsbManager = usbManager

    /** 在已连接设备里找第一个 USB Mass Storage（光驱） */
    fun findDrive(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            if (isMassStorage(device)) return device
        }
        return null
    }

    private fun isMassStorage(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        }
        return false
    }

    fun hasPermission(device: UsbDevice) = usbManager.hasPermission(device)
    fun requestPermission(device: UsbDevice, pi: PendingIntent) = usbManager.requestPermission(device, pi)

    /** 打开并认领光驱接口，找到 bulk 端点 */
    fun open(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
            val conn = usbManager.openDevice(device) ?: return false
            if (!conn.claimInterface(intf, true)) { conn.close(); return false }
            var o: UsbEndpoint? = null
            var `in`: UsbEndpoint? = null
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (ep.direction) {
                    UsbConstants.USB_DIR_OUT -> o = ep
                    UsbConstants.USB_DIR_IN -> `in` = ep
                }
            }
            if (o == null || `in` == null) { conn.releaseInterface(intf); conn.close(); return false }
            connection = conn
            claimedInterface = intf
            outEp = o
            inEp = `in`
            return true
        }
        return false
    }

    val isOpen: Boolean get() = connection != null

    fun close() {
        connection?.let { conn ->
            claimedInterface?.let { conn.releaseInterface(it) }
            conn.close()
        }
        connection = null; claimedInterface = null; outEp = null; inEp = null
    }

    data class ScsiResult(
        val status: Int,   // CSW bCSWStatus: 0=成功, 1=命令失败, 2=相位错误
        val residue: Int,   // 未传输字节数
        val data: ByteArray?,
        val ok: Boolean
    )

    /**
     * 执行一条 SCSI/MMC 命令（Bulk-Only Transport）。
     * @param cdb    命令描述块
     * @param inData 读命令时提供接收缓冲区（设备 -> 主机）
     * @param outData 写命令时提供要发送的数据（主机 -> 设备）
     */
    fun execScsi(cdb: ByteArray, inData: ByteArray? = null, outData: ByteArray? = null): ScsiResult {
        val conn = connection ?: return ScsiResult(-1, 0, null, false)
        val epOut = outEp ?: return ScsiResult(-1, 0, null, false)
        val epIn = inEp ?: return ScsiResult(-1, 0, null, false)

        val isIn = inData != null
        val dataLen = (inData ?: outData)?.size ?: 0
        val tag = tagCounter++

        // 1) CBW（Command Block Wrapper，31 字节）
        val cbw = buildCbw(tag, cdb, dataLen, isIn)
        if (conn.bulkTransfer(epOut, cbw, cbw.size, timeoutMs) != cbw.size) {
            return ScsiResult(-1, dataLen, null, false)
        }

        // 2) 数据阶段
        if (isIn && inData != null) {
            var off = 0
            while (off < inData.size) {
                val r = conn.bulkTransfer(epIn, inData, off, inData.size - off, timeoutMs)
                if (r <= 0) break
                off += r
            }
        } else if (outData != null) {
            var off = 0
            while (off < outData.size) {
                val w = conn.bulkTransfer(epOut, outData, off, outData.size - off, timeoutMs)
                if (w <= 0) break
                off += w
            }
        }

        // 3) CSW（Command Status Wrapper，13 字节）
        val csw = ByteArray(13)
        val rcv = conn.bulkTransfer(epIn, csw, csw.size, timeoutMs)
        if (rcv != 13) return ScsiResult(-2, dataLen, inData, false)
        val cswTag = readLe32(csw, 4)
        val residue = readLe32(csw, 8)
        val status = csw[12].toInt() and 0xFF
        val ok = (cswTag == tag) && (status == 0)
        return ScsiResult(status, residue, if (isIn) inData else null, ok)
    }

    /** 读现成盘用：直接读写扇区（底层 execScsi 的便捷封装） */
    fun readSectors(lba: Int, blocks: Int): ScsiResult {
        val buf = ByteArray(blocks * 2048)
        return execScsi(ScsiCommands.read10(lba, blocks), inData = buf)
    }

    private fun buildCbw(tag: Int, cdb: ByteArray, dataLen: Int, isIn: Boolean): ByteArray {
        val cbw = ByteArray(31)
        // dCBWSignature = 'USBC' 小端 => 55 53 42 43
        cbw[0] = 0x55; cbw[1] = 0x53; cbw[2] = 0x42; cbw[3] = 0x43
        writeLe32(cbw, 4, tag)
        writeLe32(cbw, 8, dataLen)
        cbw[12] = if (isIn) 0x80.toByte() else 0x00
        cbw[13] = 0 // LUN
        cbw[14] = cdb.size.toByte()
        cdb.copyInto(cbw, 15)
        return cbw
    }

    private fun readLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
