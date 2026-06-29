package com.ailife.clox.applist

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.ailife.clox.applist.model.AppItem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Physics "gravity" app-list style.
 *
 * Icons are circular bodies that bounce around inside the screen. The page's bodies follow the
 * accelerometer (tilt the watch to pour the icons around) and collide with each other and the
 * walls. Auto-picks icon size and per-page capacity from the screen — no labels, no grid.
 *
 * Gestures:
 *   - swipe in empty area   → change page (right-swipe past first page fires onRequestClose)
 *   - swipe/fling on an icon → throw that icon around (velocity carries into the simulation)
 *   - tap an icon            → launch
 *   - long-press an icon     → context menu
 * Reordering is intentionally unavailable in this mode (positions are physical, not a list).
 */
class GravityView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs), SensorEventListener {

    var onAppClick: ((AppItem, View) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var onRequestClose: (() -> Unit)? = null

    var followAccel = true
    var gravityStrength = 10   // 0-20
    var bounce = 5             // 0-10 (restitution)
    var friction = 3          // 0-10 (per-second velocity damping)
    var showBadge = false
    var showCount = true

    // ── Items / paging ─────────────────────────────────────────────────────────
    private var allItems: List<AppItem> = emptyList()
    private var page = 0
    private var perPage = 1
    private var iconPx = 1
    private var radius = 0.5f

    // ── Bodies (current page only) ──────────────────────────────────────────────
    private class Body(val item: AppItem, val view: ImageView, val badge: TextView) {
        var x = 0f; var y = 0f      // center position, px
        var vx = 0f; var vy = 0f    // velocity, px/s
        var grabbed = false
    }
    private val bodies = mutableListOf<Body>()
    private var lastBadgeCounts: Map<String, Int> = emptyMap()

    // ── Accelerometer ───────────────────────────────────────────────────────────
    private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var accelX = 0f   // screen-space gravity direction (px-independent, m/s²-ish)
    private var accelY = 9.8f
    private var sensorRegistered = false

    // ── Touch ───────────────────────────────────────────────────────────────────
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var velTracker: VelocityTracker? = null
    private var downX = 0f; private var downY = 0f
    private var lastTouchX = 0f; private var lastTouchY = 0f
    private var grabbed: Body? = null
    private var pageDragging = false
    private var pendingItems: List<AppItem>? = null
    private var moved = false

    private val longPressRunnable = Runnable {
        val b = grabbed ?: return@Runnable
        if (!moved) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // Release the body back to the simulation and consume this touch sequence so the
            // subsequent ACTION_UP doesn't also launch the app.
            b.grabbed = false
            (b.view.parent as? View)?.elevation = 0f
            grabbed = null
            onAppLongPress?.invoke(b.item, b.view)
        }
    }

    init {
        clipChildren = true; clipToPadding = false; isMotionEventSplittingEnabled = false
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    fun bindItems(items: List<AppItem>) {
        allItems = items
        page = page.coerceIn(0, maxPage())
        android.util.Log.d("CloX/Gravity", "bindItems n=${items.size} w=$width h=$height")
        if (width > 0 && height > 0) rebuildPage() else pendingItems = items
    }

    fun refreshBadges(counts: Map<String, Int>) {
        lastBadgeCounts = counts
        for (b in bodies) {
            NotificationBadge.bind(b.badge, b.item.packageName, counts, showBadge, showCount)
        }
    }

    private fun maxPage(): Int {
        if (perPage <= 0 || allItems.isEmpty()) return 0
        return (ceil(allItems.size / perPage.toFloat()).toInt() - 1).coerceAtLeast(0)
    }

    // ── Layout / page build ─────────────────────────────────────────────────────

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        android.util.Log.d("CloX/Gravity", "onLayout changed=$changed w=$width h=$height pending=${pendingItems?.size} bodies=${bodies.size} all=${allItems.size}")
        if (changed || (pendingItems != null && bodies.isEmpty())) {
            computeMetrics()
            pendingItems?.let { allItems = it; pendingItems = null }
            if (allItems.isNotEmpty()) rebuildPage()
        }
    }

    /** Auto-pick icon size and how many icons comfortably fit per page. */
    private fun computeMetrics() {
        if (width <= 0 || height <= 0) return
        // Target ~4.2 icons across the smaller dimension.
        iconPx = (min(width, height) / 4.2f).toInt().coerceAtLeast(dp(36))
        radius = iconPx / 2f
        // Packing: circle area with breathing room (0.55 fill factor) → comfortable capacity.
        val cap = ((width.toFloat() * height) * 0.55f / (Math.PI.toFloat() * radius * radius)).toInt()
        perPage = cap.coerceIn(4, 24)
        page = page.coerceIn(0, maxPage())
    }

