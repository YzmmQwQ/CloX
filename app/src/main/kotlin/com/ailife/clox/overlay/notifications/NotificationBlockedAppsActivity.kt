package com.ailife.clox.overlay.notifications

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.ailife.clox.R
import com.ailife.clox.util.safeStartActivity
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * Lets the user manage which apps are blocked from the CloX notification panel.
 * Toggling a checkbox writes the package into [NotificationPrefs.NOTIF_BLOCKED_APPS].
 */
class NotificationBlockedAppsActivity : AppCompatActivity() {

    private data class AppEntry(val label: String, val pkg: String, val icon: Drawable, var blocked: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val surface = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorSurface, 0)
        val onSurface = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorOnSurface, 0)

        val root = FrameLayout(this).apply { setBackgroundColor(surface) }
        root.addView(buildTopBar(onSurface),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(56)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = dp(56) })
        setContentView(root)

        loadApps(col, onSurface)
    }

    private fun loadApps(col: LinearLayout, textColor: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        lifecycleScope.launch {
            val blocked = CloXNotificationListenerService.blockedApps(prefs)
            val entries = withContext(Dispatchers.IO) {
                val pm = packageManager
                val launch = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                pm.queryIntentActivities(launch, 0)
                    .filterNot { it.activityInfo.packageName == packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName,
                        it.loadIcon(pm), it.activityInfo.packageName in blocked) }
                    .sortedBy { it.label.lowercase() }
            }
            entries.forEach { col.addView(buildRow(it, textColor, prefs)) }
        }
    }

    private fun buildRow(entry: AppEntry, textColor: Int, prefs: android.content.SharedPreferences): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(ImageView(this@NotificationBlockedAppsActivity).apply {
                setImageDrawable(entry.icon); scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            addView(TextView(this@NotificationBlockedAppsActivity).apply {
                text = entry.label; textSize = 14f; setTextColor(textColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(CheckBox(this@NotificationBlockedAppsActivity).apply {
                isChecked = entry.blocked
                setOnCheckedChangeListener { _, checked ->
                    entry.blocked = checked
                    CloXNotificationListenerService.setAppBlocked(prefs, entry.pkg, checked)
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

    private fun buildTopBar(textColor: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            addView(ImageView(this@NotificationBlockedAppsActivity).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(textColor)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(12), dp(16), dp(12), dp(16))
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(TextView(this@NotificationBlockedAppsActivity).apply {
                text = getString(R.string.notif_blocked_apps_title)
                textSize = 16f; setTextColor(textColor)
            })
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun launch(ctx: Context) =
            ctx.safeStartActivity(Intent(ctx, NotificationBlockedAppsActivity::class.java))
    }
}
