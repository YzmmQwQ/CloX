package com.ailife.clox.settings.fragments

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.ailife.clox.debug.DebugPrefs
import com.ailife.clox.debug.LauncherMaintenance
import com.ailife.clox.debug.SettingsBackupManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DebugSettingsFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "DebugSettingsFragment"
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) doExport(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport(uri)
    }

    private lateinit var tvBackupStatus: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_debug, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switchIgnoreLuaErrors = view.findViewById<MaterialSwitch>(R.id.switchIgnoreLuaErrors)
        val btnExportSettings = view.findViewById<View>(R.id.btnExportSettings)
        val btnImportSettings = view.findViewById<View>(R.id.btnImportSettings)
        val btnForceStop = view.findViewById<View>(R.id.btnForceStop)
        val btnUninstall = view.findViewById<View>(R.id.btnUninstall)
        tvBackupStatus = view.findViewById(R.id.tvBackupStatus)

        switchIgnoreLuaErrors.isChecked = prefs.getBoolean(DebugPrefs.KEY_IGNORE_LUA_ERRORS, false)
        switchIgnoreLuaErrors.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(DebugPrefs.KEY_IGNORE_LUA_ERRORS, checked).apply()
        }

        btnExportSettings.setOnClickListener {
            exportLauncher.launch("clox_settings_backup.json")
        }
        btnImportSettings.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        btnForceStop.setOnClickListener { confirmForceStop() }
        btnUninstall.setOnClickListener { confirmUninstall() }
    }

    private fun doExport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { SettingsBackupManager.export(requireContext(), uri) }
            Log.i(TAG, "settings export to $uri: ${if (ok) "ok" else "failed"}")
            showBackupStatus(if (ok) getString(R.string.debug_export_success) else getString(R.string.debug_export_failed))
        }
    }

    private fun confirmImport(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.debug_import_settings_title)
            .setMessage(R.string.debug_import_settings_summary)
            .setPositiveButton(android.R.string.ok) { _, _ -> doImport(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doImport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { SettingsBackupManager.import(requireContext(), uri) }
            Log.i(TAG, "settings import from $uri: ${if (ok) "ok" else "failed"}")
            if (ok) {
                showBackupStatus(getString(R.string.debug_import_success))
                tvBackupStatus.postDelayed({ LauncherMaintenance.forceStopAndRelaunch() }, 1200)
            } else {
                showBackupStatus(getString(R.string.debug_import_failed))
            }
        }
    }

    private fun showBackupStatus(message: String) {
        tvBackupStatus.text = message
        tvBackupStatus.visibility = View.VISIBLE
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun confirmForceStop() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.debug_force_stop_confirm_title)
            .setMessage(R.string.debug_force_stop_confirm_message)
            .setPositiveButton(R.string.debug_force_stop_btn) { _, _ -> LauncherMaintenance.forceStopAndRelaunch() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmUninstall() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.debug_uninstall_confirm_title)
            .setMessage(R.string.debug_uninstall_confirm_message)
            .setPositiveButton(R.string.debug_uninstall_btn) { _, _ -> LauncherMaintenance.uninstall(requireContext()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
