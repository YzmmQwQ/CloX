package com.yzmm.clox.overlay.widgets.picker

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import com.yzmm.clox.R

/**
 * Handles ACTION_CONFIRM_PIN_SHORTCUT from other apps.
 *
 * When an app calls ShortcutManager.requestPinShortcut(), Android delivers the
 * PinItemRequest here (as the default launcher). We accept it, persist the shortcut
 * info so it becomes available in the widget-drawer slot picker, then finish silently.
 */
class PinShortcutConfirmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action != LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT) return

        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        val request = launcherApps.getPinItemRequest(intent) ?: return

        if (request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) return
        if (!request.isValid) return

        val info = request.shortcutInfo ?: return

        // Accept the pin — this registers the shortcut with the system.
        if (!request.accept()) return

        val label = (info.shortLabel ?: info.longLabel ?: info.`package`).toString()
        PinnedShortcutPrefs.addShortcut(
            this,
            AcceptedShortcut(
                packageName = info.`package`,
                shortcutId  = info.id,
                label       = label
            )
        )

        Toast.makeText(this, getString(R.string.widget_shortcut_pinned, label), Toast.LENGTH_SHORT).show()
    }
}
