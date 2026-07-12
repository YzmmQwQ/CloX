package com.ailife.clox.update

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.ailife.clox.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/**
 * CloX-facing wrapper around [OTAManager3]. A listener is always set, so the
 * manager never shows its built-in dialogs or toasts — the only dialog CloX
 * shows is the update prompt (plus the download progress dialog it leads to).
 *
 * Check status (checking / up to date / errors) is reported through [onStatus]
 * for the host screen to render inline; with no [onStatus] set (the silent
 * startup check) nothing is shown unless an update is found.
 *
 * Use an Activity context: the dialogs are attached to it.
 */
class UpdateController(private val context: Context) {

    companion object {
        private const val TAG = "CloX/OTA"
        // OTAManager3's own prefs file/key — referenced here once for migrating
        // the old OtaManager's "auto update" toggle to the new manager.
        private const val OTA3_PREFS = "OTAManager3Prefs"
        private const val OLD_PREFS = "OtaManagerPrefs"
        private const val KEY_ENABLED = "update_check_enabled"
    }

    enum class CheckState { CHECKING, RESULT }

    /** Inline status for the host UI. CHECKING = show a spinner; RESULT = final text. */
    var onStatus: ((state: CheckState, text: String) -> Unit)? = null

    private var progressDialog: AlertDialog? = null
    private var progressBar: LinearProgressIndicator? = null
    private var progressText: TextView? = null

    private val ota = OTAManager3(context)
        .setProvider(OTAManager3.Provider.GITLAB)
        .setRepo("ailife8881", "clox")
        .setWifiOnly(false)
        .setDownloadMode(OTAManager3.DownloadMode.DOWNLOAD_MODE_DIRECT)
        .setDownloadFileName("clox-update.apk")
        .setProviderAuthoritySuffix(".watchface.files")

    init {
        migrateOldPrefs()
        ota.setListener(object : OTAManager3.SimpleCallback() {

            override fun onCheckStarted() {
                status(CheckState.CHECKING, context.getString(R.string.toast_ota_checking))
            }

            override fun onUpdateAvailable(info: OTAManager3.UpdateInfo) {
                status(CheckState.RESULT, context.getString(R.string.dialog_ota_update_title, info.latestVersion))
                showUpdateDialog(info)
            }

            override fun onUpToDate(info: OTAManager3.UpdateInfo) {
                status(CheckState.RESULT, context.getString(R.string.toast_ota_up_to_date, info.currentVersion))
            }

            override fun onError(error: OTAManager3.OTAError, message: String?, exception: Exception?) {
                Log.e(TAG, "Update check error ($error): $message")
                status(CheckState.RESULT, context.getString(R.string.toast_ota_check_failed, message))
            }

            override fun onDownloadStarted(mode: OTAManager3.DownloadMode) {
                if (mode == OTAManager3.DownloadMode.DOWNLOAD_MODE_DIRECT) showProgressDialog()
            }

            override fun onDownloadProgress(percent: Int, bytesDownloaded: Long, totalBytes: Long) {
                updateProgress(percent, bytesDownloaded, totalBytes)
            }

            override fun onDownloadComplete(apkFile: File) {
                dismissProgress()
            }

            override fun onDownloadFailed(message: String?, exception: Exception?) {
                Log.e(TAG, "Download failed: $message")
                dismissProgress()
                status(CheckState.RESULT, context.getString(R.string.toast_ota_check_failed, message))
            }
        })
    }

    /**
     * @param userInitiated true for the About-screen "Check for updates" row:
     *        forces the check (any network, ignores the disabled flag and any
     *        skipped version). Outcomes are reported via [onStatus].
     */
    fun check(userInitiated: Boolean) {
        ota.checkUpdate(userInitiated)
    }

    fun isUpdateCheckEnabled(): Boolean = ota.isUpdateCheckEnabled

    fun setUpdateCheckEnabled(enabled: Boolean) = ota.setUpdateCheckEnabled(enabled)

    private fun status(state: CheckState, text: String) {
        onStatus?.invoke(state, text)
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun showUpdateDialog(info: OTAManager3.UpdateInfo) {
        val body = info.changelogSinceCurrent?.takeIf { it.isNotEmpty() }
            ?: info.changelog?.takeIf { it.isNotEmpty() }
            ?: ""
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.dialog_ota_update_title, info.latestVersion))
            .setMessage(body)
            .setPositiveButton(R.string.dialog_ota_update_positive) { _, _ ->
                ota.download(info, OTAManager3.DownloadMode.DOWNLOAD_MODE_DIRECT)
            }
            .setNeutralButton(R.string.dialog_ota_update_neutral, null)
            .setNegativeButton(R.string.dialog_ota_skip) { _, _ ->
                ota.skipVersion(info.latestVersion)
                status(CheckState.RESULT, context.getString(R.string.toast_ota_skipped, info.latestVersion))
            }
            .show()
    }

    private fun showProgressDialog() {
        dismissProgress()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ota_progress, null)
        progressBar = view.findViewById(R.id.progressOta)
        progressText = view.findViewById(R.id.tvOtaProgress)
        progressBar?.isIndeterminate = true
        progressText?.text = context.getString(R.string.toast_ota_downloading)
        progressDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_ota_progress_title)
            .setView(view)
            .setCancelable(false)
            // Hides the dialog only — the download keeps running and the
            // install prompt still fires from OTAManager3 when it finishes.
            .setNegativeButton(R.string.dialog_ota_progress_hide, null)
            .show()
    }

    private fun updateProgress(percent: Int, done: Long, total: Long) {
        val bar = progressBar ?: return
        if (percent < 0) {
            bar.isIndeterminate = true
            progressText?.text = context.getString(R.string.toast_ota_downloading)
        } else {
            if (bar.isIndeterminate) { bar.isIndeterminate = false }
            bar.setProgressCompat(percent, true)
            progressText?.text = context.getString(
                R.string.ota_progress_size, done / 1048576.0, total / 1048576.0)
        }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBar = null
        progressText = null
    }

    /** One-time carry-over of the old OtaManager's enable/disable toggle. */
    private fun migrateOldPrefs() {
        val newPrefs = context.getSharedPreferences(OTA3_PREFS, Context.MODE_PRIVATE)
        if (newPrefs.contains(KEY_ENABLED)) return
        val oldPrefs = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        if (oldPrefs.contains(KEY_ENABLED)) {
            newPrefs.edit().putBoolean(KEY_ENABLED, oldPrefs.getBoolean(KEY_ENABLED, true)).apply()
        }
    }
}
