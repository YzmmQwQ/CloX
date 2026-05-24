package com.ailife.clox.settings.fragments

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.tapaction.TapActionHandler
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TapActionsSettingsFragment : BaseSettingsFragment() {

    private val actionRows = mutableMapOf<String, TextView>()

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
        val apps: List<ResolveInfo> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).sortedBy { it.loadLabel(pm).toString().lowercase() }

        val adapter = object : ArrayAdapter<ResolveInfo>(ctx, R.layout.item_app_list, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_app_list, parent, false)
                val ri = getItem(position)!!
                view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(ri.loadIcon(pm))
                view.findViewById<TextView>(R.id.appLabel).text = ri.loadLabel(pm)
                return view
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(def.displayNameRes)
            .setAdapter(adapter) { _, which ->
                val info = apps[which].activityInfo
                TapActionHandler.setComponent(ctx, def.key, "${info.packageName}/${info.name}")
                actionRows[def.key]?.text = apps[which].loadLabel(pm).toString()
            }
            .setNeutralButton(R.string.pref_tap_clear) { _, _ ->
                TapActionHandler.setComponent(ctx, def.key, "")
                actionRows[def.key]?.text = getString(R.string.pref_tap_not_configured)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
