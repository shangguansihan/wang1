package com.yuanbao.cdburner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuanbao.cdburner.burn.CdBurner
import com.yuanbao.cdburner.databinding.ActivityMainBinding
import com.yuanbao.cdburner.iso.Iso9660Builder
import com.yuanbao.cdburner.usb.UsbMassStorage
import java.io.File

/** 选中文件（已读入内存；大文件夹可能 OOM，见 README 说明）。 */
data class SelectedFile(
    val relPath: String,
    val name: String,
    val size: Long,
    val bytes: ByteArray
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usb: UsbMassStorage
    private val selected = mutableListOf<SelectedFile>()
    private lateinit var adapter: FileAdapter

    private val ACTION_USB_PERMISSION = "com.yuanbao.cdburner.USB_PERMISSION"
    private lateinit var permissionIntent: android.app.PendingIntent

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) loadFolder(uri)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val dev = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && dev != null) openDrive(dev)
            else setStatus("USB 权限被拒绝")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 如果上次启动崩溃过，先把记录弹出来，方便排查"进不去"
        showPreviousCrash()

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            usb = UsbMassStorage(this)
            permissionIntent = android.app.PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE
                else 0
            )

            adapter = FileAdapter(selected)
            binding.rvFiles.adapter = adapter
            binding.rvFiles.layoutManager = LinearLayoutManager(this)

            binding.btnPick.setOnClickListener { folderLauncher.launch(null) }
            binding.btnBurnCdr.setOnClickListener { doBurn(rewritable = false) }
            binding.btnBurnCdrw.setOnClickListener { doBurn(rewritable = true) }

            // Android 13+ 必须显式声明 RECEIVER_NOT_EXPORTED，否则启动即 IllegalArgumentException 闪退
            ContextCompat.registerReceiver(
                this, usbReceiver, IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            refreshDrive()

            // 启动成功，清掉旧的崩溃记录
            File(filesDir, "crash.log").delete()
        } catch (e: Throwable) {
            showFatal(e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbReceiver) }
        usb.close()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshDrive()
    }

    /** 检查是否已插入光驱并已就绪/授权。 */
    private fun refreshDrive() {
        val dev = usb.findDrive()
        if (dev == null) {
            binding.tvDevice.text = getString(R.string.hint_no_device)
            updateButtons()
            return
        }
        if (!usb.hasPermission(dev)) {
            binding.tvDevice.text = "已检测到光驱，请授权 USB 访问"
            usb.requestPermission(dev, permissionIntent)
            return
        }
        if (!usb.isOpen) openDrive(dev)
    }

    private fun openDrive(dev: android.hardware.usb.UsbDevice) {
        if (usb.open(dev)) {
            binding.tvDevice.text = "已连接：${dev.productName ?: dev.deviceName}"
        } else {
            binding.tvDevice.text = "打开光驱失败（可能不是 Mass Storage 类）"
        }
        updateButtons()
    }

    private fun updateButtons() {
        val ok = usb.isOpen && selected.isNotEmpty()
        binding.btnBurnCdr.isEnabled = ok
        binding.btnBurnCdrw.isEnabled = ok
    }

    /** 递归读取用户选择的整个文件夹为 (相对路径, 字节)。 */
    private fun loadFolder(uri: android.net.Uri) {
        Thread {
            val root = DocumentFile.fromTreeUri(this, uri) ?: return@Thread
            val out = mutableListOf<SelectedFile>()
            var total = 0L
            fun walk(df: DocumentFile, prefix: String) {
                for (child in df.listFiles()) {
                    if (child.isDirectory) walk(child, prefix + (child.name ?: "DIR") + "/")
                    else if (child.isFile) {
                        val bytes = contentResolver.openInputStream(child.uri)?.use { it.readBytes() } ?: ByteArray(0)
                        val name = child.name ?: "FILE"
                        out.add(SelectedFile(prefix + name, name, bytes.size.toLong(), bytes))
                        total += bytes.size
                    }
                }
            }
            walk(root, "")
            runOnUiThread {
                selected.clear(); selected.addAll(out); adapter.notifyDataSetChanged()
                val mb = total / (1024.0 * 1024.0)
                binding.tvSummary.text = "共 ${out.size} 个文件，${"%.1f".format(mb)} MB"
                if (total > 700L * 1024 * 1024) setStatus("警告：超过 700MB（CD 容量上限），请删减")
                updateButtons()
            }
        }.start()
    }

    private fun doBurn(rewritable: Boolean) {
        if (selected.isEmpty()) { setStatus("请先选择文件"); return }
        val dev = usb.findDrive()
        if (dev == null) { setStatus("未检测到光驱"); return }
        if (!usb.hasPermission(dev)) { usb.requestPermission(dev, permissionIntent); setStatus("请先授权 USB"); return }
        if (!usb.isOpen && !usb.open(dev)) { setStatus("打开光驱失败"); return }

        val items = selected.map { it.relPath to it.bytes }
        val progress: (Int, String) -> Unit = { pct, msg ->
            runOnUiThread { binding.progress.progress = pct; setStatus(msg) }
        }
        Thread {
            val burner = CdBurner(usb)
            if (rewritable) {
                val okBlank = burner.blank(immediate = false, progress)
                if (!okBlank) { runOnUiThread { updateButtons() }; return@Thread }
            }
            val plan = Iso9660Builder.plan(items)
            val ok = burner.burn(plan, progress)
            runOnUiThread {
                setStatus(if (ok) "刻录完成 ✓（封盘后放入任意光驱可读）" else "刻录失败，详见上方状态")
                updateButtons()
            }
        }.start()
    }

    private fun setStatus(s: String) { binding.tvStatus.text = s }

    /** 若上次启动崩溃过，把 crash.log 弹出来。 */
    private fun showPreviousCrash() {
        val file = File(filesDir, "crash.log")
        if (!file.exists()) return
        val text = file.readText()
        AlertDialog.Builder(this)
            .setTitle("上次启动崩溃记录")
            .setMessage(text)
            .setPositiveButton("知道了") { _, _ -> }
            .show()
    }

    /** 初始化过程中若抛出异常，拦截下来显示到屏幕，而不是白屏闪退。 */
    private fun showFatal(e: Throwable) {
        val tv = TextView(this)
        tv.text = "启动出错（已拦截，未闪退）：\n\n" + android.util.Log.getStackTraceString(e)
        tv.setTextIsSelectable(true)
        tv.setPadding(32, 32, 32, 32)
        tv.textSize = 11f
        setContentView(tv)
    }
}

/** 简单的文件列表适配器。 */
class FileAdapter(private val items: List<SelectedFile>) : RecyclerView.Adapter<FileViewHolder>() {
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FileViewHolder {
        val tv = TextView(parent.context)
        tv.setPadding(8, 10, 8, 10)
        tv.textSize = 14f
        return FileViewHolder(tv)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: FileViewHolder, pos: Int) {
        val f = items[pos]
        val kb = f.size / 1024
        h.tv.text = if (kb > 1024) "${f.name}  (%.1f MB)".format(kb / 1024.0)
        else "${f.name}  (${kb} KB)"
    }
}

class FileViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv)
