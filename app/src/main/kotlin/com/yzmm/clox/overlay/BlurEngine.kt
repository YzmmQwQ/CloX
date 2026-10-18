package com.yzmm.clox.overlay

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.ImageView
import androidx.preference.PreferenceManager
import com.yzmm.clox.cocos.CocosManager
import eightbitlab.com.blurview.RenderScriptBlur

object BlurEngine {

    enum class Mode { REALTIME, BLURVIEW, SNAPSHOT, DISABLED }

    private const val KEY_MODE   = "blur_mode"
    private const val KEY_RADIUS = "blur_radius"
    private const val DEFAULT_RADIUS = 15
    private const val DOWNSAMPLE = 4

    // Incremented on every new capture and on clearBlur so stale PixelCopy
    // callbacks can detect they've been superseded and discard their result.
    private var captureSeq = 0

    fun getMode(context: Context): Mode {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_MODE, Mode.BLURVIEW.name) ?: Mode.BLURVIEW.name
        return when (raw) {
            "AUTO" -> Mode.BLURVIEW   // migrate pre-existing setting
            else   -> try { Mode.valueOf(raw) } catch (_: Exception) { Mode.BLURVIEW }
        }
    }

    // SeekBarPreference persists as Int; cast to Float for GL/RenderEffect.
    fun getRadius(context: Context): Float =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(KEY_RADIUS, DEFAULT_RADIUS).toFloat()

    /**
     * Begin blur for an opening overlay.
     * REALTIME: signals C++ to start blurring; no capture needed.
     * BLURVIEW/SNAPSHOT: captures the watchface into [bgImageView] via PixelCopy.
     * DISABLED: no-op; [bgImageView] stays hidden.
     */
    fun applyBlur(
        context: Context,
        window: Window,
        targetView: View,
        bgImageView: ImageView,
        onDone: () -> Unit
    ) {
        val mode = getMode(context)
        Log.d("BlurEngine", "applyBlur mode=$mode SDK=${Build.VERSION.SDK_INT}")
        when (mode) {
            Mode.DISABLED -> {
                bgImageView.visibility = View.GONE
                onDone()
            }
            Mode.REALTIME -> {
                // C++ drives the blur radius; no static capture required.
                bgImageView.visibility = View.GONE
                onDone()
            }
            Mode.BLURVIEW, Mode.SNAPSHOT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    captureAndBlur(context, bgImageView, ++captureSeq, mode, onDone)
                } else {
                    bgImageView.visibility = View.GONE
                    onDone()
                }
            }
        }
    }

    /**
     * Called every drag frame with progress 0→1.
     * REALTIME:          drives C++ blur radius.
     * SNAPSHOT + API31+: drives RenderEffect blur radius (progressive animation).
     * BLURVIEW + any,
     * SNAPSHOT + pre31:  drives ImageView alpha (pre-baked blur fades in).
     */
    fun updateProgress(context: Context, bgImageView: ImageView, progress: Float) {
        when (val mode = getMode(context)) {
            Mode.DISABLED -> return
            Mode.REALTIME -> {
                val r = getRadius(context) * progress
                Log.d("CloX/Blur", "updateProgress REALTIME progress=$progress radius=$r")
                CocosManager.nativeSetBlurRadius(r)
            }
            Mode.BLURVIEW -> {
                if (bgImageView.drawable == null) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // API31+: animate blur radius via RenderEffect.
                    val r = getRadius(context) * progress
                    bgImageView.alpha = 1f
                    bgImageView.visibility = View.VISIBLE
                    bgImageView.setRenderEffect(
                        if (r >= 1f) android.graphics.RenderEffect.createBlurEffect(
                            r, r, android.graphics.Shader.TileMode.CLAMP)
                        else null
                    )
                } else {
                    // pre-31: blur pre-baked by RenderScript, animate alpha.
                    bgImageView.alpha = progress
                    if (progress > 0f) bgImageView.visibility = View.VISIBLE
                }
            }
            Mode.SNAPSHOT -> {
                if (bgImageView.drawable == null) return
                // Software blur pre-baked; only alpha animates.
                bgImageView.alpha = progress
                if (progress > 0f) bgImageView.visibility = View.VISIBLE
            }
        }
    }

    fun clearBlur(window: Window, bgImageView: ImageView) {
        Log.d("CloX/Blur", "clearBlur (radius->0, captureSeq ${captureSeq}->${captureSeq + 1})")
        captureSeq++   // invalidate any in-flight PixelCopy
        CocosManager.nativeSetBlurRadius(0f)   // safe to call regardless of mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bgImageView.setRenderEffect(null)
        }
        bgImageView.setImageDrawable(null)
        bgImageView.visibility = View.GONE
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun captureAndBlur(
        context: Context,
        bgImageView: ImageView,
        seq: Int,
        mode: Mode,
        onDone: () -> Unit
    ) {
        val glView = CocosManager.getGlView()
        Log.d("BlurEngine", "captureAndBlur glView=$glView seq=$seq mode=$mode")
        if (glView == null) { onDone(); return }

        // Wait for next vsync so the GL surface has a committed frame ready.
        Choreographer.getInstance().postFrameCallback {
            if (seq != captureSeq) { return@postFrameCallback }
            if (glView.holder?.surface?.isValid != true) { onDone(); return@postFrameCallback }
            val dm = context.resources.displayMetrics
            val w  = dm.widthPixels
            val h  = dm.heightPixels
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            PixelCopy.request(glView, null, bmp, { result ->
                if (seq != captureSeq) { return@request }
                if (result == PixelCopy.SUCCESS) {
                    val radius = getRadius(context)
                    when (mode) {
                        Mode.BLURVIEW -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                // API31+: store raw bitmap; updateProgress animates RenderEffect radius.
                                bgImageView.setImageBitmap(bmp)
                                bgImageView.alpha = 1f
                            } else {
                                // pre-31: bake RenderScript blur now; alpha animates.
                                bgImageView.setImageBitmap(renderScriptBlur(context, bmp, radius))
                                bgImageView.alpha = 0f
                            }
                            bgImageView.visibility = View.VISIBLE
                        }
                        else -> {
                            // SNAPSHOT: always CPU stackBlur, all API levels.
                            val down = Bitmap.createScaledBitmap(bmp, w / DOWNSAMPLE, h / DOWNSAMPLE, false)
                            val blurred = stackBlur(down, (radius / DOWNSAMPLE).toInt().coerceAtLeast(2))
                            bgImageView.setImageBitmap(Bitmap.createScaledBitmap(blurred, w, h, true))
                            bgImageView.alpha = 0f
                            bgImageView.visibility = View.VISIBLE
                        }
                    }
                }
                onDone()
            }, Handler(Looper.getMainLooper()))
        }
    }

    // ── RenderScript blur for BLURVIEW pre-API31 ─────────────────────────────

    @Suppress("DEPRECATION")
    private fun renderScriptBlur(context: Context, src: Bitmap, radius: Float): Bitmap {
        val downW = src.width / DOWNSAMPLE
        val downH = src.height / DOWNSAMPLE
        val down = Bitmap.createScaledBitmap(src, downW, downH, true)
        val algo = RenderScriptBlur(context)
        val blurred = algo.blur(down, radius.coerceIn(0f, 25f))
        algo.destroy()
        return Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
    }

    // ── Software blur (SNAPSHOT, all API levels) ──────────────────────────────

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
