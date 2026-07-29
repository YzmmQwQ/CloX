package com.ailife.clox.overlay.global

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.qs.BatteryStyle
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Date

/**
 * Custom status-bar strip drawn over other apps. Shows a configurable set of items: clock,
 * notification app-icons, internet speed, wifi/data/bluetooth status, and battery (icon dimmed
 * by charge level). Detects a downward swipe/tap to request the global QS panel. Horizontal
 * inset padding keeps content clear of the watch's rounded corners while the bar stays full-width.
 */
class GlobalStatusBarView(
    context: Context
) : FrameLayout(context) {

    private val row: LinearLayout
    private val clock: TextView
    private val notifs: LinearLayout
    private val speed: TextView
    private val batteryPct: TextView
    private val batteryIcon: ImageView
    private val wifiIcon: ImageView
    private val dataIcon: ImageView
    private val btIcon: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    private var lastBytes = 0L
    private var lastSpeedTime = 0L

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = updateClock()
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = updateBattery(intent)
    }
    private val connReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = updateConnectivity()
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_global_status_bar, this, true)
        row         = findViewById(R.id.globalSbRow)
        clock       = findViewById(R.id.globalSbClock)
        notifs      = findViewById(R.id.globalSbNotifs)
        speed       = findViewById(R.id.globalSbSpeed)
        batteryPct  = findViewById(R.id.globalSbBatteryPct)
        batteryIcon = findViewById(R.id.globalSbBatteryIcon)
        wifiIcon    = findViewById(R.id.globalSbWifi)
        dataIcon    = findViewById(R.id.globalSbData)
        btIcon      = findViewById(R.id.globalSbBluetooth)

        // Inset the row contents (not the background) so nothing is clipped by rounded corners.
        val pct = GlobalOverlayPrefs.insetPct(context).coerceIn(0, 40) / 100f
        val padH = (context.resources.displayMetrics.widthPixels * pct).toInt()
        row.setPadding(padH, 0, padH, 0)

        applyItemVisibility()
        updateClock()
    }

    private fun applyItemVisibility() {
        clock.visibility      = if (GlobalOverlayPrefs.itemClock(context)) VISIBLE else GONE
        speed.visibility      = if (GlobalOverlayPrefs.itemSpeed(context)) VISIBLE else GONE
        notifs.visibility     = if (GlobalOverlayPrefs.itemNotifs(context)) VISIBLE else GONE
        batteryPct.visibility = if (GlobalOverlayPrefs.itemBattery(context)) VISIBLE else GONE
        batteryIcon.visibility = if (GlobalOverlayPrefs.itemBattery(context)) VISIBLE else GONE
        // wifi/data/bt visibility is also gated by their live state in updateConnectivity()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        val batteryIntent = context.registerReceiver(
            batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let { updateBattery(it) }
        context.registerReceiver(connReceiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        })
        updateConnectivity()

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()).also { sc ->
            if (GlobalOverlayPrefs.itemNotifs(context)) {
                CloXNotificationListenerService.notifications
                    .onEach { renderNotifs(it.map { sbn -> sbn.packageName }.distinct()) }
                    .launchIn(sc)
                renderNotifs(CloXNotificationListenerService.currentNotifications
                    .map { it.packageName }.distinct())
            }
        }
        if (GlobalOverlayPrefs.itemSpeed(context)) {
            lastBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
            lastSpeedTime = android.os.SystemClock.elapsedRealtime()
            handler.postDelayed(speedTick, 1500L)
        }
        Log.d(TAG, "status bar strip attached")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(tickReceiver) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(connReceiver) }
        handler.removeCallbacks(speedTick)
        scope?.cancel()
        scope = null
        Log.d(TAG, "status bar strip detached")
    }

    private fun updateClock() {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        clock.text = DateFormat.format(pattern, Date()).toString()
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        batteryPct.text = "$pct%"
        // Level-filled battery glyph (empty portion dimmed) like the system QS.
        batteryIcon.setImageDrawable(BatteryStyle.levelDrawable(context, pct, charging))
    }

    private fun updateConnectivity() {
        val wifiOn = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        }.getOrDefault(false)
        val btOn = runCatching {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        }.getOrDefault(false)
        val dataOn = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }.getOrDefault(false)

        wifiIcon.visibility = if (wifiOn && GlobalOverlayPrefs.itemWifi(context)) VISIBLE else GONE
        btIcon.visibility   = if (btOn && GlobalOverlayPrefs.itemBluetooth(context)) VISIBLE else GONE
        dataIcon.visibility = if (dataOn && GlobalOverlayPrefs.itemData(context)) VISIBLE else GONE
    }

    // ── internet speed ─────────────────────────────────────────────────────────
    private val speedTick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            val bytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
            val dt = (now - lastSpeedTime).coerceAtLeast(1L)
            val bps = ((bytes - lastBytes) * 1000L / dt).coerceAtLeast(0L)
            lastBytes = bytes
            lastSpeedTime = now
            speed.text = formatSpeed(bps)
            handler.postDelayed(this, 1500L)
        }
    }

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1024L * 1024L -> "%.1f MB/s".format(bps / 1024f / 1024f)
        bps >= 1024L         -> "%.0f KB/s".format(bps / 1024f)
        else                 -> "$bps B/s"
    }

    // ── notification app icons ──────────────────────────────────────────────────
    private fun renderNotifs(pkgs: List<String>) {
        notifs.removeAllViews()
        val pm = context.packageManager
        val sizePx = (16 * context.resources.displayMetrics.density).toInt()
        pkgs.take(4).forEach { pkg ->
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return@forEach
            val iv = ImageView(context).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).also { it.marginEnd = (2 * context.resources.displayMetrics.density).toInt() }
            }
            notifs.addView(iv)
        }
    }

    // Display-only: don't consume touch — the hosting overlay window handles all gestures.

    companion object { private const val TAG = "GlobalStatusBar" }
}
