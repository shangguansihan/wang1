package com.yuanbao.cdburner.iso

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Calendar
import java.util.LinkedHashMap
import kotlin.math.max

/**
 * ISO 9660 (Level 2) 镜像布局生成器。
 *
 * 算法已用 Python 参考实现（tools/iso9660_ref.py）自检通过：生成的镜像能被解析回
 * 正确的目录树、LBA 分配与文件数据。本 Kotlin 版与之逐字节对应。
 *
 * 限制：文件名仅支持 ASCII（非 ASCII 字符会被清理成 '_'）。完整中文文件名需要
 * Joliet 补充卷描述符扩展（已在 README 中列为后续增强）。
 *
 * 用法：plan(items) 得到 [IsoPlan]，再由刻录流程按 LBA 顺序把每块写进光盘。
 */
object Iso9660Builder {

    const val BLOCK = 2048

    /** 一个文件在镜像中的区段（连续 LBA）。 */
    data class FileExtent(val lba: Int, val length: Int, val data: ByteArray) {
        val blocks: Int get() = max(1, (length + BLOCK - 1) / BLOCK)
    }

    /** 镜像布局计划：写盘时按 lba 顺序取块即可。 */
    data class IsoPlan(
        val totalBlocks: Int,
        val metadata: Map<Int, ByteArray>,   // lba -> 数据块（系统区/PVD/路径表/目录）
        val fileExtents: List<FileExtent>,    // 按 lba 升序
        val blockToFile: Map<Int, FileExtent> // 每个文件数据块 lba -> 对应区段
    )

    // ---------- 文件名清理 ----------
    fun sanitizeName(name: String): String {
        val up = name.uppercase()
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_."
        val sb = StringBuilder()
        var dot = 0
        for (ch in up) {
            when {
                ch == '.' -> { if (dot < 1) { sb.append('.'); dot++ } }
                ch in allowed -> sb.append(ch)
                else -> sb.append('_')
            }
        }
        var s = sb.toString().trimEnd('.')
        if (s.length > 30) s = s.substring(0, 30).trimEnd('.')
        return s + ";1"
    }

    fun dirIdentifier(name: String): ByteArray {
        if (name.isEmpty()) return byteArrayOf(0)
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_."
        val sb = StringBuilder()
        for (ch in name.uppercase()) sb.append(if (ch in allowed) ch else '_')
        var s = sb.toString()
        if (s.length > 31) s = s.substring(0, 31)
        return s.toByteArray(Charsets.US_ASCII)
    }

    // ---------- 目录树 ----------
    class Node(val name: String, val isDir: Boolean) {
        val children = LinkedHashMap<String, Node>()
        var data: ByteArray = ByteArray(0)
        var lba = 0
        var sizeBlocks = 0
        var dirId = 0
        var parentId = 0
    }

