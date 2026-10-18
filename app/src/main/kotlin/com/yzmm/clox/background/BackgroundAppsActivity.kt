package com.yzmm.clox.background

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.wear.compose.material3.AppScaffold
import com.yzmm.clox.ui.theme.CloXWearTheme
import com.yzmm.clox.ui.theme.ScreenShapeConfig

/** Recently used apps, with a one-tap way to end them. Opened from the notification panel. */
class BackgroundAppsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenShapeConfig.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CloXWearTheme {
                AppScaffold {
                    BackgroundAppsScreen()
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, BackgroundAppsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
