package com.ailife.clox.applist

import android.app.ActivityOptions
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ailife.clox.R
import android.graphics.Rect
import com.ailife.clox.applist.backends.Grid2DBackend
import com.ailife.clox.rtos.RtosAppGuard
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService
import com.ailife.clox.applist.backends.GravityBackend
import com.ailife.clox.applist.backends.GridBackend
import com.ailife.clox.applist.backends.HoneycombBackend
import com.ailife.clox.applist.backends.ListBackend
import com.ailife.clox.applist.backends.PagesBackend
import com.ailife.clox.applist.backends.WaterfallBackend
import kotlinx.coroutines.launch

class AppListFragment : Fragment() {

    var onRequestClose: (() -> Unit)? = null

    fun refreshApps() { if (isAdded) viewModel.loadApps(force = true) }
    var onAppLaunched: ((AppItem, Rect, android.view.View, () -> Unit) -> Unit)? = null
    var onPagesBackendActive: ((Boolean) -> Unit)? = null
    var onSetPagesCanScrollRight: ((() -> Boolean)?) -> Unit = {}
    var onSetPagesDragActive: ((() -> Boolean)?) -> Unit = {}
    var onSetGrid2dHoneycombActive: ((Boolean) -> Unit)? = null

    private val viewModel: AppListViewModel by viewModels()
    private var backend: AppListBackend? = null
    // Whether the drawer is the open page (set by the host). Some backends (Gravity) pause work
    // when not active. Re-applied to each freshly-built backend in swapBackend().
    private var appListActive = false

    /** Called by the host when the app list opens/closes so backends can pause/resume. */
    fun setActive(active: Boolean) {
        appListActive = active
        backend?.setActive(active)
    }
    private lateinit var root: FrameLayout
    private var lastBoundItems: List<AppItem>? = null

    private val rebuildHandler = Handler(Looper.getMainLooper())
    private val rebuildRunnable = Runnable { if (isAdded) swapBackend() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        Log.d("CloX/AppList", "prefChanged key=$key")
        when (key) {
            // Backend-config keys can change rapidly: the icon-size and min-scale settings are
            // sliders whose onChange fires on every step, so reacting immediately would rebuild
            // the whole app-list backend dozens of times per drag — allocating fresh view trees,
            // scroll listeners and scaled-icon bitmaps each time. That churn degrades the
            // watchface GL compositing (open/close jank) and persists until restart. Debounce so
            // a drag produces a single rebuild after the user settles.
            in BACKEND_KEYS -> {
                rebuildHandler.removeCallbacks(rebuildRunnable)
                rebuildHandler.postDelayed(rebuildRunnable, REBUILD_DEBOUNCE_MS)
            }
            AppListPrefs.KEY_BG_IMAGE_PATH, AppListPrefs.KEY_WALLPAPER_DIM -> applyBackground()
            AppListPrefs.KEY_ICON_PACK -> viewModel.loadApps(force = true)
            AppListPrefs.KEY_HIDDEN_COMPONENTS, AppListPrefs.KEY_HIDE_MODE -> viewModel.loadApps()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = inflater.inflate(R.layout.fragment_app_list, container, false) as FrameLayout
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        NotificationBadge.resolveColors(requireContext())
        AppListPrefs.get(requireContext()).registerOnSharedPreferenceChangeListener(prefsListener)
        root.clipChildren = false
        root.clipToPadding = false
        applyBackground()
        swapBackend()
        observeApps()
        observeNotifications()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rebuildHandler.removeCallbacks(rebuildRunnable)
        AppListPrefs.get(requireContext()).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ── Background ───────────────────────────────────────────────────────────

    private fun applyBackground() {
        val ctx = requireContext()
        val path = AppListPrefs.bgImagePath(ctx)
        if (path == null) { applySurfaceBackground(); return }

        Thread {
            val bitmap = runCatching {
                val src = BitmapFactory.decodeFile(path) ?: return@runCatching null
                val vw = resources.displayMetrics.widthPixels
                val vh = resources.displayMetrics.heightPixels
                // Center-crop: scale so the image covers the view, then crop the overflow
                val scale = maxOf(vw.toFloat() / src.width, vh.toFloat() / src.height)
                val sw = (src.width * scale).toInt()
                val sh = (src.height * scale).toInt()
                val scaled = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true)
                val ox = (sw - vw) / 2
                val oy = (sh - vh) / 2
                android.graphics.Bitmap.createBitmap(scaled, ox, oy, vw, vh)
            }.getOrNull()

            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post
                if (bitmap == null) { applySurfaceBackground(); return@post }

                val dim = AppListPrefs.wallpaperDim(ctx)
                val dimAlpha = (dim / 100f * 255).toInt().coerceIn(0, 255)
                val wallLayer = BitmapDrawable(resources, bitmap)
                root.background = if (dimAlpha > 0)
                    LayerDrawable(arrayOf(wallLayer, ColorDrawable(Color.argb(dimAlpha, 0, 0, 0))))
                else wallLayer
            }
        }.start()
    }

