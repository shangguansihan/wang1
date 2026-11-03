package com.yuanbao.cdburner.burn

import com.yuanbao.cdburner.iso.Iso9660Builder
import com.yuanbao.cdburner.iso.Iso9660Builder.IsoPlan
import com.yuanbao.cdburner.usb.ScsiCommands
import com.yuanbao.cdburner.usb.UsbMassStorage
import kotlin.math.min

/**
 * 高层刻录/擦除流程编排。
 *
 * 刻录（CD-R 一次性 / CD-RW 重刻通用）：
 *   设置写参数(SAO/数据/2048) -> 按 LBA 顺序 WRITE(10) 整张 ISO -> CLOSE TRACK/SESSION 封盘。
 * 擦除（CD-RW）：BLANK 整张。
 */
class CdBurner(private val drive: UsbMassStorage) {

    private val BUF_BLOCKS = 16 // 每次 WRITE 最多 16 扇区（32KB），减少命令往返

    /**
     * 轮询 TEST UNIT READY，直到光驱就绪（盘到位）。timeoutMs 内未就绪返回 false。
     */
    fun waitUntilReady(timeoutMs: Long = 20000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val r = drive.execScsi(ScsiCommands.testUnitReady())
            if (r.ok) return true
            try { Thread.sleep(500) } catch (_: InterruptedException) { }
        }
        return false
    }

    /** 设置写参数（SAO + 数据 + 2048 字节/扇区）。 */
    fun setWriteParams(): Boolean {
        val params = ScsiCommands.modeSelectWriteParams()
        val r = drive.execScsi(ScsiCommands.modeSelect6(params.size), outData = params)
        return r.ok
    }

    /** 把最近一次命令的 SENSE 错误组织成便于阅读的中文串。 */
    private fun senseText(): String {
        val s = drive.requestSense()
        return if (s.valid) {
            "［错误详情］${s.description}（键=${s.senseKey} ASC=${hex(s.asc)} ASCQ=${hex(s.ascq)}）"
        } else {
            "［错误详情］${s.description}"
        }
    }

    private fun hex(v: Int): String = "0x%02X".format(v)

    /**
     * 擦除整张 CD-RW。immediate=false 时阻塞到完成（可能几分钟）。
     * @return 成功与否
     */
    fun blank(immediate: Boolean = false, progress: (Int, String) -> Unit): Boolean {
        progress(0, "正在等待光驱就绪…")
        if (!waitUntilReady(15000)) {
            progress(0, "光驱未就绪，无法擦除（请确认光盘已放入刻录机）")
            return false
        }
        progress(0, "正在擦除光盘（可能需要几分钟，请勿拔线）…")
        drive.timeoutMs = 10 * 60 * 1000 // 擦除可能很久
        val r = drive.execScsi(ScsiCommands.blank(0x00, immediate))
        drive.timeoutMs = 60_000
        if (!r.ok) {
            progress(0, "擦除失败：${senseText()}")
            return false
        }
        waitUntilReady(10 * 60 * 1000)
        progress(100, "擦除完成 ✓")
        return true
    }

    /**
     * 把整个 ISO 写入光盘并封盘。
     * @param plan 由 [Iso9660Builder.plan] 生成
     * @param progress 进度回调 (百分比, 状态文本)
     */
    fun burn(plan: IsoPlan, progress: (Int, String) -> Unit): Boolean {
        progress(0, "正在等待光驱就绪…")
        if (!waitUntilReady(15000)) {
            progress(0, "光驱未就绪，无法刻录（请确认光盘已放入且已就绪）")
            return false
        }

        if (!setWriteParams()) {
            progress(0, "设置写参数失败：${senseText()}")
            return false
        }

        progress(0, "正在写入光盘…")
        val empty = ByteArray(Iso9660Builder.BLOCK)
        val total = plan.totalBlocks
        var lba = 0
        while (lba < total) {
            val n = min(BUF_BLOCKS, total - lba)
            val data = ByteArray(n * Iso9660Builder.BLOCK)
            for (i in 0 until n) {
                getBlock(lba + i, plan, empty).copyInto(data, i * Iso9660Builder.BLOCK)
            }
            val r = drive.execScsi(ScsiCommands.write10(lba, n), outData = data)
            if (!r.ok) {
                progress(lba * 100 / total, "写入失败 @LBA$lba：${senseText()}")
                return false
            }
            lba += n
            progress(lba * 100 / total, "写入中 ${lba * 100 / total}%")
        }

        progress(99, "正在封盘…")
        drive.timeoutMs = 5 * 60 * 1000
        val c = drive.execScsi(ScsiCommands.closeTrackSession(0x00))
        drive.timeoutMs = 60_000
        if (!c.ok) {
            progress(99, "封盘失败：${senseText()}")
            return false
        }
        progress(100, "刻录完成 ✓（封盘后可在任意光驱 / 电脑读取）")
        return true
    }

    /** 取某 LBA 对应的 2048 字节块：优先 metadata，其次文件数据，否则空块。 */
    private fun getBlock(lba: Int, plan: IsoPlan, empty: ByteArray): ByteArray {
        val meta = plan.metadata[lba]
        if (meta != null) return meta
        val ext = plan.blockToFile[lba]
        if (ext != null) {
            val off = (lba - ext.lba) * Iso9660Builder.BLOCK
            val len = min(Iso9660Builder.BLOCK, ext.length - off)
            val buf = ByteArray(Iso9660Builder.BLOCK)
            ext.data.copyInto(buf, 0, off, off + len)
            return buf
        }
        return empty
    }
}
