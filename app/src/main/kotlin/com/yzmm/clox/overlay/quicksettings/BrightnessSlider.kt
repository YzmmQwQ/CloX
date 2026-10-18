package com.yzmm.clox.overlay.quicksettings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.google.android.material.slider.Slider

class BrightnessSlider @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : Slider(ctx, attrs) {

    private val innerCorner = 1f * ctx.resources.displayMetrics.density
    private val rect = RectF()
    private val path = Path()
    private val base: Class<*> = Slider::class.java.superclass!!

    private fun field(name: String): Any? =
        base.getDeclaredField(name).also { it.isAccessible = true }.get(this)

    override fun onDraw(canvas: Canvas) {
        val sidePad = (field("trackSidePadding") as Int).toFloat()
        val trkH   = (field("trackThickness")    as Int).toFloat()
        val trkW   = (field("trackWidth")        as Int).toFloat()
        val thumbW = (field("thumbWidth")         as Int).toFloat()
        val thumbH = (field("thumbHeight")        as Int).toFloat()
        val gap    = (field("thumbTrackGapSize")  as Int).toFloat()

        @Suppress("UNCHECKED_CAST")
        val values    = field("values")    as ArrayList<Float>
        val valueFrom = field("valueFrom") as Float
        val valueTo   = field("valueTo")   as Float

        val activePaint   = field("activeTrackPaint")   as Paint
        val inactivePaint = field("inactiveTrackPaint") as Paint
        val thumbDrawable = field("defaultThumbDrawable") as Drawable

        val cy = height / 2f
        val top    = cy - trkH / 2f
        val bottom = cy + trkH / 2f
        val trkLeft  = sidePad
        val trkRight = sidePad + trkW

        val fraction = if (valueTo > valueFrom) (values[0] - valueFrom) / (valueTo - valueFrom) else 0f
        val thumbX = trkLeft + fraction * trkW

        val outerR = 7f * resources.displayMetrics.density
        val ic = innerCorner

        // active track: outer (left) corners = pill, inner (right) corners = 1dp
        val activeRight = thumbX - thumbW / 2f - gap
        if (activeRight > trkLeft) {
            rect.set(trkLeft, top, activeRight, bottom)
            path.reset()
            // radii order: TL, TR, BR, BL (each as x,y pair)
            path.addRoundRect(rect, floatArrayOf(outerR, outerR, ic, ic, ic, ic, outerR, outerR), Path.Direction.CW)
            canvas.drawPath(path, activePaint)
        }

        // inactive track: inner (left) corners = 1dp, outer (right) corners = pill
        val inactiveLeft = thumbX + thumbW / 2f + gap
        if (inactiveLeft < trkRight) {
            rect.set(inactiveLeft, top, trkRight, bottom)
            path.reset()
            path.addRoundRect(rect, floatArrayOf(ic, ic, outerR, outerR, outerR, outerR, ic, ic), Path.Direction.CW)
            canvas.drawPath(path, inactivePaint)
        }

        // thumb
        val hw = (thumbW / 2f).toInt()
        val hh = (thumbH / 2f).toInt()
        val cx = thumbX.toInt()
        thumbDrawable.setBounds(cx - hw, cy.toInt() - hh, cx + hw, cy.toInt() + hh)
        thumbDrawable.state = drawableState
        thumbDrawable.draw(canvas)
    }
}
