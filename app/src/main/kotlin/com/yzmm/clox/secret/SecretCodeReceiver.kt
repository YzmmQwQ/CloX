package com.yzmm.clox.secret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.yzmm.clox.R
import com.yzmm.clox.applist.AppListPrefs

/**
 * Listens for *#*#CODE#*#* dialer entries.
 * Registered with android:host="*" so it catches any code; the password
 * comparison is done here in-process so no sensitive data leaks to the manifest.
 *
 * When the received code matches the stored hide-apps password:
 *  • Shows a toast confirming access
 *  • Sets AppListPrefs.hideAppsUnlocked so RootSettingsFragment shows the entry
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return

        val receivedCode = intent.data?.host ?: return
        val storedPassword = AppListPrefs.hideAppsPassword(context) ?: return

        if (receivedCode == storedPassword) {
            AppListPrefs.saveHideAppsPassword(context, null)
            Toast.makeText(
                context,
                R.string.secret_code_unlocked,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
