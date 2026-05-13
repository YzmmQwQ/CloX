package com.ailife.clox.overlay.quicksettings

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList  // used in setStrokeColor()
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.ailife.clox.R
import com.google.android.material.card.MaterialCardView

class QsTileView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : MaterialCardView(ctx, attrs) {

    private val icon: ImageView
    private val label: TextView
    private val badge: TextView
    private val detail: TextView

    var isActive: Boolean = false
    var onAction: (() -> Unit)? = null
    var onLongAction: (() -> Unit)? = null

    // ── Ring animation ────────────────────────────────────────────────────────

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var loadingProgress = 0f   // 0..1, position of arc along perimeter
    private var arcColor = Color.WHITE
    private val loadingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
    }

    var isPending: Boolean = false
        set(value) {
            field = value
            if (value) startRingAnim() else stopRingAnim()
        }

    companion object {
        private const val STROKE_DP = 3f
        private const val ACTIVE_ARC_FRACTION = 1f    // full ring when active
        private const val LOADING_ARC_FRACTION = 0.28f // travelling arc segment
    }

    init {
        LayoutInflater.from(ctx).inflate(R.layout.view_qs_tile, this, true)
        icon   = findViewById(R.id.tileIcon)
        label  = findViewById(R.id.tileLabel)
        badge  = findViewById(R.id.tileBadge)
        detail = findViewById(R.id.tileDetail)

        radius = 16f * ctx.resources.displayMetrics.density
        cardElevation = 0f
        useCompatPadding = false

        // Tile background: very light transparent — ring provides the color
        setCardBackgroundColor(Color.argb(20, 255, 255, 255))
        setInactiveStroke()

        setOnClickListener { if (!isPending) onAction?.invoke() }
        setOnLongClickListener { vibrateShort(); onLongAction?.invoke(); true }
    }

    fun configure(iconRes: Int, labelText: String) {
        icon.setImageResource(iconRes)
        label.text = labelText
        icon.setColorFilter(Color.WHITE)
    }

    fun setIconRes(iconRes: Int) {
        icon.setImageResource(iconRes)
        icon.setColorFilter(Color.WHITE)
    }

    fun setBadge(text: String?) {
        badge.text = text ?: ""
        badge.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setDetail(text: String?) {
        detail.text = text ?: ""
        detail.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // Called from StateFlow — clears pending and applies confirmed state
    fun applyState(active: Boolean, detailText: String? = null, badgeText: String? = null, ringColor: Int? = null) {
        isPending = false  // stops animation
        isActive = active
        setDetail(detailText)
        setBadge(badgeText)
        arcColor = ringColor ?: if (active) Color.parseColor("#379950") else Color.WHITE
        if (active) setActiveStroke(arcColor) else setInactiveStroke()
        icon.setColorFilter(Color.WHITE)
        label.setTextColor(Color.WHITE)
        detail.setTextColor(Color.argb(200, 255, 255, 255))
        badge.setTextColor(Color.argb(160, 255, 255, 255))
        invalidate()
    }

    // ── Stroke helpers ────────────────────────────────────────────────────────

    private fun setInactiveStroke() {
        val strokePx = (STROKE_DP * resources.displayMetrics.density).toInt()
        strokeWidth = strokePx
        setStrokeColor(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)))
    }

    private fun setActiveStroke(color: Int) {
        val strokePx = (STROKE_DP * resources.displayMetrics.density).toInt()
        strokeWidth = strokePx
        setStrokeColor(ColorStateList.valueOf(color))
    }

    // ── Loading ring animation ────────────────────────────────────────────────

    private fun startRingAnim() {
        arcColor = strokeColor  // strokeColor returns Int in this Material version
        setInactiveStroke()  // dim base ring during loading
        loadingAnimator.removeAllUpdateListeners()
        loadingAnimator.addUpdateListener { anim ->
            loadingProgress = anim.animatedValue as Float
            postInvalidateOnAnimation()
        }
        loadingAnimator.start()
    }

    private fun stopRingAnim() {
        loadingAnimator.removeAllUpdateListeners()
        loadingAnimator.cancel()
        loadingProgress = 0f
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!isPending || !loadingAnimator.isRunning) return

        val density = resources.displayMetrics.density
        val strokePx = STROKE_DP * density
        arcPaint.strokeWidth = strokePx * 2.2f   // slightly thicker arc overlay
        arcPaint.color = arcColor.takeIf { it != 0 } ?: Color.WHITE

        val inset = strokePx / 2f
        val cornerR = (radius - inset).coerceAtLeast(0f)
        val rect = RectF(inset, inset, width - inset, height - inset)

        // Build rounded-rect path and extract a travelling arc segment
        val perimPath = Path()
        perimPath.addRoundRect(rect, cornerR, cornerR, Path.Direction.CW)
        val pm = PathMeasure(perimPath, false)
        val total = pm.length
        if (total <= 0f) return
        val segLen = total * LOADING_ARC_FRACTION
        val start = loadingProgress * total

        val segPath = Path()
        val end = start + segLen
        if (end <= total) {
            pm.getSegment(start, end, segPath, true)
        } else {
            // Wrap around perimeter
            pm.getSegment(start, total, segPath, true)
            val wrapPath = Path()
            pm.getSegment(0f, end - total, wrapPath, true)
            segPath.addPath(wrapPath)
        }
        canvas.drawPath(segPath, arcPaint)
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort() {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            else -> v.vibrate(50)
        }
    }
}
