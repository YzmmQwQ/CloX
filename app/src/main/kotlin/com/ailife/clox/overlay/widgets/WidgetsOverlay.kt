package com.ailife.clox.overlay.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.overlay.widgets.cards.AddWidgetCard

class WidgetsOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val widgetList: RecyclerView
    private val blurBg: ImageView
    val widgetHostManager = WidgetHostManager(ctx)

    private val widgetIds = mutableListOf<Int>()

    init {
        LayoutInflater.from(ctx).inflate(R.layout.overlay_widgets, this, true)
        widgetList = findViewById(R.id.widgetList)
        blurBg = findViewById<ImageView>(R.id.widgetBlurBg).apply { tag = "blur_bg" }

        widgetList.layoutManager = LinearLayoutManager(ctx)

        setupAddCard()
    }

    private fun setupAddCard() {
        // AddWidgetCard is the last item in the list
        // Full widget picker integration via startActivityForResult from hosting Activity
    }

    fun onWidgetPicked(info: AppWidgetProviderInfo) {
        val id = widgetHostManager.allocateId()
        widgetIds.add(id)
        val hostView = widgetHostManager.bindWidget(context, id, info)
        val card = WidgetCard(context).apply { setWidget(hostView); setHeightUnits(1) }
        widgetList.adapter?.notifyItemInserted(widgetIds.size - 1)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        widgetHostManager.startListening()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        widgetHostManager.stopListening()
    }
}
