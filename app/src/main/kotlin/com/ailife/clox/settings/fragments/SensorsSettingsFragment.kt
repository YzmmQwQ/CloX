package com.ailife.clox.settings.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.SensorsPrefs

class SensorsSettingsFragment : PreferenceFragmentCompat() {

    private lateinit var sensorManager: SensorManager

    // Launched when the user turns on calendar access and permission isn't yet granted.
    // On result: grant → save pref + flip switch on; deny → show rationale or redirect to settings.
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            SensorsPrefs.setCalendarEnabled(requireContext(), true)
            findPreference<SwitchPreferenceCompat>(SensorsPrefs.KEY_CALENDAR)?.isChecked = true
        } else {
            val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
            if (canAskAgain) {
                Toast.makeText(requireContext(), R.string.pref_calendar_permission_denied, Toast.LENGTH_SHORT).show()
            } else {
                // Permanently denied — direct user to app settings
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_calendar_permission_denied)
                    .setMessage("Calendar permission was denied. Please enable it in App Settings > Permissions.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", requireContext().packageName, null)
                        })
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_sensors, rootKey)
        sensorManager = requireContext().getSystemService(SensorManager::class.java)

        setupCalendar()
        setupSensor(SensorsPrefs.KEY_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER, R.string.pref_sensor_unavailable)
        setupSensor(SensorsPrefs.KEY_COMPASS,       Sensor.TYPE_MAGNETIC_FIELD, R.string.pref_sensor_unavailable)
        setupSensor(SensorsPrefs.KEY_BAROMETER,     Sensor.TYPE_PRESSURE,       R.string.pref_sensor_unavailable)
    }

    // ── Calendar ─────────────────────────────────────────────────────────────

    private fun setupCalendar() {
        val pref = findPreference<SwitchPreferenceCompat>(SensorsPrefs.KEY_CALENDAR) ?: return

        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as Boolean
            if (!enabling) return@setOnPreferenceChangeListener true   // disabling always allowed

            val hasPermission = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                true  // save immediately
            } else {
                // Don't save yet; save only after permission is granted (see launcher above)
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                false
            }
        }
    }

    // ── Hardware sensors ──────────────────────────────────────────────────────

    private fun setupSensor(prefKey: String, sensorType: Int, unavailableStringRes: Int) {
        val pref = findPreference<SwitchPreferenceCompat>(prefKey) ?: return
        val available = sensorManager.getDefaultSensor(sensorType) != null

        if (!available) {
            // Device doesn't have this sensor — lock it off
            pref.isChecked = false
            pref.isEnabled = false
            pref.summary = getString(unavailableStringRes)
            // Clear any previously saved true value so SensorHelper won't see it as enabled
            if (pref.sharedPreferences?.getBoolean(prefKey, false) == true) {
                pref.sharedPreferences?.edit()?.putBoolean(prefKey, false)?.apply()
            }
            return
        }

        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as Boolean
            if (!enabling) return@setOnPreferenceChangeListener true

            // Re-check at the moment of enabling (belt-and-suspenders)
            sensorManager.getDefaultSensor(sensorType) != null
        }
    }
}
