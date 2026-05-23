package com.ailife.clox.settings.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.watchface.WatchfaceBatchImportActivity
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.gallery.GalleryActivity
import com.ailife.clox.watchface.remote.RemoteManagementActivity
import com.ailife.clox.watchface.selector.WatchfaceSelectorActivity
import com.ailife.clox.watchface.store.WatchfaceStoreActivity
import com.google.android.material.materialswitch.MaterialSwitch

class WatchfaceSettingsFragment : BaseSettingsFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_watchface, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val selectorModeEntries = resources.getStringArray(R.array.wf_selector_mode_entries)
        val selectorModeValues  = resources.getStringArray(R.array.wf_selector_mode_values)
        val sortEntries         = resources.getStringArray(R.array.wf_sort_entries)
        val sortValues          = resources.getStringArray(R.array.wf_sort_values)
        val intervalEntries     = resources.getStringArray(R.array.wf_auto_interval_entries)
        val intervalValues      = resources.getStringArray(R.array.wf_auto_interval_values)

        fun labelFor(entries: Array<String>, values: Array<String>, value: String) =
            entries.getOrElse(values.indexOf(value)) { value }

        // Open selector card
        view.findViewById<View>(R.id.cardWfOpenSelector).setOnClickListener {
            startActivity(Intent(requireContext(), WatchfaceSelectorActivity::class.java))
        }

        // Selector mode picker
        val tvSelectorMode = view.findViewById<TextView>(R.id.tvWfSelectorMode)
        fun refreshSelectorMode() {
            tvSelectorMode.text = labelFor(selectorModeEntries, selectorModeValues,
                prefs.getString("wf_selector_mode", "pager") ?: "pager")
        }
        refreshSelectorMode()
        view.findViewById<View>(R.id.rowWfSelectorMode).setOnClickListener {
            showListPicker(getString(R.string.pref_wf_selector_mode_title),
                selectorModeEntries, selectorModeValues,
                prefs.getString("wf_selector_mode", "pager") ?: "pager") { v, _ ->
                prefs.edit().putString("wf_selector_mode", v).apply()
                refreshSelectorMode()
            }
        }

        // Sort order picker
        val tvSortOrder = view.findViewById<TextView>(R.id.tvWfSortOrder)
        fun refreshSortOrder() {
            tvSortOrder.text = labelFor(sortEntries, sortValues,
                prefs.getString("wf_sort_order", "name") ?: "name")
        }
        refreshSortOrder()
        view.findViewById<View>(R.id.rowWfSortOrder).setOnClickListener {
            showListPicker(getString(R.string.pref_wf_sort_title),
                sortEntries, sortValues,
                prefs.getString("wf_sort_order", "name") ?: "name") { v, _ ->
                prefs.edit().putString("wf_sort_order", v).apply()
                refreshSortOrder()
            }
        }

        // Auto change on screen on switch
        val switchAutoScreen = view.findViewById<MaterialSwitch>(R.id.switchWfAutoScreen)
        switchAutoScreen.isChecked = prefs.getBoolean(WatchfacePrefs.KEY_AUTO_CHANGE_ON_SCREEN_ON, false)
        switchAutoScreen.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WatchfacePrefs.KEY_AUTO_CHANGE_ON_SCREEN_ON, checked).apply()
        }

        // Auto change interval picker
        val tvAutoInterval = view.findViewById<TextView>(R.id.tvWfAutoInterval)
        fun refreshAutoInterval() {
            tvAutoInterval.text = labelFor(intervalEntries, intervalValues,
                prefs.getString(WatchfacePrefs.KEY_AUTO_CHANGE_INTERVAL, "none") ?: "none")
        }
        refreshAutoInterval()
        view.findViewById<View>(R.id.rowWfAutoInterval).setOnClickListener {
            showListPicker(getString(R.string.pref_wf_auto_interval_title),
                intervalEntries, intervalValues,
                prefs.getString(WatchfacePrefs.KEY_AUTO_CHANGE_INTERVAL, "none") ?: "none") { v, _ ->
                prefs.edit().putString(WatchfacePrefs.KEY_AUTO_CHANGE_INTERVAL, v).apply()
                refreshAutoInterval()
            }
        }

        // Management rows
        view.findViewById<View>(R.id.rowWfStore).setOnClickListener {
            startActivity(Intent(requireContext(), WatchfaceStoreActivity::class.java))
        }
        view.findViewById<View>(R.id.rowWfRemote).setOnClickListener {
            startActivity(Intent(requireContext(), RemoteManagementActivity::class.java))
        }
        view.findViewById<View>(R.id.rowWfImport).setOnClickListener {
            startActivity(Intent(requireContext(), WatchfaceBatchImportActivity::class.java))
        }
        view.findViewById<View>(R.id.rowWfGallery).setOnClickListener {
            startActivity(Intent(requireContext(), GalleryActivity::class.java))
        }
    }
}
