package com.ailife.clox.settings.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.update.OtaManager
import com.google.android.material.materialswitch.MaterialSwitch

class AboutFragment : BaseSettingsFragment() {

    private lateinit var otaManager: OtaManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        otaManager = OtaManager(requireContext())

        // Version
        val tvVersion = view.findViewById<TextView>(R.id.tvVersion)
        try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            tvVersion.text = "Version ${info.versionName}"
        } catch (_: Exception) {
            tvVersion.text = "Version unknown"
        }

        // Onyx project link
        view.findViewById<View>(R.id.rowOnyxProject).setOnClickListener {
            openUrl("https://codeberg.org/OnyxUI/OnyxUI")
        }

        // Developer links
        view.findViewById<View>(R.id.rowDevAilife).setOnClickListener {
            openUrl("https://gitlab.com/ailife8881")
        }
        view.findViewById<View>(R.id.rowDev4nf).setOnClickListener {
            openUrl("https://gitlab.com/Iroha9876")
        }

        // Auto-update switch
        val switchAutoUpdate = view.findViewById<MaterialSwitch>(R.id.switchAutoUpdate)
        switchAutoUpdate.isChecked = otaManager.isUpdateCheckEnabled()
        switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            otaManager.setUpdateCheckEnabled(checked)
        }

        // Check for updates
        view.findViewById<View>(R.id.rowCheckUpdate).setOnClickListener {
            otaManager.checkUpdate(silent = false)
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
