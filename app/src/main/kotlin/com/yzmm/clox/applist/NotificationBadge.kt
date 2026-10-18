package com.yzmm.clox.applist

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.yzmm.clox.R
import com.google.android.material.color.MaterialColors

object NotificationBadge {

    private var bgColor: Int = Color.RED
    private var fgColor: Int = Color.WHITE
    private var colorsResolved = false

    /** Resolve theme colors once. Must be called from a themed context. */
    fun resolveColors(context: Context) {
        if (colorsResolved) return
        bgColor = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, Color.RED)
        fgColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnError, Color.WHITE)
        colorsResolved = true
    }

    /** Bind notification count to a badge TextView (from XML layout or [makeView]). */
    fun bind(
        badge: TextView,
        packageName: String,
        counts: Map<String, Int>,
        showBadge: Boolean,
        showCount: Boolean
    ) {
        val count = if (showBadge) counts[packageName] ?: 0 else 0
        if (count == 0) { badge.visibility = View.GONE; return }

        // Set Material error color background on first show
        if (badge.tag == null) {
            if (!colorsResolved) resolveColors(badge.context)
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            badge.setTextColor(fgColor)
            badge.tag = true
        }

        badge.visibility = View.VISIBLE
        val d = badge.context.resources.displayMetrics.density
        if (showCount) {
            val ctx = badge.context
            badge.text = if (count > 99) ctx.getString(R.string.badge_overflow) else count.toString()
            val sz = (18 * d).toInt()
            badge.minWidth = sz; badge.minHeight = sz
        } else {
            badge.text = ""
            val sz = (10 * d).toInt()
            badge.minWidth = sz; badge.minHeight = sz
        }
    }

    /** Create a badge view for backends that build icons programmatically. */
    fun makeView(context: Context): TextView {
        val d = context.resources.displayMetrics.density
        val bg = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, Color.RED)
        val fg = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnError, Color.WHITE)
        return TextView(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            setTextColor(fg)
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            minWidth = (10 * d).toInt()
            minHeight = (10 * d).toInt()
            setPadding((3 * d).toInt(), (1 * d).toInt(), (3 * d).toInt(), (1 * d).toInt())
            visibility = View.GONE
            tag = true  // already initialized
        }
    }
}
