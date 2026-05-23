package com.ailife.clox.settings.fragments

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import com.ailife.clox.R
import com.ailife.clox.watchface.WatchfaceBatchImportActivity
import com.ailife.clox.watchface.gallery.GalleryActivity
import com.ailife.clox.watchface.remote.RemoteManagementActivity
import com.ailife.clox.watchface.selector.WatchfaceSelectorActivity
import com.ailife.clox.watchface.store.WatchfaceStoreActivity

class WatchfaceSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_watchface, rootKey)

        findPreference<Preference>("nav_wf_open_selector")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), WatchfaceSelectorActivity::class.java))
            true
        }

        findPreference<Preference>("nav_wf_store")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), WatchfaceStoreActivity::class.java))
            true
        }

        findPreference<Preference>("nav_wf_remote")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), RemoteManagementActivity::class.java))
            true
        }

        findPreference<Preference>("nav_wf_import")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), WatchfaceBatchImportActivity::class.java))
            true
        }

        findPreference<Preference>("nav_wf_gallery")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), GalleryActivity::class.java))
            true
        }
    }
}
