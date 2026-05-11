package com.ailife.clox.applist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.PopupMenu
import com.ailife.clox.R
import com.ailife.clox.applist.model.AppItem

object AppContextMenu {

    fun show(
        context: Context,
        anchor: View,
        item: AppItem,
        onIconChanged: () -> Unit
    ) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, R.string.menu_uninstall)
        popup.menu.add(0, 2, 0, R.string.menu_app_info)
        popup.menu.add(0, 3, 0, R.string.menu_change_icon)
        if (AppListPrefs.customIconPath(context, item.packageName) != null) {
            popup.menu.add(0, 4, 0, R.string.menu_reset_icon)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> requestUninstall(context, item)
                2 -> openAppInfo(context, item)
                3 -> IconPickerDialog.show(context, item) { onIconChanged() }
                4 -> {
                    AppListPrefs.saveCustomIconPath(context, item.packageName, null)
                    onIconChanged()
                }
            }
            true
        }
        popup.show()
    }

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
