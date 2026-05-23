package com.ailife.clox

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
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
            .setTitle(R.string.dialog_default_launcher_title)
            .setMessage(R.string.dialog_default_launcher_message)
            .setPositiveButton(R.string.dialog_default_launcher_positive) { _, _ ->
                prefs.edit().putBoolean(KEY_USER_WANTS_SET, true).apply()
                openLauncherChooser()
            }
            .setNeutralButton(R.string.dialog_default_launcher_neutral) { d, _ -> d.dismiss() }
            .setNegativeButton(R.string.dialog_default_launcher_negative) { d, _ ->
                prefs.edit().putBoolean(KEY_NEVER_ASK, true).apply()
                d.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun openLauncherChooser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = activity.getSystemService(RoleManager::class.java)
                if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    activity.startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_HOME)
                    Log.d(TAG, "Opened RoleManager home role request")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open RoleManager launcher chooser", e)
            }
        } else {
            try {
                val pm = activity.packageManager
                val fakeHome = ComponentName(activity, "${activity.packageName}.FakeHome")
                pm.setComponentEnabledSetting(fakeHome, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                try {
                    activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                } catch (_: Exception) { }
                pm.setComponentEnabledSetting(fakeHome, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                Log.d(TAG, "Opened FakeHome launcher chooser")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open launcher chooser", e)
            }
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
        const val REQUEST_CODE_SET_DEFAULT_HOME = 1337
    }
}
