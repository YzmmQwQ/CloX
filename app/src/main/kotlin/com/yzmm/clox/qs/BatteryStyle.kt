package com.yzmm.clox.qs

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.yzmm.clox.R

/**
 * Shared battery glyph styling for the in-app QS, global QS and status-bar strip.
 *
 * Produces a level-filled battery like the system QS: the empty portion is dimmed and the
 * filled portion (bottom-up, proportional to charge) is bright — NOT the whole icon dimmed.
 * Built from the existing authentic battery vector used twice (no hand-drawn paths).
 */
object BatteryStyle {

    private const val EMPTY_ALPHA = 55   // dim level for the un-filled portion

    fun levelDrawable(ctx: Context, pct: Int, charging: Boolean): Drawable {
        if (charging) {
            return ContextCompat.getDrawable(ctx, R.drawable.baseline_battery_charging_full_24)!!.mutate()
        }
        val p = pct.coerceIn(0, 100)
        val base = ContextCompat.getDrawable(ctx, R.drawable.baseline_battery_full_24)!!.mutate()
            .apply { alpha = EMPTY_ALPHA }
        val fill = ContextCompat.getDrawable(ctx, R.drawable.baseline_battery_full_24)!!.mutate()
        val clip = ClipDrawable(fill, Gravity.BOTTOM, ClipDrawable.VERTICAL).apply { level = p * 100 }
        return LayerDrawable(arrayOf(base, clip))
    }
}
