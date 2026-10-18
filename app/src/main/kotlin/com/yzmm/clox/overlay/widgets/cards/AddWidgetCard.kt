package com.yzmm.clox.overlay.widgets.cards

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import com.yzmm.clox.R
import com.google.android.material.card.MaterialCardView

class AddWidgetCard @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : MaterialCardView(ctx, attrs) {

    var onAddClicked: (() -> Unit)? = null

    init {
        val label = TextView(ctx).apply {
            text = ctx.getString(R.string.add_widget)
            gravity = Gravity.CENTER
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_add, 0, 0)
            compoundDrawablePadding = 8
        }
        addView(label)
        setOnClickListener { onAddClicked?.invoke() }
    }
}
