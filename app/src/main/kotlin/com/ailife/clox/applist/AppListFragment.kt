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
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.overlay.notifications.CloXNotificationListenerService
import com.ailife.clox.applist.backends.GridBackend
import com.ailife.clox.applist.backends.HoneycombBackend
import com.ailife.clox.applist.backends.ListBackend
import com.ailife.clox.applist.backends.PagesBackend
import com.ailife.clox.applist.backends.WaterfallBackend
import kotlinx.coroutines.launch

class AppListFragment : Fragment() {

    var onRequestClose: (() -> Unit)? = null
    var onAppLaunched: ((AppItem, Rect, android.view.View) -> Unit)? = null
    var onPagesBackendActive: ((Boolean) -> Unit)? = null
    var onSetPagesCanScrollRight: ((() -> Boolean)?) -> Unit = {}
    var onSetPagesDragActive: ((() -> Boolean)?) -> Unit = {}

    private val viewModel: AppListViewModel by viewModels()
    private var backend: AppListBackend? = null
    private lateinit var root: FrameLayout
    private var lastBoundItems: List<AppItem>? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            AppListPrefs.KEY_STYLE, AppListPrefs.KEY_SORT,
            AppListPrefs.KEY_NOTIF_BADGE, AppListPrefs.KEY_NOTIF_BADGE_COUNT -> swapBackend()
            AppListPrefs.KEY_BG_IMAGE_PATH, AppListPrefs.KEY_WALLPAPER_DIM -> applyBackground()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = inflater.inflate(R.layout.fragment_app_list, container, false) as FrameLayout
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppListPrefs.get(requireContext()).registerOnSharedPreferenceChangeListener(prefsListener)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        applyBackground()
        swapBackend()
        observeApps()
        observeNotifications()
    }

    override fun onDestroyView() {
        super.onDestroyView()
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

    private fun applySurfaceBackground() {
        val ta = requireContext().obtainStyledAttributes(
            intArrayOf(com.google.android.material.R.attr.colorSurface)
        )
        root.setBackgroundColor(ta.getColor(0, 0xFF1C1B1F.toInt()))
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
            // Prefer the icon ImageView for tighter bounds; fall back to the whole item view
            val boundsView = sourceView.findViewById<android.view.View>(R.id.appIcon) ?: sourceView
            val bounds = Rect().also { boundsView.getGlobalVisibleRect(it) }
            onAppLaunched?.invoke(item, bounds, boundsView)
            val intent = android.content.Intent().apply {
                setClassName(item.packageName, item.activityName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opts = ActivityOptions.makeScaleUpAnimation(
                sourceView, 0, 0, sourceView.width, sourceView.height
            ).toBundle()
            runCatching { ctx.startActivity(intent, opts) }.onFailure {
                ctx.packageManager.getLaunchIntentForPackage(item.packageName)
                    ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { ctx.startActivity(it, opts) }
            }
        }
        newBackend.onAppLongPress = { item, anchor ->
            AppContextMenu.show(ctx, anchor, item,
                onIconChanged = { viewModel.loadApps() },
                onHideApp = { viewModel.loadApps() }
            )
        }
        newBackend.onRequestClose = { onRequestClose?.invoke() }
        newBackend.onOrderChanged = { items -> viewModel.saveCustomOrder(items) }

        val contentView = newBackend.createContentView(ctx)
        newBackend.setDragEnabled(sort == AppListPrefs.SORT_CUSTOM)
        root.removeAllViews()
        root.addView(contentView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        backend = newBackend
        lastBoundItems = null   // force bindItems on the fresh backend
        val isPages = style == AppListPrefs.STYLE_PAGES
        val pagesBackend = if (isPages) newBackend as PagesBackend else null
        onPagesBackendActive?.invoke(isPages)
        onSetPagesCanScrollRight(pagesBackend?.canScrollRight)
        onSetPagesDragActive(pagesBackend?.let { b -> { b.isDragActive } })

        val current = viewModel.apps.value
        if (current.isNotEmpty()) {
            newBackend.bindItems(current)
            newBackend.refreshBadges(currentNotifCounts())
        }
    }

    private fun createBackend(ctx: Context, style: String): AppListBackend = when (style) {
        AppListPrefs.STYLE_GRID      -> GridBackend(ctx)
        AppListPrefs.STYLE_WATERFALL -> WaterfallBackend(ctx)
        AppListPrefs.STYLE_HONEYCOMB -> HoneycombBackend(ctx)
        AppListPrefs.STYLE_GRID2D    -> Grid2DBackend(ctx)
        AppListPrefs.STYLE_PAGES     -> PagesBackend(ctx)
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
}
