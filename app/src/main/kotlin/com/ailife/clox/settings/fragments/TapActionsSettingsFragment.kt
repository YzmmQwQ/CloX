package com.ailife.clox.settings.fragments

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.ailife.clox.icon.renderToBitmap
import com.ailife.clox.tapaction.TapActionHandler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TapActionsSettingsFragment : BaseSettingsFragment() {

    private val actionRows = mutableMapOf<String, TextView>()

    private data class AppRow(val ri: ResolveInfo, val label: String, val icon: Bitmap)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_tap_actions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = view.findViewById<LinearLayout>(R.id.containerTapActions)
        val inflater  = LayoutInflater.from(requireContext())

        for ((index, def) in TapActionHandler.CONFIGURABLE_ACTIONS.withIndex()) {
            val row = inflater.inflate(R.layout.row_tap_action, container, false)
            row.findViewById<TextView>(R.id.tvTapActionName).text = getString(def.displayNameRes)
            val tvSummary = row.findViewById<TextView>(R.id.tvTapActionSummary)
            tvSummary.text = currentSummary(def.key)
            actionRows[def.key] = tvSummary
            if (index > 0) {
                val params = row.layoutParams as LinearLayout.LayoutParams
                params.topMargin = (14 * resources.displayMetrics.density).toInt()
            }
            row.setOnClickListener { showAppPicker(def) }
            container.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        for (def in TapActionHandler.CONFIGURABLE_ACTIONS) {
            actionRows[def.key]?.text = currentSummary(def.key)
        }
    }

    private fun currentSummary(key: String) =
        TapActionHandler.getMappedAppLabel(requireContext(), key) ?: getString(R.string.pref_tap_not_configured)

    private fun showAppPicker(def: TapActionHandler.ActionDef) {
        val ctx = requireContext()
        val pm  = ctx.packageManager
        val iconSizePx = (40 * resources.displayMetrics.density + 0.5f).toInt()

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).sortedBy { it.loadLabel(pm).toString().lowercase() }
                 .map { ri -> AppRow(ri, ri.loadLabel(pm).toString(), ri.loadIcon(pm).renderToBitmap(iconSizePx)) }
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
                .setTitle(def.displayNameRes)
                .setAdapter(adapter) { _, which ->
                    val info = rows[which].ri.activityInfo
                    TapActionHandler.setComponent(ctx, def.key, "${info.packageName}/${info.name}")
                    actionRows[def.key]?.text = rows[which].label
                }
                .setNeutralButton(R.string.pref_tap_clear) { _, _ ->
                    TapActionHandler.setComponent(ctx, def.key, "")
                    actionRows[def.key]?.text = getString(R.string.pref_tap_not_configured)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
