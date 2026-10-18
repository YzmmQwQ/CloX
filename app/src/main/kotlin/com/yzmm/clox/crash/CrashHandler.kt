package com.yzmm.clox.crash

import android.app.Application
import android.content.Intent
import android.os.Build
import com.yzmm.clox.BuildConfig
import com.yzmm.clox.util.safeStartActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler : Thread.UncaughtExceptionHandler {

    private lateinit var app: Application
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun install(app: Application) {
        this.app = app
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val report = buildReport(thread, throwable)
            val file = File(app.cacheDir, "crash_report.txt")
            file.writeText(report)

            val intent = Intent(app, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashActivity.EXTRA_CRASH_FILE, file.absolutePath)
            }
            app.safeStartActivity(intent, showError = false)
        } catch (_: Exception) { }

        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("CloX Crash Report")
            appendLine("=================")
            appendLine("Time:    $time")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread:  ${thread.name}")
            appendLine()
            appendLine("Stack trace:")
            appendLine(throwable.stackTraceToString())
        }
    }
}
