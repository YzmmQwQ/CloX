package com.ailife.clox.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ailife.clox.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class OtaManager(private val context: Context) {

    companion object {
        private const val GITLAB_OWNER = "ailife8881"
        private const val GITLAB_REPO = "clox"
        private const val GITLAB_API_BASE = "https://gitlab.com/api/v4"
        private const val DOWNLOAD_FILE = "clox-update.apk"
        private const val PREFS_NAME = "OtaManagerPrefs"
        private const val PREF_UPDATE_ENABLED = "update_check_enabled"
        private const val MIME_TYPE = "application/vnd.android.package-archive"
        private const val TAG = "CloX/OTA"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestVersion: String? = null
    private var changelog: String? = null
    private var downloadUrl: String? = null

    fun isUpdateCheckEnabled(): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_UPDATE_ENABLED, true)

    fun setUpdateCheckEnabled(enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_UPDATE_ENABLED, enabled).apply()
    }

    fun checkUpdate(silent: Boolean = false) {
        if (silent && !isUpdateCheckEnabled()) return
        if (!isNetworkAvailable()) {
            if (!silent) showToast(context.getString(R.string.toast_ota_no_internet))
            return
        }

        val projectPath = "$GITLAB_OWNER/$GITLAB_REPO".replace("/", "%2F")
        val apiUrl = "$GITLAB_API_BASE/projects/$projectPath/releases/permalink/latest"

        thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    if (!silent) showToast(context.getString(R.string.toast_ota_check_failed_code, conn.responseCode))
                    return@thread
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseRelease(body, silent)
            } catch (e: Exception) {
                Log.e(TAG, "Check failed: ${e.message}")
                if (!silent) showToast(context.getString(R.string.toast_ota_check_failed, e.message))
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun parseRelease(json: String, silent: Boolean) {
        try {
            val release = JSONObject(json)
            var version = release.getString("tag_name")
            if (version.startsWith("v", ignoreCase = true)) version = version.substring(1)
            latestVersion = version
            changelog = cleanMarkdown(release.optString("description", "No changelog available"))

            val links = release.optJSONObject("assets")?.optJSONArray("links")
            if (links != null) {
                for (i in 0 until links.length()) {
                    val link = links.getJSONObject(i)
                    if (link.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = link.optString("url", "")
                        break
                    }
                }
            }

            if (downloadUrl == null) {
                if (!silent) showToast(context.getString(R.string.toast_ota_no_apk))
                return
            }
            promptIfNewer(silent)
        } catch (e: JSONException) {
            Log.e(TAG, "Parse error: ${e.message}")
            if (!silent) showToast(context.getString(R.string.toast_ota_parse_failed))
        }
    }

    private fun promptIfNewer(silent: Boolean) {
        try {
            val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            val newer = compareVersions(current, latestVersion ?: "0.0.0") < 0
            mainHandler.post {
                if (newer) showUpdateDialog()
                else if (!silent) showToast(context.getString(R.string.toast_ota_up_to_date, current))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Version compare failed: ${e.message}")
        }
    }

    private fun showUpdateDialog() {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.dialog_ota_update_title, latestVersion))
            .setMessage(changelog)
            .setPositiveButton(R.string.dialog_ota_update_positive) { _, _ -> startDownload() }
            .setNeutralButton(R.string.dialog_ota_update_neutral, null)
            .setNegativeButton(R.string.dialog_ota_update_negative) { _, _ ->
                setUpdateCheckEnabled(false)
                showToast(context.getString(R.string.toast_ota_auto_disabled))
            }
            .show()
    }

    private fun startDownload() {
        val url = downloadUrl ?: return
        val dest = File(context.getExternalFilesDir(null), DOWNLOAD_FILE)
        if (dest.exists()) dest.delete()

        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(MIME_TYPE)
            .setTitle(context.getString(R.string.ota_download_notif_title))
            .setDescription("v$latestVersion")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, DOWNLOAD_FILE)

        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        listenForDownload(dest)
        showToast(context.getString(R.string.toast_ota_downloading))
    }

    private fun listenForDownload(file: File) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (file.exists()) {
                    installApk(file)
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.watchface.files", file)
            } else {
                Uri.fromFile(file)
            }
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}")
            showToast(context.getString(R.string.toast_ota_install_failed, e.message))
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val clean = Regex("[^0-9.]")
        val p1 = v1.replace(clean, "").split(".").map { it.toIntOrNull() ?: 0 }
        val p2 = v2.replace(clean, "").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(p1.size, p2.size)) {
            val diff = (p1.getOrElse(i) { 0 }) - (p2.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun cleanMarkdown(text: String) =
        text.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1").replace(Regex("[*#_`]+"), "")

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.getNetworkCapabilities(cm.activeNetwork ?: return false)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun showToast(msg: String) =
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
}