    private fun rebuildPage() {
        removeAllViews()
        bodies.clear()
        if (iconPx <= 1) computeMetrics()
        val start = page * perPage
        val end = min(start + perPage, allItems.size)
        android.util.Log.d("CloX/Gravity", "rebuildPage page=$page perPage=$perPage iconPx=$iconPx start=$start end=$end all=${allItems.size}")
        if (start >= end) return

        val cols = ceil(sqrt(perPage.toFloat())).toInt().coerceAtLeast(1)
        var i = 0
        for (idx in start until end) {
            val item = allItems[idx]
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                id = com.ailife.clox.R.id.appIcon
                setImageDrawable(
                    CircularIconCache.obtain(item.icon, iconPx, "${item.packageName}/${item.activityName}")
                )
                isLongClickable = false; isClickable = false
            }
            val badge = NotificationBadge.makeView(context)
            val cell = FrameLayout(context).apply {
                setTag(com.ailife.clox.R.id.app_item_package, item.packageName)
                isLongClickable = false; isClickable = false
            }
            cell.addView(iv, LayoutParams(iconPx, iconPx))
            cell.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
            })
            val body = Body(item, iv, badge)
            // Spawn on a loose grid; physics scatters them immediately.
            val col = i % cols; val rowN = i / cols
            val spacing = iconPx * 1.15f
            val gridW = cols * spacing
            val ox = (width - gridW) / 2f + radius
            body.x = (ox + col * spacing).coerceIn(radius, width - radius)
            body.y = (radius + iconPx * 0.6f + rowN * spacing).coerceIn(radius, height - radius)
            addView(cell, LayoutParams(iconPx, iconPx))
            positionCell(body)
            bodies.add(body)
            i++
        }
        refreshBadges(lastBadgeCounts)
        updateRunState()
    }

    private fun positionCell(b: Body) {
        val cell = b.view.parent as? View ?: return
        cell.x = b.x - radius
        cell.y = b.y - radius
    }

    // ── Simulation loop ──────────────────────────────────────────────────────────

    private var running = false
    private var lastFrameNanos = 0L

    private fun ensureRunning() {
        if (!running) {
            running = true
            lastFrameNanos = 0L
            postOnAnimation(frameRunnable)
        }
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) { running = false; return }
            val now = System.nanoTime()
            val dt = if (lastFrameNanos == 0L) 0.016f
                     else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = now
            step(dt)
            if (running) postOnAnimation(this)
        }
    }

    private fun step(dt: Float) {
        if (bodies.isEmpty()) { running = false; return }
        // Scale m/s² gravity into px/s² — tie to icon size so motion feels consistent across screens.
        val gScale = (gravityStrength / 10f) * iconPx * 7f
        val gx: Float; val gy: Float
        if (followAccel) { gx = accelX; gy = accelY } else { gx = 0f; gy = 9.8f }
        val ax = gx * gScale
        val ay = gy * gScale
        // Friction → exponential per-second velocity decay.
        val damp = Math.pow((1f - friction / 12f).toDouble(), dt.toDouble()).toFloat()
        val restitution = (bounce / 10f).coerceIn(0f, 0.98f)

        for (b in bodies) {
            if (b.grabbed) continue
            b.vx = (b.vx + ax * dt) * damp
            b.vy = (b.vy + ay * dt) * damp
            b.x += b.vx * dt
            b.y += b.vy * dt
            // Walls
            if (b.x < radius)         { b.x = radius;          b.vx = -b.vx * restitution }
            if (b.x > width - radius) { b.x = width - radius;  b.vx = -b.vx * restitution }
            if (b.y < radius)         { b.y = radius;          b.vy = -b.vy * restitution }
            if (b.y > height - radius){ b.y = height - radius; b.vy = -b.vy * restitution }
        }
        resolveCollisions(restitution)
        for (b in bodies) positionCell(b)
    }

    private fun resolveCollisions(restitution: Float) {
        val minDist = iconPx.toFloat()
        for (i in bodies.indices) {
            val a = bodies[i]
            for (j in i + 1 until bodies.size) {
                val c = bodies[j]
                var dx = c.x - a.x; var dy = c.y - a.y
                var dist = sqrt(dx * dx + dy * dy)
                if (dist <= 0f) { dx = 0.01f; dy = 0f; dist = 0.01f }
                if (dist < minDist) {
                    val overlap = minDist - dist
                    val nx = dx / dist; val ny = dy / dist
                    // Separate (grabbed bodies don't move; push the other the full overlap).
                    when {
                        a.grabbed && c.grabbed -> {}
                        a.grabbed -> { c.x += nx * overlap; c.y += ny * overlap }
                        c.grabbed -> { a.x -= nx * overlap; a.y -= ny * overlap }
                        else -> {
                            a.x -= nx * overlap / 2f; a.y -= ny * overlap / 2f
                            c.x += nx * overlap / 2f; c.y += ny * overlap / 2f
                        }
                    }
                    // Exchange velocity along the collision normal (equal mass).
                    val rvx = c.vx - a.vx; val rvy = c.vy - a.vy
                    val velN = rvx * nx + rvy * ny
                    if (velN < 0) {
                        val imp = -(1f + restitution) * velN / 2f
                        if (!a.grabbed) { a.vx -= imp * nx; a.vy -= imp * ny }
                        if (!c.grabbed) { c.vx += imp * nx; c.vy += imp * ny }
                    }
                }
            }
        }
    }

    // ── Touch handling ───────────────────────────────────────────────────────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                AppContextMenu.dismissCurrent()
                downX = ev.x; downY = ev.y; lastTouchX = ev.x; lastTouchY = ev.y
                moved = false; pageDragging = false
                velTracker?.recycle(); velTracker = VelocityTracker.obtain()
                velTracker?.addMovement(ev)
                grabbed = hitBody(ev.x, ev.y)?.also {
                    it.grabbed = true; it.vx = 0f; it.vy = 0f
                    it.view.parent?.let { p -> (p as View).elevation = dp(8).toFloat() }
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velTracker?.addMovement(ev)
                val dx = ev.x - downX; val dy = ev.y - downY
                if (!moved && dx * dx + dy * dy > touchSlop * touchSlop) {
                    moved = true
                    removeCallbacks(longPressRunnable)
                    if (grabbed == null) pageDragging = true
                }
                val g = grabbed
                if (g != null && moved) {
                    g.x = ev.x.coerceIn(radius, width - radius)
                    g.y = ev.y.coerceIn(radius, height - radius)
                    positionCell(g)
                }
                lastTouchX = ev.x; lastTouchY = ev.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                velTracker?.addMovement(ev)
                velTracker?.computeCurrentVelocity(1000)
                val vx = velTracker?.xVelocity ?: 0f
                val vy = velTracker?.yVelocity ?: 0f
                val g = grabbed
                if (g != null) {
                    g.view.parent?.let { p -> (p as View).elevation = 0f }
                    g.grabbed = false
                    if (!moved) {
                        // tap → launch
                        if (!AppContextMenu.isShowing) onAppClick?.invoke(g.item, g.view)
                    } else {
                        // fling → hand the gesture velocity to the body
                        g.vx = vx; g.vy = vy
                    }
                } else if (pageDragging) {
                    handlePageSwipe(ev.x - downX, vx)
                }
                grabbed = null; pageDragging = false
                velTracker?.recycle(); velTracker = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                grabbed?.let { it.grabbed = false; it.view.parent?.let { p -> (p as View).elevation = 0f } }
                grabbed = null; pageDragging = false
                velTracker?.recycle(); velTracker = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // We always handle our own touches.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }

    private fun hitBody(x: Float, y: Float): Body? {
        // Topmost (last added draws on top) first.
        for (i in bodies.indices.reversed()) {
            val b = bodies[i]
            val dx = x - b.x; val dy = y - b.y
            if (dx * dx + dy * dy <= radius * radius) return b
        }
        return null
    }

    private fun handlePageSwipe(totalDx: Float, vx: Float) {
        val threshold = width * 0.25f
        val flung = abs(vx) > 800f
        if (totalDx <= -threshold || (flung && vx < 0)) {
            if (page < maxPage()) { page++; rebuildPage() }
        } else if (totalDx >= threshold || (flung && vx > 0)) {
            if (page > 0) { page--; rebuildPage() }
            else onRequestClose?.invoke()
        }
    }

    // ── Sensor ───────────────────────────────────────────────────────────────────

    // The app list shares the launcher window with the watchface and is only slid off-screen
    // (still attached + window-visible) when closed, so window visibility alone can't tell us when
    // the drawer is actually shown. The host drives [active] via the backend so the simulation only
    // runs while the app list is the open page — never on the watchface or behind an open app.
    private var active = false
    private var windowVisible = false

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        updateRunState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowVisible = windowVisibility == VISIBLE
        updateRunState()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        windowVisible = false
        updateRunState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        updateRunState()
    }

    /** Run the frame loop + accelerometer only when on-screen AND the drawer is actually open. */
    private fun updateRunState() {
        if (active && windowVisible && isAttachedToWindow) {
            registerSensor()
            lastFrameNanos = 0L   // avoid a huge dt spike across the paused gap
            if (bodies.isNotEmpty()) ensureRunning()
        } else {
            running = false
            unregisterSensor()
        }
    }

    private fun registerSensor() {
        if (sensorRegistered || !followAccel) return
        val sm = sensorManager ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        sensorRegistered = true
    }

    private fun unregisterSensor() {
        if (!sensorRegistered) return
        sensorManager?.unregisterListener(this)
        sensorRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // Device accel: +x right, +y up, +z out of screen. Screen y is down → negate.
        // Low-pass for stability.
        val alpha = 0.75f
        accelX = alpha * accelX + (1 - alpha) * (-event.values[0])
        accelY = alpha * accelY + (1 - alpha) * (event.values[1])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
