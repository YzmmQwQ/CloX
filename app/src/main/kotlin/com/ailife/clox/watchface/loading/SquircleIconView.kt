package com.ailife.clox.watchface.loading

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.ailife.clox.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * The launcher background clipped to a squircle (superellipse) — the static
 * backdrop of the loading indicator. The animated X sticks live in a sibling
 * ImageView driven by @drawable/avd_x_loading: an AnimatedVectorDrawable runs
 * on the RenderThread, so the pulse keeps playing even when the main thread
 * janks while the watchface loads on the GL thread. This view is static and
 * costs nothing per frame.
 */
class SquircleIconView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bgBitmap: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) bgBitmap = buildSquircleBackground(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        bgBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /** Launcher background scaled to fill and clipped to a superellipse squircle. */
    private fun buildSquircleBackground(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.clipPath(squirclePath(w.toFloat(), h.toFloat()))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_background, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) {
            sample *= 2
        }
        val src = BitmapFactory.decodeResource(
            resources, R.drawable.ic_launcher_background,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
        if (src != null) {
            canvas.drawBitmap(
                src, Rect(0, 0, src.width, src.height), RectF(0f, 0f, w.toFloat(), h.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            src.recycle()
        } else {
            canvas.drawColor(Color.BLACK)
        }
        return out
    }

    /** Superellipse |x/a|^n + |y/b|^n = 1 with n = 4, inset slightly for clean AA edges. */
    private fun squirclePath(w: Float, h: Float): Path {
        val inset = resources.displayMetrics.density // 1dp
        val a = w / 2f - inset
        val b = h / 2f - inset
        val cx = w / 2f
        val cy = h / 2f
        val exp = 2f / SQUIRCLE_N
        val path = Path()
        val steps = 128
        for (i in 0..steps) {
            val t = 2.0 * PI * i / steps
            val c = cos(t).toFloat()
            val s = sin(t).toFloat()
            val x = cx + a * sign(c) * Math.pow(abs(c).toDouble(), exp.toDouble()).toFloat()
            val y = cy + b * sign(s) * Math.pow(abs(s).toDouble(), exp.toDouble()).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    companion object {
        private const val SQUIRCLE_N = 4f
    }
}
