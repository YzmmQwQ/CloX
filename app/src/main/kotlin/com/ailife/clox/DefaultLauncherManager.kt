package com.ailife.clox

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DefaultLauncherManager(private val activity: Activity) {

    private val prefs: SharedPreferences =
        activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)

    fun isDefaultLauncher(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val info = activity.packageManager.resolveActivity(intent, 0)
            activity.packageName == info?.activityInfo?.packageName
        } catch (e: Exception) {
            Log.e(TAG, "Error checking default launcher", e)
            false
        }
    }

    fun checkAndPromptIfNeeded() {
        if (prefs.getBoolean(KEY_NEVER_ASK, false)) return
        if (isDefaultLauncher()) {
            prefs.edit().putBoolean(KEY_USER_WANTS_SET, false).apply()
            return
        }
        if (prefs.getBoolean(KEY_USER_WANTS_SET, false)) {
            openLauncherChooser()
        } else {
            showLauncherDialog()
        }
    }

    private fun showLauncherDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Set as Default Launcher")
            .setMessage("CloX needs to be set as your default launcher to work properly.\n\nNote: Some devices don't allow setting 'Always' on first selection — if that happens, just select it again.")
            .setPositiveButton("Set Now") { _, _ ->
                prefs.edit().putBoolean(KEY_USER_WANTS_SET, true).apply()
                openLauncherChooser()
            }
            .setNeutralButton("Ask Later") { d, _ -> d.dismiss() }
            .setNegativeButton("Never") { d, _ ->
                prefs.edit().putBoolean(KEY_NEVER_ASK, true).apply()
                d.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun openLauncherChooser() {
        try {
            val pm = activity.packageManager
            val fakeHome = ComponentName(activity, "${activity.packageName}.FakeHome")
            pm.setComponentEnabledSetting(fakeHome, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            try {
                activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            } catch (_: Exception) { }
            pm.setComponentEnabledSetting(fakeHome, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            Log.d(TAG, "Opened launcher chooser")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open launcher chooser", e)
        }
    }

    fun resetNeverAskAgain() {
        prefs.edit().putBoolean(KEY_NEVER_ASK, false).apply()
    }

    companion object {
        private const val TAG = "DefaultLauncherManager"
        private const val PREFS_NAME = "default_launcher_prefs"
        private const val KEY_NEVER_ASK = "never_ask_again"
        private const val KEY_USER_WANTS_SET = "user_wants_set_as_default"
    }
}