    // ── Return-animation icon lookup ─────────────────────────────────────────

    /** A located icon for the app that is currently closing back to the launcher. */
    class ReturnIconHit(val item: AppItem, val iconView: View?, val bounds: Rect?)

    /**
     * Finds the app-list entry for [pkg] so the launcher can animate the *actual*
     * closing app's icon (which may differ from the last app we launched). Returns the
     * matching [AppItem] when it exists in the list, plus the on-screen icon view and
     * bounds when that row is currently visible (null bounds → row not on screen).
     */
    fun returnIconHit(pkg: String): ReturnIconHit? {
        val item = lastBoundItems?.firstOrNull { it.packageName == pkg } ?: return null
        val row = if (::root.isInitialized) findVisibleRow(root, pkg) else null
        val iconView = row?.findViewById<View>(R.id.appIcon) ?: row
        // Use getLocationOnScreen (layout position) rather than getGlobalVisibleRect — the latter
        // returns false while the launcher window is still occluded by the closing app.
        // getLocationOnScreen returns the scaled top-left, but width/height are the unscaled
        // layout size, so multiply by the cumulative scale up the hierarchy (the 3D effect scales
        // rows/cells) to get the icon's true on-screen rect — and thus its real visual centre.
        val bounds = iconView?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }?.let { v ->
            var sx = 1f; var sy = 1f
            var p: View? = v
            while (p != null) { sx *= p.scaleX; sy *= p.scaleY; p = p.parent as? View }
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + (v.width * sx).toInt(), loc[1] + (v.height * sy).toInt())
        }
        Log.d("CloX/Return", "returnIconHit pkg=$pkg rowFound=${row != null} bounds=$bounds")
        return ReturnIconHit(item, iconView.takeIf { bounds != null }, bounds)
    }

    private fun findVisibleRow(v: View, pkg: String): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.getTag(R.id.app_item_package) == pkg) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findVisibleRow(v.getChildAt(i), pkg)?.let { return it }
            }
        }
        return null
    }

    private fun applySurfaceBackground() {
        val ta = requireContext().obtainStyledAttributes(
            intArrayOf(com.google.android.material.R.attr.colorSurface)
        )
        root.setBackgroundColor(ta.getColor(0, requireContext().getColor(R.color.fallback_surface)))
        ta.recycle()
    }

    // ── Backend management ────────────────────────────────────────────────────

    private fun swapBackend() {
        val ctx = requireContext()
        val style = AppListPrefs.style(ctx)
        val sort  = AppListPrefs.sort(ctx)
        Log.d("CloX/AppList", "swapBackend style=$style sort=$sort")

        val newBackend = createBackend(ctx, style)
        newBackend.onAppClick = { item, sourceView ->
            if (RtosAppGuard.allowLaunch(ctx, item)) {
            // Prefer the icon ImageView for tighter bounds; fall back to the whole item view
            val boundsView = sourceView.findViewById<android.view.View>(R.id.appIcon) ?: sourceView
            val bounds = Rect().also { boundsView.getGlobalVisibleRect(it) }
            val intent = android.content.Intent().apply {
                setClassName(item.packageName, item.activityName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val useCustomAnim = AppListPrefs.useCustomLaunchAnim(ctx)
            val doLaunch: () -> Unit = if (useCustomAnim) {
                {
                    runCatching {
                        ctx.startActivity(intent)
                    }.onFailure {
                        ctx.packageManager.getLaunchIntentForPackage(item.packageName)
                            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let { ctx.startActivity(it) }
                    }
                }
            } else {
                {
                    val opts = ActivityOptions.makeScaleUpAnimation(
                        boundsView, 0, 0, boundsView.width, boundsView.height
                    ).toBundle()
                    runCatching {
                        ctx.startActivity(intent, opts)
                    }.onFailure {
                        ctx.packageManager.getLaunchIntentForPackage(item.packageName)
                            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let { ctx.startActivity(it, opts) }
                    }
                }
            }
            onAppLaunched?.invoke(item, bounds, boundsView, doLaunch) ?: doLaunch()
            } // end if (!rtosBlocked)
        }
        newBackend.onAppLongPress = if (!RtosAppGuard.isLongPressAllowed) null else
            { item, anchor ->
                AppContextMenu.show(ctx, anchor, item,
                    onIconChanged = { viewModel.loadApps(force = true) },
                    onHideApp = { viewModel.loadApps() }
                )
            }
        newBackend.onRequestClose = { onRequestClose?.invoke() }
        newBackend.onOrderChanged = { items -> viewModel.saveCustomOrder(items) }

        val contentView = newBackend.createContentView(ctx)
        newBackend.setDragEnabled(sort == AppListPrefs.SORT_CUSTOM)
        newBackend.setActive(appListActive)
        root.removeAllViews()
        root.addView(contentView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        backend = newBackend
        lastBoundItems = null   // force bindItems on the fresh backend
        val isPages = style == AppListPrefs.STYLE_PAGES
        val isGrid2dOrHoneycomb = style == AppListPrefs.STYLE_GRID2D || style == AppListPrefs.STYLE_HONEYCOMB
        val pagesBackend = if (isPages) newBackend as PagesBackend else null
        onPagesBackendActive?.invoke(isPages)
        onSetGrid2dHoneycombActive?.invoke(isGrid2dOrHoneycomb)
        onSetPagesCanScrollRight(pagesBackend?.canScrollRight)
        onSetPagesDragActive(pagesBackend?.let { b -> { b.isDragActive } })

        val current = viewModel.apps.value
        if (current.isNotEmpty()) {
            newBackend.bindItems(current)
            newBackend.refreshBadges(currentNotifCounts())
            // Record what we just bound so observeApps doesn't redundantly re-bind the whole
            // list (same reference) on its next emission.
            lastBoundItems = current
        }
    }

    private fun createBackend(ctx: Context, style: String): AppListBackend = when (style) {
        AppListPrefs.STYLE_GRID      -> GridBackend(ctx)
        AppListPrefs.STYLE_WATERFALL -> WaterfallBackend(ctx)
        AppListPrefs.STYLE_HONEYCOMB -> HoneycombBackend(ctx)
        AppListPrefs.STYLE_GRID2D    -> Grid2DBackend(ctx)
        AppListPrefs.STYLE_PAGES     -> PagesBackend(ctx)
        AppListPrefs.STYLE_GRAVITY   -> GravityBackend(ctx)
        else                         -> ListBackend(ctx)
    }

    private fun observeApps() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.apps.collect { items ->
                    // StateFlow re-emits its current value every time collection restarts
                    // (e.g. returning from an external app). Skip bindItems if the list
                    // reference hasn't changed — same object means nothing was reloaded,
                    // so the adapter is already up-to-date and scroll position is preserved.
                    if (items !== lastBoundItems) {
                        lastBoundItems = items
                        backend?.bindItems(items)
                    }
                    backend?.refreshBadges(currentNotifCounts())
                }
            }
        }
    }

    private fun observeNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CloXNotificationListenerService.notifications.collect {
                    backend?.refreshBadges(currentNotifCounts())
                }
            }
        }
    }

    private fun currentNotifCounts(): Map<String, Int> =
        CloXNotificationListenerService.currentNotifications
            .groupBy { it.packageName }
            .mapValues { it.value.size }

    companion object {
        /** Coalesce rapid slider-driven config changes into a single backend rebuild. */
        private const val REBUILD_DEBOUNCE_MS = 300L

        /** Pref keys that require the backend to be rebuilt when changed. */
        private val BACKEND_KEYS = setOf(
            AppListPrefs.KEY_STYLE, AppListPrefs.KEY_SORT,
            AppListPrefs.KEY_NOTIF_BADGE, AppListPrefs.KEY_NOTIF_BADGE_COUNT,
            AppListPrefs.KEY_LIST_SEARCH,
            AppListPrefs.KEY_LIST_3D, AppListPrefs.KEY_LIST_3D_MIN_SCALE, AppListPrefs.KEY_LIST_3D_CURVE,
            AppListPrefs.KEY_GRID_COLS, AppListPrefs.KEY_GRID_NAMES,
            AppListPrefs.KEY_WATERFALL_3D, AppListPrefs.KEY_WATERFALL_3D_MIN_SCALE, AppListPrefs.KEY_WATERFALL_3D_CURVE,
            AppListPrefs.KEY_HONEYCOMB_3D, AppListPrefs.KEY_HONEYCOMB_3D_MIN_SCALE, AppListPrefs.KEY_HONEYCOMB_ICON_SIZE, AppListPrefs.KEY_HONEYCOMB_3D_CURVE, AppListPrefs.KEY_HONEYCOMB_ROUND_OVERSCROLL,
            AppListPrefs.KEY_GRID2D_COLS, AppListPrefs.KEY_GRID2D_NAMES, AppListPrefs.KEY_GRID2D_3D, AppListPrefs.KEY_GRID2D_3D_MIN_SCALE, AppListPrefs.KEY_GRID2D_ICON_SIZE, AppListPrefs.KEY_GRID2D_3D_CURVE, AppListPrefs.KEY_GRID2D_ROUND_OVERSCROLL,
            AppListPrefs.KEY_PAGES_COLS, AppListPrefs.KEY_PAGES_ROWS,
            AppListPrefs.KEY_GRAVITY_ACCEL, AppListPrefs.KEY_GRAVITY_STRENGTH,
            AppListPrefs.KEY_GRAVITY_BOUNCE, AppListPrefs.KEY_GRAVITY_FRICTION,
            AppListPrefs.KEY_OVERSCROLL,
        )
    }
}
