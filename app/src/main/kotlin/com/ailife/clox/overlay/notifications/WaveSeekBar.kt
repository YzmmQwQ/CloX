package com.ailife.clox.overlay.notifications

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Custom progress bar matching Android 13+ QS media player style:
 * - Filled track (0 → thumb): animated sine wave traveling left→right
 *   with amplitude = 0 at the start, growing to max at the thumb
 * - Empty track (thumb → end): thin dim line
 * - Thumb: vertical pill (Material indicator, no circle)
 * - Wave amplitude fades in/out smoothly on play/pause via amplitudeScale animator
 */
class WaveSeekBar @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var max: Int = 100
        set(v) { field = v; invalidate() }

    var progress: Int = 0
        set(v) { field = v.coerceIn(0, max); invalidate() }

    var onSeekStart: (() -> Unit)? = null
    var onSeekEnd: ((Int) -> Unit)? = null
    var onProgressChanged: ((Int, Boolean) -> Unit)? = null

    var playing: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (v) {
                wavePhaseAnim.start()
                amplitudeAnim.setFloatValues(amplitudeScale, 1f)
            } else {
                amplitudeAnim.setFloatValues(amplitudeScale, 0f)
            }
            amplitudeAnim.start()
        }

    private var wavePhase = 0f
    private var amplitudeScale = 0f
    private var isDragging = false
    private val wavePath = Path()

    // Phase scrolls continuously while playing (left→right travel)
    private val wavePhaseAnim = ValueAnimator.ofFloat(0f, (2f * PI).toFloat()).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { wavePhase = it.animatedValue as Float; invalidate() }
    }

    // Amplitude scale fades in (0→1) on play, fades out (1→0) on pause
    private val amplitudeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400
        addUpdateListener { amplitudeScale = it.animatedValue as Float; invalidate() }
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (40 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val dp = resources.displayMetrics.density
        val trackH = 3f * dp
        val thumbW = 4f * dp
        val thumbH = 18f * dp
        val pad = 12f * dp
        val trackW = w - pad * 2f
        val fraction = if (max > 0) progress.toFloat() / max else 0f
        val progressX = pad + fraction * trackW

        val colorPrimary = resolveColor(androidx.appcompat.R.attr.colorPrimary)
        val colorSurface  = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant)

        trackPaint.color = colorSurface; trackPaint.strokeWidth = trackH
        progressPaint.color = colorPrimary; progressPaint.strokeWidth = trackH * 0.8f
        thumbPaint.color = colorPrimary

        // Background track — unplayed portion only (thumb → end), never overlaps the wave
        if (progressX < pad + trackW) {
            canvas.drawLine(progressX, cy, pad + trackW, cy, trackPaint)
        }

        // Filled progress: wave (uniform amplitude) or plain line when scale ≈ 0
        if (amplitudeScale > 0.01f && fraction > 0f) {
            val maxAmp = 5f * dp * amplitudeScale
            // Fixed pixel wavelength → constant spatial frequency regardless of progress length
            val wavelengthPx = 32f * dp
            wavePath.reset()
            var x = pad
            var first = true
            while (x <= progressX + 1f) {
                // wavePhase scrolls left→right; x/wavelengthPx gives constant spatial frequency
                val y = cy + sin(wavePhase + (x / wavelengthPx) * 2f * PI).toFloat() * maxAmp
                if (first) { wavePath.moveTo(x, y); first = false } else wavePath.lineTo(x, y)
                x += 2f
            }
            canvas.drawPath(wavePath, progressPaint)
        } else if (fraction > 0f) {
            canvas.drawLine(pad, cy, progressX, cy, progressPaint)
        }

        // Thumb — vertical pill
        val thumbRect = RectF(progressX - thumbW / 2f, cy - thumbH / 2f,
            progressX + thumbW / 2f, cy + thumbH / 2f)
        canvas.drawRoundRect(thumbRect, thumbW / 2f, thumbW / 2f, thumbPaint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val pad = 12f * resources.displayMetrics.density
        val trackW = width - pad * 2f
        val fraction = ((ev.x - pad) / trackW).coerceIn(0f, 1f)
        val newProgress = (fraction * max).toInt()
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onSeekStart?.invoke()
                progress = newProgress
                onProgressChanged?.invoke(newProgress, true)
            }
            MotionEvent.ACTION_MOVE -> {
                progress = newProgress
                onProgressChanged?.invoke(newProgress, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                progress = newProgress
                onSeekEnd?.invoke(newProgress)
            }
        }
        return true
    }

    private fun resolveColor(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0xFFFFFFFF.toInt())
        ta.recycle()
        return c
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        wavePhaseAnim.cancel()
        amplitudeAnim.cancel()
    }
}
