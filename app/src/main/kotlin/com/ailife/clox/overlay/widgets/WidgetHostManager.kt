package com.ailife.clox.overlay.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

class WidgetHostManager(context: Context) : AppWidgetHost(context, HOST_ID) {

    private val appWidgetManager = AppWidgetManager.getInstance(context)

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView = AppWidgetHostView(context)

    fun allocateId(): Int = allocateAppWidgetId()

    fun bindWidget(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView {
        val view = createView(context, appWidgetId, info)
        updateAppWidgetView(view, appWidgetId, info)
        return view
    }

    private fun updateAppWidgetView(view: AppWidgetHostView, appWidgetId: Int, info: AppWidgetProviderInfo) {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        view.setAppWidget(appWidgetId, info)
        view.updateAppWidgetSize(options,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
    }

    fun removeWidget(appWidgetId: Int) {
        deleteAppWidgetId(appWidgetId)
    }

    companion object {
        const val HOST_ID = 0x4c585800  // 'LX\0'
        const val REQUEST_BIND_WIDGET = 9001
        const val REQUEST_CONFIGURE_WIDGET = 9002
    }
}
