package com.yzmm.clox.crash

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.yzmm.clox.BuildConfig
import com.yzmm.clox.util.safeStartActivity
import java.io.File

/**
 * Native (C++/signal) counterpart of [CrashHandler]. The signal handler in
 * native_crash.cpp writes a report file and calls [onNativeCrash], which pops
 * the same [CrashActivity] used for Java crashes — it runs in the separate
 * `:crash` process, so the dialog survives the crashing process dying.
 *
 * Two delivery paths, because a corrupted process may not manage the JNI
 * call-back:
 *  1. live: signal handler → [onNativeCrash] → CrashActivity, right away
 *  2. fallback: the report file is found by [install] on the NEXT launch and
 *     shown then (renamed first so it can never re-show twice)
 *
 * Install AFTER libclox is loaded (CocosManager does this) — the JNI entry
 * point lives inside it.
 */
object NativeCrashHandler {

    private const val TAG = "NativeCrashHandler"
    private const val REPORT_FILE = "native_crash_report.txt"

    private lateinit var app: Application
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        this.app = app

        val report = File(app.cacheDir, REPORT_FILE)
        // Fallback path: a report the dying process couldn't surface live.
        // Rename before showing so a failed CrashActivity read can't loop it.
        if (report.exists()) {
            val last = File(app.cacheDir, "native_crash_last.txt")
            if (report.renameTo(last)) showReporter(last.absolutePath)
        }

        try {
            nativeInstall(report.absolutePath, buildHeader())
            installed = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libclox not loaded yet — native crash handler NOT installed", e)
        }
    }

    /** Called from the signal handler (native_crash.cpp) on the crashing thread. */
    @JvmStatic
    fun onNativeCrash(path: String) {
        try {
            showReporter(path)
        } catch (_: Throwable) {
            // the process is dying; the on-disk report covers us (see install)
        }
    }

    private fun showReporter(path: String) {
        val intent = Intent(app, CrashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(CrashActivity.EXTRA_CRASH_FILE, path)
        }
        app.safeStartActivity(intent, showError = false)
    }

    // Device/version lines the native handler can't know; prepended verbatim
    // to the report at crash time.
    private fun buildHeader(): String = buildString {
        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("ABI:     ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
    }

    private external fun nativeInstall(reportPath: String, header: String)
}
