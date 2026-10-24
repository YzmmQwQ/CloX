package com.yzmm.clox

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Launcher icon that only exists to surface the control center: hands the home activity an
 * EXTRA_OPEN_QS intent and disappears immediately.
 */
class QuickSettingsShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same process as MainActivity: flag the request and let its next resume consume it.
        QuickSettingsShortcut.pending = true
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}

object QuickSettingsShortcut {
    @Volatile var pending = false
}
