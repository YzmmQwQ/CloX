package com.ailife.clox.applist

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ailife.clox.R
import com.ailife.clox.applist.model.AppItem

object AppContextMenu {

    private var current: PopupWindow? = null
    val isShowing get() = current?.isShowing == true

    fun show(context: Context, anchor: View, item: AppItem, onIconChanged: () -> Unit) {
        dismissCurrent()

        val entries = buildList {
            add(Entry(R.string.menu_uninstall,   R.drawable.ic_delete)  { requestUninstall(context, item) })
            add(Entry(R.string.menu_app_info,    R.drawable.ic_info)    { openAppInfo(context, item) })
            add(Entry(R.string.menu_change_icon, R.drawable.ic_palette) { IconPickerDialog.show(context, item) { onIconChanged() } })
            if (AppListPrefs.customIconPath(context, item.packageName) != null) {
                add(Entry(R.string.menu_reset_icon, R.drawable.ic_reset) {
                    AppListPrefs.saveCustomIconPath(context, item.packageName, null)
                    onIconChanged()
                })
            }
        }

        val density = context.resources.displayMetrics.density
        val ta = context.obtainStyledAttributes(intArrayOf(
            android.R.attr.colorBackground,
            com.google.android.material.R.attr.colorSurface
        ))
        val surfaceColor = ta.getColor(1, ta.getColor(0, 0xFF1C1B1F.toInt()))
        ta.recycle()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = density * 16f
            setColor(surfaceColor)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            elevation = density * 8f
            val p = (8 * density).toInt()
            setPadding(0, p, 0, p)
        }

        val inflater = LayoutInflater.from(context)
        var popupRef: PopupWindow? = null
        for (entry in entries) {
            val v = inflater.inflate(R.layout.item_context_menu, container, false)
            v.findViewById<ImageView>(R.id.menuIcon).setImageDrawable(
                ContextCompat.getDrawable(context, entry.iconRes)
            )
            v.findViewById<TextView>(R.id.menuLabel).setText(entry.labelRes)
            v.setOnClickListener {
                popupRef?.dismiss()
                current = null
                entry.action()
            }
            container.addView(v)
        }

        val popup = PopupWindow(
            container,
            (200 * density).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = false      // touch events on underlying view still fire (needed for drag detection)
            isOutsideTouchable = false
            elevation = density * 8f
        }
        popupRef = popup
        current = popup

        // Show centered on screen — suitable for small watch displays
        popup.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0)
    }

    fun dismissCurrent() {
        current?.dismiss()
        current = null
    }

    private data class Entry(val labelRes: Int, val iconRes: Int, val action: () -> Unit)

    private fun requestUninstall(ctx: Context, item: AppItem) {
        ctx.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openAppInfo(ctx: Context, item: AppItem) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${item.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
