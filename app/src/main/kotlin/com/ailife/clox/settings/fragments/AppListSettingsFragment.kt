package com.ailife.clox.settings.fragments

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.ailife.clox.R
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.icon.IconPackLoader

class AppListSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = AppListPrefs.PREFS_NAME
        setPreferencesFromResource(R.xml.preferences_app_list, rootKey)

        val currentStyle = AppListPrefs.style(requireContext())
        updateVisibility(currentStyle)
        updateSortHint(AppListPrefs.sort(requireContext()))
        populateIconPacks()

        // React to style changes
        findPreference<ListPreference>("style")?.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility(newValue as String)
            true
        }

        // React to sort changes
        findPreference<ListPreference>("sort")?.setOnPreferenceChangeListener { _, newValue ->
            updateSortHint(newValue as String)
            true
        }
    }

    // ── Show/hide style sub-settings ─────────────────────────────────────────

    private val styleCategories = mapOf(
        AppListPrefs.STYLE_LIST      to "cat_list",
        AppListPrefs.STYLE_GRID      to "cat_grid",
        AppListPrefs.STYLE_WATERFALL to "cat_waterfall",
        AppListPrefs.STYLE_HONEYCOMB to "cat_honeycomb",
        AppListPrefs.STYLE_GRID2D    to "cat_grid2d",
        AppListPrefs.STYLE_PAGES     to "cat_pages"
    )

    private fun updateVisibility(activeStyle: String) {
        styleCategories.forEach { (style, key) ->
            findPreference<PreferenceCategory>(key)?.isVisible = (style == activeStyle)
        }
    }

    // ── Sort hint ─────────────────────────────────────────────────────────────

    private fun updateSortHint(sort: String) {
        findPreference<Preference>("sort_custom_hint")?.isVisible =
            (sort == AppListPrefs.SORT_CUSTOM)
    }

    // ── Icon pack list ────────────────────────────────────────────────────────

    private fun populateIconPacks() {
        val pref = findPreference<ListPreference>("icon_pack") ?: return
        val packs = IconPackLoader.getInstalledPacks(requireContext())
        val entries = mutableListOf<CharSequence>(getString(R.string.pref_icon_pack_none))
        val values  = mutableListOf<CharSequence>("")
        packs.forEach { (pkg, label) -> entries.add(label); values.add(pkg) }
        pref.entries = entries.toTypedArray()
        pref.entryValues = values.toTypedArray()

        pref.setOnPreferenceChangeListener { _, newValue ->
            IconPackLoader.setSelectedPack(requireContext(), (newValue as String).ifEmpty { null })
            true
        }
    }
}
