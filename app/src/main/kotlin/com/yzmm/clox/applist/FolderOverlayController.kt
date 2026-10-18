package com.yzmm.clox.applist

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.yzmm.clox.R
import com.yzmm.clox.applist.model.AppItem

/**
 * Full-screen folder overlay: a centred square card holding the folder's apps in a 3-column grid.
 * Tapping a folder icon zoom-animates the card out from the icon rect; the background is blurred
 * when the app-list "launch blur" pref is on (via [Blur.add] returning a BlurView), otherwise a
 * plain darkening scrim is used. Hosted in [host] (MainActivity's gestureRoot), above the app list.
 */
class FolderOverlayController(
    private val host: FrameLayout,
    private val screenSize: () -> Pair<Int, Int>,
    private val blur: Blur
) {
    /** Bridge to MainActivity's launch-blur helpers. [add] returns null when launch blur is off. */
    interface Blur {
        fun add(): View?
        fun set(view: View?, fraction: Float)
        fun remove(view: View?)
    }

    private val ctx: Context = host.context
    private val density = ctx.resources.displayMetrics.density

    private var container: FrameLayout? = null
    private var card: View? = null
    private var dim: View? = null
    private var blurView: View? = null
    private var anim: ValueAnimator? = null
    private var adapter: AppListAdapter? = null

    // Geometry captured at open time so the reverse animation matches the forward one.
    private var iconBounds = Rect()
    private var finalSize = 0
    private var startScale = 1f
    private var startDx = 0f
    private var startDy = 0f

    val isOpen: Boolean get() = container != null

    // Latest applied frame fraction (1 = fully open, 0 = collapsed into the icon). Tracked so the
    // predictive-back drag and the commit/cancel springs continue from wherever the finger left off.
    private var currentFrame = 1f

    fun open(
        name: String,
        members: List<AppItem>,
        bounds: Rect,
        onMember: (AppItem, View) -> Unit,
        onMemberLongPress: (AppItem, View) -> Unit,
        onReorder: (List<AppItem>) -> Unit
    ) {
        if (isOpen) return
        val (sw, sh) = screenSize()
        iconBounds = Rect(bounds)
        val marginPx = (16 * density)
        finalSize = (minOf(sw, sh) - 2 * marginPx).toInt().coerceAtLeast(1)

        val cont = FrameLayout(ctx).apply {
            isClickable = true
            setOnClickListener { close() }
        }
        container = cont

        // Background: blur if available, otherwise a darkening scrim.
        val bv = blur.add()
        blurView = bv
        if (bv == null) {
            dim = View(ctx).apply {
                setBackgroundColor(Color.BLACK)
                alpha = 0f
            }
            cont.addView(dim, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val cardView = buildCard(name, members, onMember, onMemberLongPress, onReorder)
        card = cardView
        cont.addView(cardView, FrameLayout.LayoutParams(finalSize, finalSize, Gravity.CENTER))

        host.addView(cont, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Compute zoom-from-icon geometry.
        val finalCx = sw / 2f
        val finalCy = sh / 2f
        startScale = (iconBounds.width().coerceAtLeast(1)) / finalSize.toFloat()
        startDx = iconBounds.exactCenterX() - finalCx
        startDy = iconBounds.exactCenterY() - finalCy
        cardView.pivotX = finalSize / 2f
        cardView.pivotY = finalSize / 2f
        applyFrame(0f)

        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyFrame(it.animatedValue as Float) }
            start()
        }
    }

    fun close() {
        val cont = container ?: return
        anim?.cancel()
        val from = currentFrame
        anim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyFrame(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    host.removeView(cont)
                    blurView?.let { blur.remove(it) }
                    container = null; card = null; dim = null; blurView = null
                    currentFrame = 1f
                }
            })
            start()
        }
    }

    // ── Predictive back ──────────────────────────────────────────────────────
    // The system back gesture drives a live preview: as the finger drags, the folder shrinks toward
    // its origin icon (previewing the close) without fully collapsing; commit finishes the close,
    // cancel springs it back open. Progress 0..1 maps to a partial collapse so a cancel can recover.

    fun backStarted() {
        if (!isOpen) return
        anim?.cancel()
    }

    /** [p] 0..1 = how far the back gesture has been dragged. */
    fun backProgress(p: Float) {
        if (!isOpen) return
        anim?.cancel()
        // Preview only part-way (down to 0.35) so releasing mid-drag can spring back open cleanly.
        val f = 1f - p.coerceIn(0f, 1f) * BACK_PREVIEW_DEPTH
        applyFrame(f)
    }

    fun backCancelled() {
        if (!isOpen) return
        anim?.cancel()
        anim = ValueAnimator.ofFloat(currentFrame, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyFrame(it.animatedValue as Float) }
            start()
        }
    }

    private fun applyFrame(f: Float) {
        currentFrame = f
        val c = card ?: return
        val s = startScale + (1f - startScale) * f
        c.scaleX = s
        c.scaleY = s
        c.translationX = startDx * (1f - f)
        c.translationY = startDy * (1f - f)
        c.alpha = (f * 2f).coerceAtMost(1f)
        if (blurView != null) blur.set(blurView, f) else dim?.alpha = f * DIM_MAX
    }

    /** Rebinds the folder's members after an edit (e.g. remove-from-folder); closes if now empty. */
    fun refresh(members: List<AppItem>) {
        if (!isOpen) return
        if (members.isEmpty()) close() else adapter?.submitList(members)
    }

    private fun buildCard(
        name: String,
        members: List<AppItem>,
        onMember: (AppItem, View) -> Unit,
        onMemberLongPress: (AppItem, View) -> Unit,
        onReorder: (List<AppItem>) -> Unit
    ): View {
        val pad = (12 * density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            isClickable = true    // consume taps so they don't close the folder
            background = GradientDrawable().apply {
                cornerRadius = finalSize * 0.12f
                setColor(cardColor())
            }
            clipToOutline = true
        }

        val title = TextView(ctx).apply {
            text = name
            setTextColor(onSurfaceColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val rv = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, GRID_COLS)
            clipToPadding = false
        }
        val adp = AppListAdapter(R.layout.item_app_grid) { view, item ->
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
            view.findViewById<TextView>(R.id.appLabel).apply {
                text = item.label
                visibility = View.VISIBLE
            }
        }.also { a ->
            // Launch the app but keep the folder open: the app's open animation plays from the
            // member's mini icon, and on return it morphs back into that same still-present icon.
            a.onAppClick = { item, v -> onMember(item, v) }
            a.onAppLongPress = { item, v -> onMemberLongPress(item, v) }
            // Long-press then drag reorders members (same infra as the main app list): a stationary
            // long-press shows the context menu, but moving past the drag threshold dismisses it and
            // starts an ItemTouchHelper drag. New order is persisted on drop via onReorder.
            a.dragEnabled = true
            a.onOrderChanged = { onReorder(it) }
        }
        rv.adapter = adp
        adapter = adp
        val touchHelper = ItemTouchHelper(AppListTouchCallback(adp))
        touchHelper.attachToRecyclerView(rv)
        adp.touchHelper = touchHelper
        adp.submitList(members)
        root.addView(rv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun cardColor(): Int = resolveAttr(
        com.google.android.material.R.attr.colorSurface, Color.rgb(30, 30, 30))

    private fun onSurfaceColor(): Int = resolveAttr(
        com.google.android.material.R.attr.colorOnSurface, Color.WHITE)

    private fun resolveAttr(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true) && tv.data != 0) tv.data else fallback
    }

    companion object {
        private const val GRID_COLS = 3
        private const val DIM_MAX = 0.6f
        // How far a full predictive-back drag collapses the folder preview (0 = none, 1 = fully closed).
        private const val BACK_PREVIEW_DEPTH = 0.65f
    }
}
