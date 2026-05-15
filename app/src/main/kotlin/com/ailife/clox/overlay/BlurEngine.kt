package com.ailife.clox.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.ImageView
import com.ailife.clox.cocos.CocosManager

object BlurEngine {

    enum class Mode { AUTO, SNAPSHOT, DISABLED }

    private const val PREFS = "blur_settings"
    private const val KEY_MODE = "blur_mode"
    private const val KEY_RADIUS = "blur_radius"
    private const val DEFAULT_RADIUS = 15f
    private const val DOWNSAMPLE = 4

    // Incremented on every new capture and on clearBlur — lets the PixelCopy callback
    // detect that its result is stale (cancelled or superseded) and discard it.
    private var captureSeq = 0

    fun getMode(context: Context): Mode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.AUTO.name) ?: Mode.AUTO.name
        return try { Mode.valueOf(raw) } catch (e: Exception) { Mode.AUTO }
    }

    fun getRadius(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_RADIUS, DEFAULT_RADIUS)

    /**
     * Capture the watchface into [bgImageView] as a raw (unblurred) bitmap.
     * Blur is applied progressively via [updateProgress] as the user drags.
     */
    fun applyBlur(
        context: Context,
        window: Window,
        targetView: View,
        bgImageView: ImageView,
        onDone: () -> Unit
    ) {
        Log.d("BlurEngine", "applyBlur mode=${getMode(context)} SDK=${Build.VERSION.SDK_INT}")
        if (getMode(context) == Mode.DISABLED) { bgImageView.visibility = View.GONE; onDone(); return }
        val seq = ++captureSeq
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureRaw(context, bgImageView, seq, onDone)
        } else {
            bgImageView.visibility = View.GONE
            onDone()
        }
    }

    /**
     * Called every drag frame with progress 0→1.
     * API 31+: updates RenderEffect blur radius (0→maxRadius).
     * Pre-31:  fades alpha (0→1) on a pre-blurred bitmap.
     */
    fun updateProgress(context: Context, bgImageView: ImageView, progress: Float) {
        if (getMode(context) == Mode.DISABLED) return
        if (bgImageView.drawable == null) return   // bitmap not ready yet
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val r = getRadius(context) * progress
            bgImageView.alpha = 1f
            bgImageView.visibility = View.VISIBLE
            bgImageView.setRenderEffect(
                if (r >= 1f) android.graphics.RenderEffect.createBlurEffect(
                    r, r, android.graphics.Shader.TileMode.CLAMP)
                else null
            )
        } else {
            bgImageView.alpha = progress
        }
    }

    fun clearBlur(window: Window, bgImageView: ImageView) {
        captureSeq++   // invalidate any in-flight PixelCopy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bgImageView.setRenderEffect(null)
        }
        bgImageView.setImageDrawable(null)
        bgImageView.visibility = View.GONE
    }

    // ── Raw capture — bitmap stored unblurred; RenderEffect drives progressive blur ──

    @Suppress("DEPRECATION")
    private fun captureRaw(
        context: Context,
        bgImageView: ImageView,
        seq: Int,
        onDone: () -> Unit
    ) {
        val glView = CocosManager.getGlView()
        Log.d("BlurEngine", "captureRaw glView=$glView seq=$seq")
        if (glView == null) { onDone(); return }

        // Wait for next vsync so the GL surface has a committed frame ready
        Choreographer.getInstance().postFrameCallback {
            if (seq != captureSeq) { Log.d("BlurEngine", "captureRaw stale at vsync seq=$seq"); return@postFrameCallback }
            val dm = context.resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // null srcRect = capture entire SurfaceView surface — avoids fullscreen coordinate shift
            PixelCopy.request(glView, null, bitmap, { result ->
                Log.d("BlurEngine", "PixelCopy result=$result SUCCESS=${PixelCopy.SUCCESS} seq=$seq current=$captureSeq")
                if (seq != captureSeq) { Log.d("BlurEngine", "captureRaw stale at PixelCopy callback, discarding"); return@request }
                if (result == PixelCopy.SUCCESS) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // API 31+: set raw bitmap; RenderEffect drives progressive blur in updateProgress
                        bgImageView.setImageBitmap(bitmap)
                    } else {
                        // Pre-API 31: bake software blur into bitmap now (RenderEffect unavailable)
                        val downW = w / DOWNSAMPLE
                        val downH = h / DOWNSAMPLE
                        val down = Bitmap.createScaledBitmap(bitmap, downW, downH, false)
                        val blurred = stackBlur(down, 8)
                        bgImageView.setImageBitmap(Bitmap.createScaledBitmap(blurred, w, h, true))
                    }
                    bgImageView.alpha = 0f   // start hidden; updateProgress drives alpha/radius
                    bgImageView.visibility = View.VISIBLE
                }
                onDone()
            }, Handler(Looper.getMainLooper()))
        }
    }

    // ── Software blur for pre-API 31 ──────────────────────────────────────────

    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        repeat(3) { boxBlurPass(pixels, out.width, out.height, radius) }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int, r: Int) {
        val tmp = pixels.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rS = 0; var gS = 0; var bS = 0; var n = 0
                for (dx in -r..r) {
                    val px = tmp[y * w + (x + dx).coerceIn(0, w - 1)]
                    rS += (px shr 16) and 0xFF; gS += (px shr 8) and 0xFF; bS += px and 0xFF; n++
                }
                pixels[y * w + x] = (0xFF shl 24) or ((rS/n) shl 16) or ((gS/n) shl 8) or (bS/n)
            }
        }
    }
}
