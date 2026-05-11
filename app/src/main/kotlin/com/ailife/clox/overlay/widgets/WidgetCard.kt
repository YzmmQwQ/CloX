package com.ailife.clox.overlay.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.ailife.clox.R
import com.google.android.material.card.MaterialCardView

class WidgetCard @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : MaterialCardView(ctx, attrs) {

    private val container: FrameLayout

    init {
        LayoutInflater.from(ctx).inflate(R.layout.card_widget, this, true)
        container = findViewById(R.id.widgetContainer)
        radius = resources.getDimension(R.dimen.widget_card_radius)
    }

    fun setWidget(hostView: AppWidgetHostView) {
        container.removeAllViews()
        container.addView(hostView)
    }

    fun setHeightUnits(units: Int) {
        val unitH = context.resources.displayMetrics.heightPixels / 5
        layoutParams = layoutParams?.apply { height = unitH * units }
    }
}
