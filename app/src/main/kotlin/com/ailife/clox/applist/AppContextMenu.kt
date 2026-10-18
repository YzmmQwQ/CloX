package com.ailife.clox.applist

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ailife.clox.R
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.util.safeStartActivity

object AppContextMenu {

    private var current: Dialog? = null
    val isShowing get() = current?.isShowing == true

    fun show(
        context: Context,
        anchor: View,
        item: AppItem,
        onIconChanged: () -> Unit,
        onHideApp: () -> Unit = {},
        onFolderChanged: () -> Unit = {}
    ) {
        dismissCurrent()

        val componentKey = "${item.packageName}/${item.activityName}"
        val currentFolder = AppListPrefs.folderContaining(context, componentKey)
        val allFolders = AppListPrefs.folders(context)

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
            // ── Folder actions ─────────────────────────────────────────────
            add(Entry(R.string.menu_new_folder, R.drawable.ic_create_new_folder) {
                promptText(context, context.getString(R.string.folder_name_title), "") { name ->
                    AppListPrefs.createFolder(context, name.ifBlank { context.getString(R.string.folder_default_name) }, componentKey)
                    onFolderChanged()
                }
            })
            if (allFolders.isNotEmpty()) {
                add(Entry(R.string.menu_add_to_folder, R.drawable.ic_folder) {
                    pickFolder(context, allFolders) { folder ->
                        if (!AppListPrefs.addToFolder(context, folder.id, componentKey)) {
                            android.widget.Toast.makeText(
                                context, R.string.folder_full, android.widget.Toast.LENGTH_SHORT).show()
                        } else onFolderChanged()
                    }
                })
            }
            if (currentFolder != null) {
                add(Entry(R.string.menu_remove_from_folder, R.drawable.ic_folder_remove) {
                    AppListPrefs.removeFromFolder(context, componentKey)
                    onFolderChanged()
                })
            }
        }

        val shortcuts = queryShortcuts(context, item)

        val rows = ArrayList<Row>(entries.size + shortcuts.size + 1)
        for (entry in entries) {
            rows.add(Row(
                label = context.getString(entry.labelRes),
                icon = ContextCompat.getDrawable(context, entry.iconRes),
                action = entry.action
            ))
        }
        if (shortcuts.isNotEmpty()) {
            rows.add(Row(divider = true))
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            for (shortcut in shortcuts) {
                val icon = try {
                    launcherApps.getShortcutIconDrawable(shortcut, context.resources.displayMetrics.densityDpi)
                } catch (_: Exception) { null }
                rows.add(Row(
                    label = shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id,
                    icon = icon ?: ContextCompat.getDrawable(context, R.drawable.ic_open_in_new)
                ) {
                    try { launcherApps.startShortcut(shortcut, null, null) } catch (_: Exception) { }
                })
            }
        }

        showMenuDialog(context, rows)
    }

    /**
     * Folder long-press menu: Rename / Edit members / Delete. Edit members opens the settings
     * folder-member picker; the others act inline. [onChanged] refreshes the app list.
     */
    fun showFolderMenu(
        context: Context,
        anchor: View,
        folderId: String,
        folderName: String,
        onChanged: () -> Unit
    ) {
        dismissCurrent()
        val entries = listOf(
            Entry(R.string.menu_rename_folder, R.drawable.ic_edit) {
                promptText(context, context.getString(R.string.folder_rename_title), folderName) { name ->
                    if (name.isNotBlank()) { AppListPrefs.renameFolder(context, folderId, name); onChanged() }
                }
            },
            Entry(R.string.menu_edit_members, R.drawable.ic_folder) {
                context.safeStartActivity(
                    Intent(context, com.ailife.clox.settings.SettingsActivity::class.java).apply {
                        putExtra(com.ailife.clox.settings.SettingsActivity.EXTRA_OPEN_FOLDER, folderId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            },
            Entry(R.string.menu_delete_folder, R.drawable.ic_delete) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.folder_delete_title)
                    .setMessage(context.getString(R.string.folder_delete_message, folderName))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.menu_delete_folder) { _, _ ->
                        AppListPrefs.deleteFolder(context, folderId)
                        onChanged()
                    }
                    .show()
            }
        )
        val rows = entries.map { entry ->
            Row(
                label = context.getString(entry.labelRes),
                icon = ContextCompat.getDrawable(context, entry.iconRes),
                action = entry.action
            )
        }
        showMenuDialog(context, rows)
    }

    private data class Row(
        val label: CharSequence? = null,
        val icon: Drawable? = null,
        val divider: Boolean = false,
        val action: (() -> Unit)? = null
    )

    /**
     * Shows [rows] in a standalone Material dialog. A dialog (rather than an
     * anchored PopupWindow) sidesteps the small-watch-screen blowup: the old
     * anchor maths built a 200dp popup and clamped its x-range with coerceIn,
     * which throws when the screen is narrower than the popup. The dialog is
     * sized to the screen, scrolls when the rows exceed it, and follows the
     * DayNight theme.
     */
    private fun showMenuDialog(context: Context, rows: List<Row>) {
        dismissCurrent()
        val density = context.resources.displayMetrics.density
        val inflater = LayoutInflater.from(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (8 * density).toInt()
            setPadding(0, p, 0, p)
        }
        for (row in rows) {
            if (row.divider) {
                container.addView(makeDivider(context, density))
                continue
            }
            val v = inflater.inflate(R.layout.item_context_menu, container, false)
            v.findViewById<ImageView>(R.id.menuIcon).setImageDrawable(row.icon)
            v.findViewById<TextView>(R.id.menuLabel).text = row.label
            v.setOnClickListener {
                val action = row.action
                dismissCurrent()
                action?.invoke()
            }
            container.addView(v)
        }

        val screenW = context.resources.displayMetrics.widthPixels
        val scroll = ScrollView(context).apply {
            addView(container, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(scroll, 0, 0, 0, 0)
            .create()
        dialog.setOnDismissListener { current = null }
        dialog.setOnShowListener {
            // Nearly full width on tiny (watch) screens; the platform caps height.
            dialog.window?.setLayout(
                (screenW * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        current = dialog
        dialog.show()
    }

    /** Simple single-line text input dialog (folder name / rename). */
    private fun promptText(context: Context, title: String, initial: String, onOk: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        val input = android.widget.EditText(context).apply {
            setText(initial)
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pad = (20 * density).toInt()
        val holder = FrameLayout(context).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(holder)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString().trim()) }
            .show()
    }

    /** Lists existing folders for "Add to folder". */
    private fun pickFolder(context: Context, folders: List<AppListPrefs.Folder>, onPick: (AppListPrefs.Folder) -> Unit) {
        val names = folders.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.menu_add_to_folder)
            .setItems(names) { _, which -> onPick(folders[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
