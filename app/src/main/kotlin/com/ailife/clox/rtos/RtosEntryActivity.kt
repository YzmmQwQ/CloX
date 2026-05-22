package com.ailife.clox.rtos

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ailife.clox.MainActivity

/** Launcher-visible entry point for RTOS mode. Brings launcher to front and activates RTOS. */
class RtosEntryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (RtosPrefs.isEnabled(this) && RtosPrefs.passwordHash(this) != null) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_ENTER_RTOS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }
}
