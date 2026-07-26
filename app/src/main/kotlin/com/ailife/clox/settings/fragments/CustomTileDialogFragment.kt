package com.ailife.clox.settings.fragments

import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.ailife.clox.icon.renderToBitmap
import com.ailife.clox.qs.tile.CustomTileConfig
import com.ailife.clox.qs.tile.CustomTileMode
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Material 3 dialog for creating a custom QS tile. Instead of typing raw component / settings
 * strings, the user picks an installed app from a list, or picks an existing settings key from the
 * chosen namespace. Manual key entry is still available as a fallback.
 */
class CustomTileDialogFragment(
    private val existing: CustomTileConfig? = null,
    private val onSave: (CustomTileConfig) -> Unit
) : DialogFragment() {

    private data class AppRow(val component: String, val label: String, val icon: Bitmap)

    // Selected launch-activity component (flattened "pkg/cls"), null until the user picks one.
    private var pickedComponent: String? = existing?.activityComponent

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_custom_tile, null, false)

        val etLabel = view.findViewById<TextInputEditText>(R.id.etLabel)
        val toggleMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleMode)
        val sectionApp = view.findViewById<View>(R.id.sectionApp)
        val sectionSetting = view.findViewById<View>(R.id.sectionSetting)
        val btnChooseApp = view.findViewById<MaterialButton>(R.id.btnChooseApp)
        val rowSelectedApp = view.findViewById<View>(R.id.rowSelectedApp)
        val ivAppIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
        val tvAppLabel = view.findViewById<TextView>(R.id.tvAppLabel)
        val etComponent = view.findViewById<TextInputEditText>(R.id.etComponent)
        val toggleNs = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleNamespace)
        val btnPickSetting = view.findViewById<MaterialButton>(R.id.btnPickSetting)
        val etKey = view.findViewById<TextInputEditText>(R.id.etKey)

        // Initial selections — pre-fill from the tile being edited, else defaults.
        val editingSetting = existing?.mode == CustomTileMode.WRITE_SETTINGS
        toggleMode.check(if (editingSetting) R.id.btnModeSetting else R.id.btnModeApp)
        toggleNs.check(when (existing?.settingsNamespace) {
            "secure" -> R.id.btnNsSecure
            "global" -> R.id.btnNsGlobal
            else     -> R.id.btnNsSystem
        })
        sectionApp.visibility = if (editingSetting) View.GONE else View.VISIBLE
        sectionSetting.visibility = if (editingSetting) View.VISIBLE else View.GONE
        existing?.let { cfg ->
            etLabel.setText(cfg.label)
            cfg.settingsKey?.let { etKey.setText(it) }
            // For a launch tile, show the raw component (editable) and resolve its app name + icon.
            cfg.activityComponent?.let { comp ->
                etComponent.setText(comp)
                ComponentName.unflattenFromString(comp)?.let { cn ->
                    runCatching {
                        val pm = ctx.packageManager
                        val ai = pm.getActivityInfo(cn, 0)
                        val sizePx = (32 * resources.displayMetrics.density + 0.5f).toInt()
                        ivAppIcon.setImageBitmap(ai.loadIcon(pm).renderToBitmap(sizePx))
                        tvAppLabel.text = ai.loadLabel(pm).toString()
                    }.onFailure { tvAppLabel.text = comp }
                    rowSelectedApp.visibility = View.VISIBLE
                }
            }
        }

        fun namespaceOf(): String = when (toggleNs.checkedButtonId) {
            R.id.btnNsSecure -> "secure"
            R.id.btnNsGlobal -> "global"
            else             -> "system"
        }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sectionApp.visibility = if (checkedId == R.id.btnModeApp) View.VISIBLE else View.GONE
            sectionSetting.visibility = if (checkedId == R.id.btnModeSetting) View.VISIBLE else View.GONE
        }

        btnChooseApp.setOnClickListener {
            showAppPicker { row ->
                pickedComponent = row.component
                etComponent.setText(row.component)  // editable — user can retarget any activity
                ivAppIcon.setImageBitmap(row.icon)
                tvAppLabel.text = row.label
                rowSelectedApp.visibility = View.VISIBLE
                // Pre-fill the label with the app name if the user hasn't typed one.
                if (etLabel.text.isNullOrBlank()) etLabel.setText(row.label)
            }
        }

        btnPickSetting.setOnClickListener { showSettingKeyPicker(namespaceOf()) { key -> etKey.setText(key) } }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing != null) R.string.custom_tile_edit_title else R.string.custom_tile_dialog_title)
            .setView(view)
            .setPositiveButton(
                if (existing != null) R.string.custom_tile_save_btn else R.string.custom_tile_add_btn,
                null  // overridden below to validate
            )
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Validate before dismissing so an incomplete tile can't be saved.
        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                val label = etLabel.text?.toString()?.trim().orEmpty()
                    .ifEmpty { getString(R.string.custom_tile_default_label) }
                // Keep the same uuid when editing so the tile stays in place; new uuid otherwise.
                val uuid = existing?.uuid ?: UUID.randomUUID().toString()
                val config = if (toggleMode.checkedButtonId == R.id.btnModeApp) {
                    // Prefer the (editable) component field so a hand-targeted non-main activity wins;
                    // fall back to whatever the picker selected. Reject anything unparseable.
                    val typed = etComponent.text?.toString()?.trim().orEmpty()
                    val component = typed.ifEmpty { pickedComponent.orEmpty() }
                    if (component.isEmpty() || ComponentName.unflattenFromString(component) == null) {
                        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilComponent)
                            .error = " "
                        return@setOnClickListener
                    }
                    CustomTileConfig(
                        uuid = uuid,
                        label = label,
                        mode = CustomTileMode.LAUNCH_ACTIVITY,
                        activityComponent = component
                    )
                } else {
                    val key = etKey.text?.toString()?.trim().orEmpty()
                    if (key.isEmpty()) {
                        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilKey)
                            .error = " "
                        return@setOnClickListener
                    }
                    CustomTileConfig(
                        uuid = uuid,
                        label = label,
                        mode = CustomTileMode.WRITE_SETTINGS,
                        settingsNamespace = namespaceOf(),
                        settingsKey = key
                    )
                }
                onSave(config)
                dialog.dismiss()
            }
        }
        return dialog
    }

    /** Show the installed-launcher-app list and return the picked component via [onPick]. */
    private fun showAppPicker(onPick: (AppRow) -> Unit) {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val iconSizePx = (40 * resources.displayMetrics.density + 0.5f).toInt()

        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).sortedBy { it.loadLabel(pm).toString().lowercase() }
                    .map { ri: ResolveInfo ->
                        val ai = ri.activityInfo
                        AppRow(
                            component = ComponentName(ai.packageName, ai.name).flattenToString(),
                            label = ri.loadLabel(pm).toString(),
                            icon = ri.loadIcon(pm).renderToBitmap(iconSizePx)
                        )
                    }
            }
            if (!isAdded) return@launch

            val labels = rows.map { it.label }
            val adapter = object : ArrayAdapter<String>(ctx, R.layout.item_app_list, labels) {
                override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                    val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_app_list, parent, false)
                    v.findViewById<ImageView>(R.id.appIcon).setImageBitmap(rows[pos].icon)
                    v.findViewById<TextView>(R.id.appLabel).text = labels[pos]
                    return v
                }
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.custom_tile_choose_app_title)
                .setAdapter(adapter) { _, which -> onPick(rows[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** List the keys currently present in the [namespace] settings table and return one via [onPick]. */
    private fun showSettingKeyPicker(namespace: String, onPick: (String) -> Unit) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val keys = withContext(Dispatchers.IO) {
                val uri: Uri = when (namespace) {
                    "secure" -> Settings.Secure.CONTENT_URI
                    "global" -> Settings.Global.CONTENT_URI
                    else     -> Settings.System.CONTENT_URI
                }
                val out = sortedSetOf<String>()
                runCatching {
                    ctx.contentResolver.query(uri, arrayOf("name"), null, null, "name")?.use { c ->
                        val col = c.getColumnIndex("name")
                        if (col >= 0) while (c.moveToNext()) c.getString(col)?.let { out.add(it) }
                    }
                }
                out.toTypedArray()
            }
            if (!isAdded) return@launch
            if (keys.isEmpty()) {
                android.widget.Toast.makeText(ctx, R.string.custom_tile_no_settings, android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.custom_tile_pick_setting_title)
                .setItems(keys) { _, which -> onPick(keys[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
