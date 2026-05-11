package com.ailife.clox.overlay.quicksettings

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.gridlayout.widget.GridLayout
import com.ailife.clox.R
import com.google.android.material.slider.Slider
import java.util.Calendar

class QuickSettingsOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val tileGrid: GridLayout
    private val brightnessSlider: Slider
    private val volumeSlider: Slider
    private val clockText: TextView
    private val blurBg: ImageView

    init {
        LayoutInflater.from(ctx).inflate(R.layout.overlay_quick_settings, this, true)
        tileGrid = findViewById(R.id.qsTileGrid)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        volumeSlider = findViewById(R.id.volumeSlider)
        clockText = findViewById(R.id.qsClockText)
        blurBg = findViewById<ImageView>(R.id.qsBlurBg).apply { tag = "blur_bg" }

        setupTiles()
        setupSliders()
        updateClock()
    }

    private fun setupTiles() {
        val tiles = listOf(
            Triple(R.drawable.ic_wifi, "Wi-Fi") { _: Boolean -> toggleWifi() },
            Triple(R.drawable.ic_bluetooth, "Bluetooth") { _: Boolean -> toggleBluetooth() },
            Triple(R.drawable.ic_dnd, "DND") { active: Boolean -> toggleDnd(active) },
            Triple(R.drawable.ic_flashlight, "Flashlight") { active: Boolean -> toggleFlashlight(active) },
            Triple(R.drawable.ic_airplane, "Airplane") { _: Boolean -> },
            Triple(R.drawable.ic_rotate, "Rotate") { _: Boolean -> }
        )

        tiles.forEach { (iconRes, label, toggle) ->
            val tile = QsTileView(context).apply {
                configure(iconRes, label, false)
                onToggle = toggle
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(8, 8, 8, 8)
            }
            tileGrid.addView(tile, params)
        }
    }

    private fun setupSliders() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

        volumeSlider.valueTo = maxVol.toFloat()
        volumeSlider.value = curVol.toFloat()
        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt(), 0)
        }

        brightnessSlider.valueTo = 255f
        brightnessSlider.value = getBrightness().toFloat()
        brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setBrightness(value.toInt())
        }
    }

    private fun updateClock() {
        val cal = Calendar.getInstance()
        clockText.text = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) updateClock()
    }

    private fun toggleWifi() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) wm.isWifiEnabled = !wm.isWifiEnabled
        // API 29+: user must toggle via system settings panel
    }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth() {
        val bt = BluetoothAdapter.getDefaultAdapter() ?: return
        if (bt.isEnabled) bt.disable() else bt.enable()
    }

    private fun toggleDnd(active: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(
                if (active) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
    }

    private fun toggleFlashlight(active: Boolean) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = cm.cameraIdList.firstOrNull() ?: return
        cm.setTorchMode(cameraId, active)
    }

    private fun getBrightness(): Int = try {
        android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS
        )
    } catch (e: Exception) { 128 }

    private fun setBrightness(value: Int) {
        android.provider.Settings.System.putInt(
            context.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
            value
        )
    }
}
