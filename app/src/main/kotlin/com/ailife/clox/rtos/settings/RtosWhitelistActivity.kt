package com.ailife.clox.rtos.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ailife.clox.R
import com.ailife.clox.rtos.RtosPrefs

/**
 * Lets the user toggle individual apps in/out of the RTOS Mode 2 whitelist.
 * Defaults: all non-user-installed apps, minus any with "browser"/"camera"/"settings" in name/pkg,
 * minus our own launcher.
 */
class RtosWhitelistActivity : AppCompatActivity() {

    data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
        var allowed: Boolean
    )

    private val entries = mutableListOf<AppEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()

        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.rtos_bg)) }

        val topBar = buildTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(56)
        ))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        loadApps(col)

        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = dp(56) })

        setContentView(root)
    }

    private fun loadApps(col: LinearLayout) {
        val pm = packageManager
        val whitelist = RtosPrefs.whitelist(this)
        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val ownPkg = packageName
        val blocked = setOf("browser", "camera", "settings")

        val activities = pm.queryIntentActivities(launchIntent, 0)
            .filterNot { it.activityInfo.packageName == ownPkg }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        for (ri in activities) {
            val pkg  = ri.activityInfo.packageName
            val act  = ri.activityInfo.name
            val label = ri.loadLabel(pm).toString()
            val key  = "$pkg/$act"

            val isSystem = (ri.activityInfo.applicationInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            val nameBlocked = blocked.any { it in pkg.lowercase() || it in label.lowercase() }
            val defaultAllowed = whitelist.let { wl ->
                if (wl.isEmpty()) isSystem && !nameBlocked else key in wl
            }

            val entry = AppEntry(label, pkg, act, defaultAllowed)
            entries.add(entry)

            val row = buildRow(entry)
            col.addView(row)
        }
    }

    private fun buildRow(entry: AppEntry): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))

            val cb = CheckBox(this@RtosWhitelistActivity).apply {
                isChecked = entry.allowed
                setOnCheckedChangeListener { _, checked ->
                    entry.allowed = checked
                    saveWhitelist()
                }
            }
            addView(cb, LinearLayout.LayoutParams(dp(40), dp(40)))

            val tv = TextView(this@RtosWhitelistActivity).apply {
                text = entry.label
                textSize = 14f
                setTextColor(Color.WHITE)
            }
            addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            setBackgroundColor(getColor(R.color.rtos_whitelist_bg))
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)

            val back = TextView(this@RtosWhitelistActivity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { finish() }
            }
            addView(back, LinearLayout.LayoutParams(dp(48), dp(56)))

            val title = TextView(this@RtosWhitelistActivity).apply {
                text = "Allowed Apps"
                textSize = 16f
                setTextColor(Color.WHITE)
            }
            addView(title)
        }
    }

    private fun saveWhitelist() {
        val allowed = entries.filter { it.allowed }.map { "${it.packageName}/${it.activityName}" }.toSet()
        RtosPrefs.setWhitelist(this, allowed)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
