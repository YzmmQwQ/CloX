package com.ailife.clox.applist

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
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
import com.ailife.clox.applist.backends.Grid2DBackend
import com.ailife.clox.applist.backends.GridBackend
import com.ailife.clox.applist.backends.HoneycombBackend
import com.ailife.clox.applist.backends.ListBackend
import com.ailife.clox.applist.backends.PagesBackend
import com.ailife.clox.applist.backends.WaterfallBackend
import kotlinx.coroutines.launch

class AppListFragment : Fragment() {

    /** Called by MainActivity to close the app list (used by Pages backend). */
    var onRequestClose: (() -> Unit)? = null

    /** Called when Pages mode activates/deactivates — MainActivity uses this to
     *  toggle GestureInterceptorLayout.pagesBackendActive. */
    var onPagesBackendActive: ((Boolean) -> Unit)? = null

    private val viewModel: AppListViewModel by viewModels()
    private var backend: AppListBackend? = null
    private lateinit var root: FrameLayout

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppListPrefs.KEY_STYLE || key == AppListPrefs.KEY_SORT) {
            swapBackend()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = inflater.inflate(R.layout.fragment_app_list, container, false) as FrameLayout
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppListPrefs.get(requireContext()).registerOnSharedPreferenceChangeListener(prefsListener)
        swapBackend()
        observeApps()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AppListPrefs.get(requireContext()).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ── Backend management ────────────────────────────────────────────────────

    private fun swapBackend() {
        val ctx = requireContext()
        val style = AppListPrefs.style(ctx)
        val sort  = AppListPrefs.sort(ctx)

        val newBackend = createBackend(ctx, style)
        newBackend.onAppClick = { item ->
            val pm = ctx.packageManager
            pm.getLaunchIntentForPackage(item.packageName)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { ctx.startActivity(it) }
        }
        newBackend.onAppLongPress = { item, anchor ->
            AppContextMenu.show(ctx, anchor, item) { viewModel.loadApps() }
        }
        newBackend.onRequestClose = { onRequestClose?.invoke() }
        newBackend.setDragEnabled(sort == AppListPrefs.SORT_CUSTOM)

        val contentView = newBackend.createContentView(ctx)
        root.removeAllViews()
        root.addView(contentView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        backend = newBackend
        onPagesBackendActive?.invoke(style == AppListPrefs.STYLE_PAGES)

        // Bind current list immediately if already loaded
        val current = viewModel.apps.value
        if (current.isNotEmpty()) newBackend.bindItems(current)
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
                viewModel.apps.collect { items -> backend?.bindItems(items) }
            }
        }
    }
}
