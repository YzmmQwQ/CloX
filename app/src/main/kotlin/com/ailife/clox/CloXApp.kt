package com.ailife.clox

import android.app.Application
import android.content.pm.PackageManager
import com.ailife.clox.crash.CrashHandler
import com.ailife.clox.qs.privilege.ShizukuOperations
import com.ailife.clox.util.MonetBackport
import com.google.android.material.color.DynamicColors
import rikka.shizuku.Shizuku

class CloXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DynamicColors.applyToActivitiesIfAvailable(this)
        MonetBackport.applyToActivitiesIfAvailable(this)
        CrashHandler.install(this)
        initShizuku()
    }

    private fun initShizuku() {
        // Bind the Shizuku UserService as soon as Shizuku server is available
        Shizuku.addBinderReceivedListenerSticky {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuOperations.bind()
            }
        }
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuOperations.bind()
            }
        }
        // If already available at startup
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuOperations.bind()
            }
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: CloXApp
            private set
    }
}
