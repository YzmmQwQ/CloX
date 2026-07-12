package com.ailife.clox.settings.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.update.UpdateController
import com.google.android.material.materialswitch.MaterialSwitch

class AboutFragment : BaseSettingsFragment() {

    private lateinit var updater: UpdateController

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updater = UpdateController(requireActivity())

        // Version
        val tvVersion = view.findViewById<TextView>(R.id.tvVersion)
        try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            tvVersion.text = getString(R.string.about_version, info.versionName)
        } catch (_: Exception) {
            tvVersion.text = getString(R.string.about_version_unknown)
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
        switchAutoUpdate.isChecked = updater.isUpdateCheckEnabled()
        switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            updater.setUpdateCheckEnabled(checked)
        }

        // Check for updates — status renders inline in the row (no toasts):
        // spinner while checking, then the result text replaces the subtitle.
        val tvSummary  = view.findViewById<TextView>(R.id.tvCheckUpdateSummary)
        val spinner    = view.findViewById<View>(R.id.progressCheckUpdate)
        val ivDownload = view.findViewById<View>(R.id.ivCheckUpdate)
        updater.onStatus = { state, text ->
            tvSummary.text = text
            val checking = state == UpdateController.CheckState.CHECKING
            spinner.visibility    = if (checking) View.VISIBLE else View.GONE
            ivDownload.visibility = if (checking) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.rowCheckUpdate).setOnClickListener {
            updater.check(userInitiated = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updater.onStatus = null
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
