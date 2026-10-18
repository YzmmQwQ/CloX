package com.yzmm.clox.overlay.widgets.builtin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class AnalogClockView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val onSurface  = resolveColor(com.google.android.material.R.attr.colorOnSurface)
    private val accent     = resolveColor(androidx.appcompat.R.attr.colorPrimary)
    private val surface    = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant)

    private val paintFace  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = surface; style = Paint.Style.FILL
    }
    private val paintRim   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface; style = Paint.Style.STROKE
        alpha = 40; strokeWidth = 2f
    }
    private val paintTick  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface; style = Paint.Style.STROKE
        alpha = 80; strokeWidth = 2f
    }
    private val paintHour  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface; style = Paint.Style.STROKE
        strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
    }
    private val paintMin   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface; style = Paint.Style.STROKE
        strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
    }
    private val paintSec   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE
        strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
    }
    private val paintDot   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface; style = Paint.Style.FILL
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = min(cx, cy) * 0.88f

        canvas.drawCircle(cx, cy, r, paintFace)
        canvas.drawCircle(cx, cy, r, paintRim)

        // Tick marks
        for (i in 0 until 60) {
            val angle  = Math.toRadians(i * 6.0)
            val isMajor = i % 5 == 0
            val inner  = r * (if (isMajor) 0.80f else 0.88f)
            val outer  = r * 0.94f
            paintTick.strokeWidth = if (isMajor) 2.5f else 1.5f
            paintTick.alpha       = if (isMajor) 120 else 60
            canvas.drawLine(
                (cx + inner * sin(angle)).toFloat(), (cy - inner * cos(angle)).toFloat(),
                (cx + outer * sin(angle)).toFloat(), (cy - outer * cos(angle)).toFloat(),
                paintTick
            )
        }

        val cal    = Calendar.getInstance()
        val hour   = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
        val minute = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val second = cal.get(Calendar.SECOND).toFloat()

        // Hour hand
        drawHand(canvas, cx, cy, Math.toRadians(hour * 30.0), r * 0.52f, paintHour)
        // Minute hand
        drawHand(canvas, cx, cy, Math.toRadians(minute * 6.0), r * 0.72f, paintMin)
        // Second hand
        drawHand(canvas, cx, cy, Math.toRadians(second * 6.0), r * 0.78f, paintSec)

        canvas.drawCircle(cx, cy, 5f, paintDot)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, angle: Double, length: Float, paint: Paint) {
        canvas.drawLine(cx, cy,
            (cx + length * sin(angle)).toFloat(),
            (cy - length * cos(angle)).toFloat(),
            paint
        )
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else Color.WHITE
    }
}
