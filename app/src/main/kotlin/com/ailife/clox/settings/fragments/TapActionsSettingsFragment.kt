package com.ailife.clox.settings.fragments

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.ailife.clox.R
import com.ailife.clox.tapaction.TapActionHandler

class TapActionsSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx    = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen

        // ── Info about built-in actions ───────────────────────────────────────
        PreferenceCategory(ctx).apply {
            title = getString(R.string.pref_tap_category_builtin)
            screen.addPreference(this)
            addPreference(Preference(ctx).apply {
                summary = getString(R.string.pref_tap_builtin_desc)
                isSelectable = false
            })
        }

        // ── Configurable app shortcuts ────────────────────────────────────────
        PreferenceCategory(ctx).apply {
            title = getString(R.string.pref_tap_category_shortcuts)
            screen.addPreference(this)
            for (def in TapActionHandler.CONFIGURABLE_ACTIONS) {
                addPreference(buildActionPref(def))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh summaries in case the user installed/uninstalled apps
        for (def in TapActionHandler.CONFIGURABLE_ACTIONS) {
            findPreference<Preference>("tap_action_${def.key}")?.summary = currentSummary(def.key)
        }
    }

    private fun buildActionPref(def: TapActionHandler.ActionDef): Preference =
        Preference(requireContext()).apply {
            key     = "tap_action_${def.key}"
            title   = def.displayName
            summary = currentSummary(def.key)
            setOnPreferenceClickListener {
                showAppPicker(def)
                true
            }
        }

    private fun currentSummary(actionKey: String): String {
        val label = TapActionHandler.getMappedAppLabel(requireContext(), actionKey)
        return label ?: getString(R.string.pref_tap_not_configured)
    }

    // ── App picker ────────────────────────────────────────────────────────────

    private fun showAppPicker(def: TapActionHandler.ActionDef) {
        val ctx = requireContext()
        val pm  = ctx.packageManager

        val apps: List<ResolveInfo> = pm
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val labels = apps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle(def.displayName)
            .setItems(labels) { _, which ->
                val info      = apps[which].activityInfo
                val component = "${info.packageName}/${info.name}"
                TapActionHandler.setComponent(ctx, def.key, component)
                findPreference<Preference>("tap_action_${def.key}")?.summary = labels[which]
            }
            .setNeutralButton(R.string.pref_tap_clear) { _, _ ->
                TapActionHandler.setComponent(ctx, def.key, "")
                findPreference<Preference>("tap_action_${def.key}")?.summary =
                    getString(R.string.pref_tap_not_configured)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
