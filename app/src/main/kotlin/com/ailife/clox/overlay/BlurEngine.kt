package com.ailife.clox.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.view.Window
import android.widget.ImageView
import com.ailife.clox.cocos.SnapshotHelper

object BlurEngine {

    enum class Mode { AUTO, SNAPSHOT, DISABLED }

    private const val PREFS = "blur_settings"
    private const val KEY_MODE = "blur_mode"
    private const val KEY_RADIUS = "blur_radius"
    private const val DEFAULT_RADIUS = 15f
    private const val DOWNSAMPLE = 4

    fun getMode(context: Context): Mode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.AUTO.name) ?: Mode.AUTO.name
        return try { Mode.valueOf(raw) } catch (e: Exception) { Mode.AUTO }
    }

    fun getRadius(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_RADIUS, DEFAULT_RADIUS)

    fun applyBlur(
        context: Context,
        window: Window,
        targetView: View,
        bgImageView: ImageView,
        onDone: () -> Unit
    ) {
        when (resolveStrategy(context)) {
            Strategy.RENDER_EFFECT -> applyRenderEffect(bgImageView, getRadius(context), onDone)
            Strategy.RENDER_SCRIPT -> applyRenderScript(context, window, bgImageView, onDone)
            Strategy.SNAPSHOT_STACKBLUR -> applySnapshotBlur(context, window, bgImageView, onDone)
            Strategy.SCRIM -> {
                bgImageView.visibility = View.GONE
                onDone()
            }
        }
    }

    fun clearBlur(bgImageView: ImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bgImageView.setRenderEffect(null)
        }
        bgImageView.setImageDrawable(null)
        bgImageView.visibility = View.GONE
    }

    private enum class Strategy { RENDER_EFFECT, RENDER_SCRIPT, SNAPSHOT_STACKBLUR, SCRIM }

    private fun resolveStrategy(context: Context): Strategy {
        val mode = getMode(context)
        if (mode == Mode.DISABLED) return Strategy.SCRIM
        return when {
            mode == Mode.SNAPSHOT -> Strategy.SNAPSHOT_STACKBLUR
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Strategy.RENDER_EFFECT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> Strategy.RENDER_SCRIPT
            else -> Strategy.SCRIM
        }
    }

    @Suppress("DEPRECATION")
    private fun applyRenderEffect(bgImageView: ImageView, radius: Float, onDone: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val effect = android.graphics.RenderEffect.createBlurEffect(
                radius, radius, android.graphics.Shader.TileMode.CLAMP
            )
            bgImageView.setRenderEffect(effect)
        }
        onDone()
    }

    @Suppress("DEPRECATION")
    private fun applyRenderScript(
        context: Context,
        window: Window,
        bgImageView: ImageView,
        onDone: () -> Unit
    ) {
        val displayMetrics = context.resources.displayMetrics
        val fullRect = android.graphics.Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        SnapshotHelper.capture(window, fullRect) { bitmap ->
            bitmap ?: run { onDone(); return@capture }
            val downW = bitmap.width / DOWNSAMPLE
            val downH = bitmap.height / DOWNSAMPLE
            val down = Bitmap.createScaledBitmap(bitmap, downW, downH, false)
            val blurred = blurWithRenderScript(context, down, getRadius(context).coerceAtMost(25f))
            val upscaled = Bitmap.createScaledBitmap(blurred, bitmap.width, bitmap.height, true)
            bgImageView.setImageBitmap(upscaled)
            bgImageView.visibility = View.VISIBLE
            onDone()
        }
    }

    private fun applySnapshotBlur(
        context: Context,
        window: Window,
        bgImageView: ImageView,
        onDone: () -> Unit
    ) {
        val displayMetrics = context.resources.displayMetrics
        val fullRect = android.graphics.Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        SnapshotHelper.capture(window, fullRect) { bitmap ->
            bitmap ?: run { onDone(); return@capture }
            val downW = bitmap.width / DOWNSAMPLE
            val downH = bitmap.height / DOWNSAMPLE
            val down = Bitmap.createScaledBitmap(bitmap, downW, downH, false)
            val blurred = stackBlur(down, 6)
            val upscaled = Bitmap.createScaledBitmap(blurred, bitmap.width, bitmap.height, true)
            bgImageView.setImageBitmap(upscaled)
            bgImageView.visibility = View.VISIBLE
            onDone()
        }
    }

    @Suppress("DEPRECATION")
    private fun blurWithRenderScript(context: Context, bitmap: Bitmap, radius: Float): Bitmap {
        val rs = RenderScript.create(context)
        val input = Allocation.createFromBitmap(rs, bitmap)
        val output = Allocation.createTyped(rs, input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius)
        script.setInput(input)
        script.forEach(output)
        output.copyTo(bitmap)
        rs.destroy()
        return bitmap
    }

    // Simple stack blur (pure Java, no RS dependency)
    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        // Minimal box blur approximation (3 passes)
        repeat(3) { boxBlurPass(pixels, out.width, out.height, radius) }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int, r: Int) {
        val tmp = pixels.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
                for (dx in -r..r) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val px = tmp[y * w + nx]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                    count++
                }
                pixels[y * w + x] = (0xFF shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
            }
        }
    }
}
