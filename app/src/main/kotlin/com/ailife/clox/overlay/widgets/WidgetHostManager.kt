package com.ailife.clox.overlay.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.SparseArray

class WidgetHostManager(context: Context) : AppWidgetHost(context, HOST_ID) {

    private val appWidgetManager = AppWidgetManager.getInstance(context)
    private val hostViews = SparseArray<AppWidgetHostView>()

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView {
        // Use applicationContext so AppCompat's LayoutInflater.Factory (which replaces
        // TextView→MaterialTextView etc.) is not applied when the widget inflates its
        // RemoteViews. The activity context has that factory installed and the widget's
        // theme is null, causing "Failed to resolve attribute" → "Can't load widget".
        return AppWidgetHostView(context.applicationContext)
    }

    fun allocateId(): Int = allocateAppWidgetId()

    /** Returns cached view or creates a new one. Caller must reparent as needed. */
    fun getOrCreateView(
        context: Context,
        appWidgetId: Int,
        info: AppWidgetProviderInfo
    ): AppWidgetHostView {
        hostViews[appWidgetId]?.let { return it }
        val view = createView(context, appWidgetId, info)
        hostViews.put(appWidgetId, view)
        // Provide size hints so the widget provider knows what dimensions to render at.
        // Without these, many widgets render an empty/loading placeholder indefinitely.
        val dm = context.resources.displayMetrics
        val maxW = (dm.widthPixels / dm.density).toInt()
        val maxH = (dm.heightPixels / dm.density).toInt()
        val minW = info.minWidth.coerceAtMost(maxW)
        val minH = info.minHeight.coerceAtMost(maxH)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minW)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minH)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxW)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxH)
        }
        appWidgetManager.updateAppWidgetOptions(appWidgetId, options)
        return view
    }

    fun removeWidget(appWidgetId: Int) {
        hostViews.remove(appWidgetId)
        deleteAppWidgetId(appWidgetId)
    }

    fun removeAllWidgets() {
        for (i in 0 until hostViews.size()) {
            deleteAppWidgetId(hostViews.keyAt(i))
        }
        hostViews.clear()
    }

    companion object {
        const val HOST_ID = 0x4c585800  // 'LX\0'
        const val REQUEST_PICK_WIDGET = 9000
        const val REQUEST_BIND_WIDGET = 9001
        const val REQUEST_CONFIGURE_WIDGET = 9002
    }
}
