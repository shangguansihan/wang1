package com.yuanbao.cdburner.usb

/**
 * SCSI/MMC 命令描述块（CDB）构造器。
 * 字节序严格按 SPC/MMC 规范：大多数 CDB 字段是大端（BE）；CBW/CSW 是小端（在 UsbMassStorage 里处理）。
 *
 * 参考资料：SCSI Primary Commands (SPC-3)、SCSI MMC-3（多媒体命令）。
 */
object ScsiCommands {

    /** TEST UNIT READY：探活，判断光驱是否就绪（盘是否到位）。 */
    fun testUnitReady(): ByteArray = byteArrayOf(0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** INQUIRY：返回 36 字节设备信息（厂商/产品/版本）。 */
    fun inquiry(): ByteArray = byteArrayOf(0x12, 0, 0, 0, 0x24, 0, 0, 0, 0, 0, 0, 0)

    /** READ CAPACITY(10)：返回 8 字节（4 字节最大 LBA BE + 4 字节块大小 BE）。 */
    fun readCapacity(): ByteArray = byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** REQUEST SENSE：命令失败后取详细错误。返回 18 字节。 */
    fun requestSense(): ByteArray = byteArrayOf(0x03, 0, 0, 0, 0x12, 0, 0, 0, 0, 0, 0, 0)

    /** READ(10)：从 lba 读取 blocks 个 2048 字节扇区。 */
    fun read10(lba: Int, blocks: Int): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = 0x28
        cdb[2] = (lba ushr 24).toByte(); cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte(); cdb[5] = (lba and 0xFF).toByte()
        cdb[7] = ((blocks ushr 8) and 0xFF).toByte(); cdb[8] = (blocks and 0xFF).toByte()
        return cdb
    }

    /** WRITE(10)：向 lba 写入 blocks 个 2048 字节扇区。 */
    fun write10(lba: Int, blocks: Int): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = 0x2A
        cdb[2] = (lba ushr 24).toByte(); cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte(); cdb[5] = (lba and 0xFF).toByte()
        cdb[7] = ((blocks ushr 8) and 0xFF).toByte(); cdb[8] = (blocks and 0xFF).toByte()
        return cdb
    }

    /**
     * MODE SELECT(6) 的 CDB。paramListLength 必须等于随后发送的参数字节数。
     * 这里真正要发的数据由 [modeSelectWriteParams] 生成。
     */
    fun modeSelect6(paramListLength: Int): ByteArray {
        val cdb = ByteArray(6)
        cdb[0] = 0x15 // MODE SELECT(6)
        cdb[4] = (paramListLength and 0xFF).toByte()
        return cdb
    }

    /**
     * 写参数模式页（Write Parameters Mode Page，0x05）连同 4 字节模式头。
     * 关键字段：
     *  - Write Type = 0x02 (SAO)：整段会话一次写完，最适合"写满后封盘"的资料盘。
     *  - Track Mode = 0x01 (CD-ROM/数据)。
     *  - Data Block Type = 0x00 => 2048 字节/扇区（数据盘标准）。
     * 若你的刻录机对 SAO 不友好，可把 byte2 改成 0x11（TAO）。
     */
    fun modeSelectWriteParams(): ByteArray {
        val pageLen = 0x32 // 50 字节的写参数页
        val page = ByteArray(2 + pageLen)
        page[0] = 0x05          // 页码
        page[1] = pageLen.toByte()
        page[2] = 0x12          // Write Type=SAO(0x02) | Track Mode=CD-ROM(0x01)
        page[3] = 0x00          // Data Block Type = 0 (2048 字节/扇区)
        // 其余字段（会话格式、交织等）保留为 0，单会话 SAO 用默认值即可。

        val header = byteArrayOf(
            (page.size + 3).toByte(), // 模式数据长度（不含本字节）= 余下 3 字节头 + page
            0,                        // medium type
            0,                        // device specific parameter
            0                         // block descriptor length = 0
        )
        return header + page // 总长 4 + 52 = 56 字节
    }

    /**
     * CLOSE TRACK/SESSION。
     * @param function 0x00 = 封盘（finalize session，使盘在所有光驱可读）；0x01 = 仅封当前轨。
     * @param trackNumber 封轨时填轨号，封盘填 0。
     */
    fun closeTrackSession(function: Int = 0x00, trackNumber: Int = 0): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = 0x5B
        cdb[4] = (trackNumber and 0xFF).toByte()
        cdb[5] = (function and 0xFF).toByte()
        return cdb
    }

    /**
     * BLANK：擦除盘片。
     * @param type 0x00 = 整张擦除（CD-RW 物理清空，较慢，几分钟）。
     *             若只想快速清目录（旧数据仍在），可用 0x06（unclose last session）——但不建议用于重刻。
     * @param immediate true 时命令立即返回，需轮询 TEST UNIT READY 等其完成；false 时阻塞到结束。
     */
    fun blank(type: Int = 0x00, immediate: Boolean = false): ByteArray {
        val cdb = ByteArray(12)
        cdb[0] = 0xA1.toByte()
        if (immediate) cdb[1] = 0x01
        cdb[2] = (type and 0xFF).toByte()
        return cdb
    }

    /** SET CD SPEED（可选）：设置读写速度，避免某些盘飞车失败。numX 为 1X=176.4KB/s 的倍数。 */
    fun setCdSpeed(kbPerSec: Int): ByteArray {
        val cdb = ByteArray(12)
        cdb[0] = 0xBB.toByte()
        cdb[2] = ((kbPerSec ushr 8) and 0xFF).toByte(); cdb[3] = (kbPerSec and 0xFF).toByte()
        return cdb
    }
}
