package com.ailife.clox.settings.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.SensorsPrefs
import com.google.android.material.materialswitch.MaterialSwitch

class SensorsSettingsFragment : BaseSettingsFragment() {

    private lateinit var sensorManager: SensorManager

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            SensorsPrefs.setCalendarEnabled(requireContext(), true)
            view?.findViewById<MaterialSwitch>(R.id.switchCalendar)?.isChecked = true
        } else {
            val canAsk = shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
            if (canAsk) {
                Toast.makeText(requireContext(), R.string.pref_calendar_permission_denied, Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_calendar_permission_denied)
                    .setMessage(R.string.dialog_calendar_perm_message)
                    .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", requireContext().packageName, null)
                        })
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_sensors, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sensorManager = requireContext().getSystemService(SensorManager::class.java)

        setupCalendar(view)
        setupSensor(view, R.id.switchAccelerometer, R.id.tvAccelerometerSummary,
            SensorsPrefs.KEY_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER)
        setupSensor(view, R.id.switchCompass, R.id.tvCompassSummary,
            SensorsPrefs.KEY_COMPASS, Sensor.TYPE_MAGNETIC_FIELD)
        setupSensor(view, R.id.switchBarometer, R.id.tvBarometerSummary,
            SensorsPrefs.KEY_BAROMETER, Sensor.TYPE_PRESSURE)
        setupSensor(view, R.id.switchGyroscope, R.id.tvGyroscopeSummary,
            SensorsPrefs.KEY_GYROSCOPE, Sensor.TYPE_GYROSCOPE)
    }

    private fun setupCalendar(view: View) {
        val sw = view.findViewById<MaterialSwitch>(R.id.switchCalendar)
        sw.isChecked = prefs.getBoolean(SensorsPrefs.KEY_CALENDAR, false)
        sw.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                prefs.edit().putBoolean(SensorsPrefs.KEY_CALENDAR, false).apply()
                return@setOnCheckedChangeListener
            }
            val has = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
            if (has) {
                prefs.edit().putBoolean(SensorsPrefs.KEY_CALENDAR, true).apply()
            } else {
                sw.isChecked = false
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }
    }

    private fun setupSensor(view: View, switchId: Int, summaryId: Int, key: String, sensorType: Int) {
        val sw = view.findViewById<MaterialSwitch>(switchId)
        val tv = view.findViewById<TextView>(summaryId)
        val available = sensorManager.getDefaultSensor(sensorType) != null
        if (!available) {
            sw.isChecked = false
            sw.isEnabled = false
            tv.text = getString(R.string.pref_sensor_unavailable)
            if (prefs.getBoolean(key, false)) prefs.edit().putBoolean(key, false).apply()
            return
        }
        sw.isChecked = prefs.getBoolean(key, false)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }
}
