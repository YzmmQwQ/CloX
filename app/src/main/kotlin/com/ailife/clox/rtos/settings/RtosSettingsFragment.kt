package com.ailife.clox.rtos.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.ailife.clox.R
import com.ailife.clox.qs.CloXAccessibilityService
import com.ailife.clox.qs.QsPermissionHelper
import com.ailife.clox.rtos.RtosPrefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class RtosSettingsFragment : Fragment() {

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var tvRtosAccessibilityStatus: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_rtos, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }

        switchEnabled            = view.findViewById(R.id.switchRtosEnabled)
        tvRtosAccessibilityStatus = view.findViewById(R.id.tvRtosAccessibilityStatus)

        val rtosPrefs = RtosPrefs.get(requireContext())
        val modeEntries     = resources.getStringArray(R.array.rtos_mode_entries)
        val modeValues      = resources.getStringArray(R.array.rtos_mode_values)
        val passwordEntries = resources.getStringArray(R.array.rtos_password_entries)
        val passwordValues  = resources.getStringArray(R.array.rtos_password_values)

        // Enable switch
        switchEnabled.isChecked = RtosPrefs.isEnabled(requireContext())
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && RtosPrefs.passwordHash(requireContext()) == null) {
                Toast.makeText(requireContext(), R.string.toast_rtos_set_password_first, Toast.LENGTH_SHORT).show()
                switchEnabled.isChecked = false
                return@setOnCheckedChangeListener
            }
            RtosPrefs.setEnabled(requireContext(), checked)
        }

        // Mode picker
        val tvMode = view.findViewById<TextView>(R.id.tvRtosMode)
        fun refreshMode() {
            val v = rtosPrefs.getString(RtosPrefs.KEY_MODE, RtosPrefs.MODE_WATCHFACE_ONLY) ?: RtosPrefs.MODE_WATCHFACE_ONLY
            tvMode.text = modeEntries.getOrElse(modeValues.indexOf(v)) { v }
        }
        refreshMode()
        view.findViewById<View>(R.id.rowRtosMode).setOnClickListener {
            showListPicker(getString(R.string.rtos_setting_mode_title), modeEntries, modeValues,
                rtosPrefs.getString(RtosPrefs.KEY_MODE, RtosPrefs.MODE_WATCHFACE_ONLY) ?: RtosPrefs.MODE_WATCHFACE_ONLY) { v, _ ->
                rtosPrefs.edit().putString(RtosPrefs.KEY_MODE, v).apply()
                refreshMode()
            }
        }

        // Auto-enter switch
        val switchAutoEnter = view.findViewById<MaterialSwitch>(R.id.switchRtosAutoEnter)
        switchAutoEnter.isChecked = RtosPrefs.autoEnter(requireContext())
        switchAutoEnter.setOnCheckedChangeListener { _, checked ->
            rtosPrefs.edit().putBoolean(RtosPrefs.KEY_AUTO_ENTER, checked).apply()
        }

        // Launcher icon switch
        val switchLauncherIcon = view.findViewById<MaterialSwitch>(R.id.switchRtosLauncherIcon)
        switchLauncherIcon.isChecked = rtosPrefs.getBoolean(RtosPrefs.KEY_SHOW_LAUNCHER_ICON, false)
        switchLauncherIcon.setOnCheckedChangeListener { _, checked ->
            rtosPrefs.edit().putBoolean(RtosPrefs.KEY_SHOW_LAUNCHER_ICON, checked).apply()
            setRtosEntryLauncherEnabled(checked)
        }

        // Password type picker
        val tvPasswordType = view.findViewById<TextView>(R.id.tvRtosPasswordType)
        fun refreshPasswordType() {
            val v = rtosPrefs.getString(RtosPrefs.KEY_PASSWORD_TYPE, RtosPrefs.PASSWORD_KEYPAD) ?: RtosPrefs.PASSWORD_KEYPAD
            tvPasswordType.text = passwordEntries.getOrElse(passwordValues.indexOf(v)) { v }
        }
        refreshPasswordType()
        view.findViewById<View>(R.id.rowRtosPasswordType).setOnClickListener {
            showListPicker(getString(R.string.rtos_setting_password_type_title), passwordEntries, passwordValues,
                rtosPrefs.getString(RtosPrefs.KEY_PASSWORD_TYPE, RtosPrefs.PASSWORD_KEYPAD) ?: RtosPrefs.PASSWORD_KEYPAD) { v, _ ->
                rtosPrefs.edit().putString(RtosPrefs.KEY_PASSWORD_TYPE, v).apply()
                RtosPrefs.setPassword(requireContext(), emptyList(), "")
                rtosPrefs.edit().remove(RtosPrefs.KEY_PASSWORD_HASH).remove(RtosPrefs.KEY_PASSWORD_SEQ).apply()
                RtosPrefs.setEnabled(requireContext(), false)
                switchEnabled.isChecked = false
                Toast.makeText(requireContext(), R.string.toast_rtos_password_cleared, Toast.LENGTH_LONG).show()
                refreshPasswordType()
            }
        }

        // Set password nav
        view.findViewById<View>(R.id.rowRtosSetPassword).setOnClickListener {
            startActivity(Intent(requireContext(), RtosPasswordSetupActivity::class.java))
        }

        // Whitelist nav
        view.findViewById<View>(R.id.rowRtosWhitelist).setOnClickListener {
            startActivity(Intent(requireContext(), RtosWhitelistActivity::class.java))
        }

        // Block apps switch
        val switchBlockApps = view.findViewById<MaterialSwitch>(R.id.switchRtosBlockApps)
        switchBlockApps.isChecked = RtosPrefs.blockApps(requireContext())
        switchBlockApps.setOnCheckedChangeListener { _, checked ->
            if (checked && !CloXAccessibilityService.isRunning()) {
                Toast.makeText(requireContext(), R.string.toast_rtos_accessibility_first, Toast.LENGTH_SHORT).show()
                QsPermissionHelper.ensureAccessibilityEnabled(requireContext())
                switchBlockApps.isChecked = false
                return@setOnCheckedChangeListener
            }
            rtosPrefs.edit().putBoolean(RtosPrefs.KEY_BLOCK_APPS, checked).apply()
        }

        // Accessibility
        view.findViewById<View>(R.id.rowRtosAccessibility).setOnClickListener {
            QsPermissionHelper.ensureAccessibilityEnabled(requireContext())
        }
    }

    override fun onResume() {
        super.onResume()
        val running = CloXAccessibilityService.isRunning()
        tvRtosAccessibilityStatus.text = getString(if (running) R.string.rtos_accessibility_enabled else R.string.rtos_accessibility_disabled)
    }

    private fun showListPicker(title: String, entries: Array<String>, values: Array<String>,
            currentValue: String, onSelect: (String, String) -> Unit) {
        val selected = values.indexOf(currentValue).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(entries, selected) { dialog, which ->
                onSelect(values[which], entries[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setRtosEntryLauncherEnabled(enabled: Boolean) {
        val ctx = requireContext()
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, "com.ailife.clox.rtos.RtosEntryLauncher"),
                state, PackageManager.DONT_KILL_APP)
        }
    }
}
