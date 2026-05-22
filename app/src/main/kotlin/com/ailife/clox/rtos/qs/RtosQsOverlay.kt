package com.ailife.clox.rtos.qs

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.VibrationEffect
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ailife.clox.R
import com.ailife.clox.rtos.fakesettings.FakeSettingsActivity

/**
 * RTOS fake quick settings panel — swipe-down in Mode 2.
 * 3 rows × 2 columns, large pill tiles, white vector icons, no text labels.
 * Swipe-up or tap background to dismiss.
 */
class RtosQsOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var onDismiss: (() -> Unit)? = null

    private var brightnessStep = 2
    private var touchStartY = 0f
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val TILE_OFF = Color.parseColor("#2A2A2A")
    private val TILE_ON  = Color.parseColor("#1565C0")

    private lateinit var btTile: FrameLayout
    private lateinit var sndTile: FrameLayout
    private lateinit var sndIcon: ImageView

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        setOnClickListener { onDismiss?.invoke() }

        val tileH   = dp(88)
        val outerM  = dp(12)
        val innerM  = dp(8)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerM, outerM, outerM, outerM)
        }

        btTile   = iconTile(R.drawable.ic_bluetooth)  { toggleBluetooth() }
        val batTile  = batteryTile()
        sndTile  = iconTile(R.drawable.ic_volume)      { toggleSound() }.also { tile ->
            sndIcon = tile.getChildAt(0) as ImageView
        }
        val briTile  = iconTile(R.drawable.ic_brightness_high) { stepBrightness() }
        val setTile  = iconTile(R.drawable.ic_settings) { openFakeSettings() }
        val qrTile   = iconTile(R.drawable.ic_rtos_qr) { openQr() }

        // Apply initial BT state color
        updateColor(btTile, BluetoothAdapter.getDefaultAdapter()?.isEnabled == true)

        val rowLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val rowLpGap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = innerM }

        col.addView(row(tileH, innerM, btTile, batTile), rowLp)
        col.addView(row(tileH, innerM, sndTile, briTile), rowLpGap)
        col.addView(row(tileH, innerM, setTile, qrTile), rowLpGap)

        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
    }

    // ── Tile builders ──────────────────────────────────────────────────────────

    private fun iconTile(iconRes: Int, onClick: () -> Unit): FrameLayout {
        return FrameLayout(context).apply {
            background = pillBg(TILE_OFF)
            isClickable = true
            isFocusable = true
            val icon = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, iconRes))
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            addView(icon, LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER })
            setOnClickListener { vibrate(); onClick() }
        }
    }

    private fun batteryTile(): FrameLayout {
        return FrameLayout(context).apply {
            background = pillBg(TILE_OFF)
            isClickable = false
            val tv = TextView(context).apply {
                text = getBatteryText()
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun row(tileH: Int, gap: Int, left: FrameLayout, right: FrameLayout): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left,  LinearLayout.LayoutParams(0, tileH, 1f))
            addView(right, LinearLayout.LayoutParams(0, tileH, 1f).apply { leftMargin = gap })
        }
    }

    private fun pillBg(color: Int) = GradientDrawable().apply {
        shape        = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = 999f
    }

    private fun updateColor(tile: FrameLayout, active: Boolean) {
        (tile.background as? GradientDrawable)?.setColor(if (active) TILE_ON else TILE_OFF)
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    private fun toggleBluetooth() {
        runCatching {
            val bt = BluetoothAdapter.getDefaultAdapter() ?: return
            val nowOn = !bt.isEnabled
            if (bt.isEnabled) bt.disable() else bt.enable()
            updateColor(btTile, nowOn)
        }
    }

    private fun toggleSound() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val next = if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL)
            AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
        runCatching { am.ringerMode = next }
        val soundOn = next == AudioManager.RINGER_MODE_NORMAL
        updateColor(sndTile, soundOn)
        sndIcon.setImageResource(if (soundOn) R.drawable.ic_volume else R.drawable.ic_rtos_vibrate)
    }

    private fun stepBrightness() {
        brightnessStep = (brightnessStep + 1) % 5
        val value = (brightnessStep * 255 / 4).coerceIn(1, 255)
        runCatching {
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value
            )
        }
    }

    private fun openFakeSettings() {
        onDismiss?.invoke()
        context.startActivity(Intent(context, FakeSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openQr() {
        onDismiss?.invoke()
        context.startActivity(Intent(context, FakeSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(FakeSettingsActivity.EXTRA_START_PAGE, FakeSettingsActivity.PAGE_QR)
        })
    }

    private fun getBatteryText(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
    }

    private fun vibrate() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as android.os.VibratorManager).defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
                    .vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchStartY = ev.y
            MotionEvent.ACTION_MOVE ->
                if (touchStartY - ev.y > touchSlop * 3) return true   // steal upward swipe from tiles
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchStartY = ev.y
            MotionEvent.ACTION_UP   -> if (touchStartY - ev.y > touchSlop * 2) onDismiss?.invoke()
        }
        return true
    }
}
