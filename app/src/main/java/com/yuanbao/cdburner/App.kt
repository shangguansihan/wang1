package com.yuanbao.cdburner

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把未捕获异常的堆栈写入私有目录 crash.log，
 * 下次启动时 MainActivity 会把它显示出来，避免"闪退却无从排查"。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = File(filesDir, "crash.log")
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                val sb = StringBuilder()
                sb.append("=== 崩溃时间 ===\n").append(ts).append("\n\n")
                sb.append("=== 设备 ===\n")
                sb.append("Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                sb.append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n")
                sb.append("=== 异常堆栈 ===\n")
                sb.append(Log.getStackTraceString(throwable))
                file.writeText(sb.toString())
            } catch (_: Throwable) {
                // 写日志失败也不影响系统默认崩溃处理
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