    private fun buildTree(items: List<Pair<String, ByteArray>>): Node {
        val root = Node("", true)
        for ((relPath, data) in items) {
            val parts = relPath.replace('\\', '/').trim('/').split('/')
            var cur = root
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty()) continue
                val last = i == parts.size - 1
                if (last) {
                    val f = cur.children.getOrPut(p) { Node(p, false) }
                    f.data = data
                } else {
                    cur = cur.children.getOrPut(p) { Node(p, true) }
                }
            }
        }
        return root
    }

    // ---------- 目录记录 ----------
    private fun entryLen(nameBytes: ByteArray): Int {
        val n = nameBytes.size
        val pad = if ((33 + n) % 2 != 0) 1 else 0
        return 33 + n + pad
    }

    private fun dirRecord(nameBytes: ByteArray, lba: Int, size: Int, isDir: Boolean, date: ByteArray): ByteArray {
        val n = nameBytes.size
        val pad = if ((33 + n) % 2 != 0) 1 else 0
        val recLen = 33 + n + pad
        val flags = if (isDir) 0x02 else 0x00
        val buf = ByteArray(recLen)
        buf[0] = recLen.toByte()
        writeIntLE(buf, 2, lba)   // LBA 小端（偏移 2-5）
        writeIntBE(buf, 6, lba)   // LBA 大端（偏移 6-9）
        writeIntLE(buf, 10, size) // size 小端（偏移 10-13）
        writeIntBE(buf, 14, size) // size 大端（偏移 14-17）
        date.copyInto(buf, 18)    // 7 字节日期
        buf[25] = flags.toByte()
        writeShortLE(buf, 28, 1)  // 卷序列号（小端）
        writeShortBE(buf, 30, 1)  // 卷序列号（大端）
        buf[32] = n.toByte()      // 文件名长度
        nameBytes.copyInto(buf, 33)
        return buf
    }

    private fun buildPathTable(dirs: List<Node>, littleEndian: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        for (d in dirs) {
            val name = dirIdentifier(d.name)
            val parent = if (d.name.isEmpty()) d.dirId else d.parentId
            val rec = ByteArrayOutputStream()
            rec.write(name.size)
            rec.write(0) // 扩展属性长度
            if (littleEndian) writeIntLE(rec, d.lba) else writeIntBE(rec, d.lba)
            if (littleEndian) writeShortLE(rec, parent) else writeShortBE(rec, parent)
            rec.write(name)
            if (name.size % 2 != 0) rec.write(0)
            out.write(rec.toByteArray())
        }
        return out.toByteArray()
    }

    // ---------- 日期 ----------
    private fun makeDirDate(): ByteArray {
        val c = Calendar.getInstance()
        return byteArrayOf(
            (c.get(Calendar.YEAR) - 1900).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte(),
            0
        )
    }

    private fun makeVolDate(): ByteArray {
        val c = Calendar.getInstance()
        val s = String.format(
            "%04d%02d%02d%02d%02d%02d00",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
        ).toByteArray(Charsets.US_ASCII)
        val b = ByteArray(17)
        s.copyInto(b, 0)
        b[16] = 0
        return b
    }

    // ---------- 主入口 ----------
    fun plan(items: List<Pair<String, ByteArray>>): IsoPlan {
        val root = buildTree(items)
        val dirDate = makeDirDate()
        val volDate = makeVolDate()

        // 分配 dirId（BFS 顺序，root=1）
        val order = mutableListOf<Node>()
        val queue = ArrayDeque<Node>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            order.add(d)
            for (c in d.children.values.filter { it.isDir }.sortedBy { it.name }) queue.add(c)
        }
        order.forEachIndexed { i, d -> d.dirId = i + 1 }

        // 设置 parentId
        fun setParent(node: Node, pid: Int) {
            for (c in node.children.values) {
                if (c.isDir) { c.parentId = node.dirId; setParent(c, c.dirId) }
            }
        }
        root.parentId = 1
        setParent(root, 1)

        // 计算每个目录占用的块数（仅依赖文件名长度，与 LBA 无关）
        fun computeDirSize(d: Node) {
            var total = entryLen(byteArrayOf(0)) // '.'
            total += entryLen(byteArrayOf(1))    // '..'
            for (c in d.children.values.sortedBy { it.name }) {
                total += entryLen(sanitizeName(c.name).toByteArray(Charsets.US_ASCII))
            }
            d.sizeBlocks = max(1, (total + BLOCK - 1) / BLOCK)
        }
        order.forEach { computeDirSize(it) }

        // 先算路径表大小（用临时 lba，路径表内容不含自身位置，只含 dir lba）
        order.forEach { it.lba = 100000 }
        val ptL = buildPathTable(order, true)
        val ptM = buildPathTable(order, false)
        val ptBlocks = max(1, (ptL.size + BLOCK - 1) / BLOCK)

        // 真实分配：系统区 0..15，PVD=16，终止=17，路径表 18..，目录与文件随后
        var cur = 18 + 2 * ptBlocks
        for (d in order) { d.lba = cur; cur += d.sizeBlocks }

        // 收集文件并分配 LBA
        val files = mutableListOf<Node>()
        fun collect(n: Node) {
            for (c in n.children.values) {
                if (c.isDir) collect(c) else files.add(c)
            }
        }
        collect(root)
        for (f in files) {
            f.lba = cur
            f.sizeBlocks = max(1, (f.data.size + BLOCK - 1) / BLOCK)
            cur += f.sizeBlocks
        }
        val totalBlocks = cur

        // 生成每个目录的数据块
        fun parentLbaOf(d: Node): Int {
            fun find(node: Node, target: Node): Int? {
                for (c in node.children.values) {
                    if (c === target) return node.lba
                    if (c.isDir) find(c, target)?.let { return it }
                }
                return null
            }
            return find(root, d) ?: root.lba
        }
        val dirData = mutableMapOf<Int, ByteArray>()
        for (d in order) {
            val total = ByteArrayOutputStream()
            total.write(dirRecord(byteArrayOf(0), d.lba, d.sizeBlocks * BLOCK, true, dirDate))            // '.'
            total.write(dirRecord(byteArrayOf(1), if (d === root) root.lba else parentLbaOf(d), d.sizeBlocks * BLOCK, true, dirDate)) // '..'
            for (c in d.children.values.sortedBy { it.name }) {
                val nb = sanitizeName(c.name).toByteArray(Charsets.US_ASCII)
                total.write(dirRecord(nb, c.lba, if (c.isDir) c.sizeBlocks * BLOCK else c.data.size, c.isDir, dirDate))
            }
            val tgt = d.sizeBlocks * BLOCK
            val arr = total.toByteArray()
            val padded = ByteArray(tgt)
            arr.copyInto(padded, 0)
            dirData[d.lba] = padded
        }

        // PVD
        val pvd = ByteArray(BLOCK)
        pvd[0] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(pvd, 1)
        pvd[6] = 1
        "PHONECDBURNER".padEnd(32).toByteArray(Charsets.US_ASCII).copyInto(pvd, 8)
        "DATA_DISC".padEnd(32).toByteArray(Charsets.US_ASCII).copyInto(pvd, 40)
        writeIntLE(pvd, 80, totalBlocks)
        writeIntBE(pvd, 84, totalBlocks)
        writeShortLE(pvd, 120, 1); writeShortBE(pvd, 124, 1)
        writeShortLE(pvd, 128, 1); writeShortBE(pvd, 132, 1)
        writeShortLE(pvd, 136, BLOCK); writeShortBE(pvd, 140, BLOCK)
        writeIntLE(pvd, 144, ptL.size); writeIntBE(pvd, 148, ptL.size)
        writeIntLE(pvd, 152, 18)             // L-path-table LBA
        writeIntBE(pvd, 160, 18 + ptBlocks)   // M-path-table LBA
        val rr = dirRecord(byteArrayOf(0), root.lba, root.sizeBlocks * BLOCK, true, dirDate)
        rr.copyInto(pvd, 168)
        volDate.copyInto(pvd, 813)
        pvd[841] = 1
        "PHONECDBURNER V0.1".padEnd(37).toByteArray(Charsets.US_ASCII).copyInto(pvd, 843)

        // 终止描述符
        val term = ByteArray(BLOCK)
        term[0] = 255.toByte()
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(term, 1)
        term[6] = 1

        // 组装 metadata
        val metadata = mutableMapOf<Int, ByteArray>()
        metadata[16] = pvd
        metadata[17] = term
        val ptLPadded = ByteArray(ptBlocks * BLOCK); ptL.copyInto(ptLPadded)
        val ptMPadded = ByteArray(ptBlocks * BLOCK); ptM.copyInto(ptMPadded)
        for (i in 0 until ptBlocks) metadata[18 + i] = ptLPadded.copyOfRange(i * BLOCK, (i + 1) * BLOCK)
        for (i in 0 until ptBlocks) metadata[18 + ptBlocks + i] = ptMPadded.copyOfRange(i * BLOCK, (i + 1) * BLOCK)
        for ((lba, data) in dirData) metadata[lba] = data

        val extents = files.sortedBy { it.lba }.map { FileExtent(it.lba, it.data.size, it.data) }
        val blockToFile = mutableMapOf<Int, FileExtent>()
        for (e in extents) for (b in e.lba until e.lba + e.blocks) blockToFile[b] = e

        return IsoPlan(totalBlocks, metadata, extents, blockToFile)
    }

    /** 调试/测试用：直接生成完整 ISO 字节（内存版，仅适合小数据）。 */
    fun buildAll(items: List<Pair<String, ByteArray>>): ByteArray {
        val plan = plan(items)
        val out = ByteArray(plan.totalBlocks * BLOCK)
        for ((lba, data) in plan.metadata) data.copyInto(out, lba * BLOCK)
        for (e in plan.fileExtents) e.data.copyInto(out, e.lba * BLOCK)
        return out
    }

    // ---------- 字节工具 ----------
    private fun writeIntLE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
    private fun writeIntBE(buf: ByteArray, off: Int, v: Int) {
        buf[off + 3] = (v and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off] = ((v ushr 24) and 0xFF).toByte()
    }
    private fun writeShortLE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }
    private fun writeShortBE(buf: ByteArray, off: Int, v: Int) {
        buf[off + 1] = (v and 0xFF).toByte()
        buf[off] = ((v ushr 8) and 0xFF).toByte()
    }
    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }
    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }
    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }
    private fun writeShortBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }
}
