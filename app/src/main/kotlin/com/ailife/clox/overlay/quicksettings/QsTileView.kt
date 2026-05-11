package com.ailife.clox.overlay.quicksettings

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.ailife.clox.R
import com.google.android.material.card.MaterialCardView

class QsTileView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : MaterialCardView(ctx, attrs) {

    private val icon: ImageView
    private val label: TextView

    var isActive: Boolean = false
        set(value) {
            field = value
            updateAppearance()
        }

    var onToggle: ((Boolean) -> Unit)? = null

    init {
        LayoutInflater.from(ctx).inflate(R.layout.view_qs_tile, this, true)
        icon = findViewById(R.id.tileIcon)
        label = findViewById(R.id.tileLabel)
        setOnClickListener {
            isActive = !isActive
            onToggle?.invoke(isActive)
        }
        updateAppearance()
    }

    fun configure(iconRes: Int, labelText: String, active: Boolean) {
        icon.setImageResource(iconRes)
        label.text = labelText
        isActive = active
    }

    private fun updateAppearance() {
        val colorAttr = if (isActive) com.google.android.material.R.attr.colorPrimary
                        else com.google.android.material.R.attr.colorSurface
        val typedArray = context.obtainStyledAttributes(intArrayOf(colorAttr))
        val color = typedArray.getColor(0, 0)
        typedArray.recycle()
        setCardBackgroundColor(color)
    }
}
