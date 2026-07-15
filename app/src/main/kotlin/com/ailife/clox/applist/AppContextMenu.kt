package com.ailife.clox.applist

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ailife.clox.R
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.util.safeStartActivity

object AppContextMenu {

    private var current: PopupWindow? = null
    val isShowing get() = current?.isShowing == true

    fun show(
        context: Context,
        anchor: View,
        item: AppItem,
        onIconChanged: () -> Unit,
        onHideApp: () -> Unit = {}
    ) {
        dismissCurrent()

        val entries = buildList {
            add(Entry(R.string.menu_hide_app, R.drawable.ic_block) {
                // Confirm first — this menu item is easy to mis-tap.
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.hide_confirm_title)
                    .setMessage(context.getString(R.string.hide_confirm_message, item.label))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.menu_hide_app) { _, _ ->
                        quickHideApp(context, item)
                        onHideApp()
                    }
                    .show()
            })
            add(Entry(R.string.menu_uninstall,   R.drawable.ic_delete)  { requestUninstall(context, item) })
            add(Entry(R.string.menu_app_info,    R.drawable.ic_info)    { openAppInfo(context, item) })
            add(Entry(R.string.menu_change_icon, R.drawable.ic_palette) { IconPickerDialog.show(context, item) { onIconChanged() } })
            if (AppListPrefs.customIconPath(context, item.packageName, item.activityName) != null) {
                add(Entry(R.string.menu_reset_icon, R.drawable.ic_reset) {
                    AppListPrefs.saveCustomIconPath(context, item.packageName, item.activityName, null)
                    onIconChanged()
                })
            }
        }

        val shortcuts = queryShortcuts(context, item)

        val density = context.resources.displayMetrics.density
        val ta = context.obtainStyledAttributes(intArrayOf(
            android.R.attr.colorBackground,
            com.google.android.material.R.attr.colorSurface
        ))
        val surfaceColor = ta.getColor(1, ta.getColor(0, context.getColor(R.color.fallback_surface)))
        ta.recycle()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = density * 16f
            setColor(surfaceColor)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (8 * density).toInt()
            setPadding(0, p, 0, p)
        }

        val scrollView = ScrollView(context).apply {
            background = bg
            elevation = density * 8f
            // Clip scrolling content to the rounded-corner background so items
            // don't render outside the visual background when the list scrolls.
            clipToOutline = true
        }
        scrollView.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

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

        if (shortcuts.isNotEmpty()) {
            container.addView(makeDivider(context, density))

            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            for (shortcut in shortcuts) {
                val v = inflater.inflate(R.layout.item_context_menu, container, false)
                val icon = try {
                    launcherApps.getShortcutIconDrawable(shortcut, context.resources.displayMetrics.densityDpi)
                } catch (_: Exception) { null }
                v.findViewById<ImageView>(R.id.menuIcon).setImageDrawable(
                    icon ?: ContextCompat.getDrawable(context, R.drawable.ic_open_in_new)
                )
                v.findViewById<TextView>(R.id.menuLabel).text =
                    shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id
                v.setOnClickListener {
                    popupRef?.dismiss()
                    current = null
                    try { launcherApps.startShortcut(shortcut, null, null) } catch (_: Exception) { }
                }
                container.addView(v)
            }
        }

        val popupWidth = (200 * density).toInt()
        val margin = (8 * density).toInt()
        val screenH = context.resources.displayMetrics.heightPixels
        val screenW = context.resources.displayMetrics.widthPixels

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorTop    = loc[1]
        val anchorBottom = loc[1] + anchor.height
        val spaceBelow   = screenH - anchorBottom - margin
        val spaceAbove   = anchorTop - margin

        // Use whichever side has more space; cap height to that available space
        val availableH = maxOf(spaceBelow, spaceAbove)
        val maxHeight  = (screenH * 0.70f).toInt().coerceAtMost(availableH)

        scrollView.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val popupHeight = scrollView.measuredHeight

        // Horizontally: centre over anchor, clamped within screen
        val anchorCx = loc[0] + anchor.width / 2
        val x = (anchorCx - popupWidth / 2).coerceIn(margin, screenW - popupWidth - margin)

        // Vertically: below anchor if more space there, above otherwise
        val y = if (spaceBelow >= spaceAbove) anchorBottom + margin
                else anchorTop - popupHeight - margin

        val popup = PopupWindow(scrollView, popupWidth, popupHeight).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = density * 8f
            setOnDismissListener { current = null }
        }
        popupRef = popup
        current = popup

        popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y)
    }

    fun dismissCurrent() {
        current?.dismiss()
        current = null
    }

    private data class Entry(val labelRes: Int, val iconRes: Int, val action: () -> Unit)

    private fun quickHideApp(ctx: Context, item: AppItem) {
        val component = "${item.packageName}/${item.activityName}"
        val mode = AppListPrefs.hideMode(ctx)
        val hidden = AppListPrefs.hiddenComponents(ctx).toMutableSet()
        if (mode == AppListPrefs.HIDE_MODE_WHITELIST) {
            hidden.remove(component)   // remove from "visible" set → becomes hidden
        } else {
            hidden.add(component)
        }
        AppListPrefs.saveHiddenComponents(ctx, hidden)
    }

    private fun queryShortcuts(ctx: Context, item: AppItem): List<ShortcutInfo> {
        return try {
            val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = ShortcutQuery().apply {
                setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_MANIFEST)
                setPackage(item.packageName)
            }
            la.getShortcuts(query, Process.myUserHandle()) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun makeDivider(ctx: Context, density: Float): View {
        return View(ctx).apply {
            setBackgroundColor(Color.argb(40, 128, 128, 128))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                val vm = (4 * density).toInt()
                setMargins(0, vm, 0, vm)
            }
        }
    }

    private fun requestUninstall(ctx: Context, item: AppItem) {
        ctx.safeStartActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openAppInfo(ctx: Context, item: AppItem) {
        ctx.safeStartActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${item.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
