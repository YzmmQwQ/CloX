package com.ailife.clox.settings.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.WeatherPrefs

class LocationSettingsFragment : BasePreferenceFragment() {

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchGpsOnce()
        else Toast.makeText(requireContext(), R.string.pref_location_gps_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_location, rootKey)
        setupCoordInputTypes()
        setupCoordValidation()
        setupGpsButton()
    }

    private fun setupCoordInputTypes() {
        val coordType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        findPreference<EditTextPreference>(WeatherPrefs.KEY_LAT)?.setOnBindEditTextListener { it.inputType = coordType }
        findPreference<EditTextPreference>(WeatherPrefs.KEY_LON)?.setOnBindEditTextListener { it.inputType = coordType }
        for (i in 2..5) {
            findPreference<EditTextPreference>("location_name_$i")?.setOnBindEditTextListener { et ->
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
    }

    private fun setupCoordValidation() {
        findPreference<EditTextPreference>(WeatherPrefs.KEY_LAT)?.setOnPreferenceChangeListener { _, v ->
            val d = (v as String).toDoubleOrNull()
            if (d == null || d < -90 || d > 90) {
                Toast.makeText(requireContext(), R.string.pref_location_lat_invalid, Toast.LENGTH_SHORT).show()
                false
            } else true
        }
        findPreference<EditTextPreference>(WeatherPrefs.KEY_LON)?.setOnPreferenceChangeListener { _, v ->
            val d = (v as String).toDoubleOrNull()
            if (d == null || d < -180 || d > 180) {
                Toast.makeText(requireContext(), R.string.pref_location_lon_invalid, Toast.LENGTH_SHORT).show()
                false
            } else true
        }
    }

    private fun setupGpsButton() {
        findPreference<Preference>("location_get_gps")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                fetchGpsOnce()
            } else {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            true
        }
    }

    private fun fetchGpsOnce() {
        val ctx = requireContext()
        val lm  = ctx.getSystemService(LocationManager::class.java)

        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(ctx, R.string.pref_location_gps_disabled, Toast.LENGTH_SHORT).show()
            return
        }

        val gpsPref = findPreference<Preference>("location_get_gps")

        // Use last known location for an instant result if available
        @Suppress("MissingPermission")
        val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (last != null) {
            applyGpsResult(last.latitude, last.longitude)
            return
        }

        gpsPref?.summary = getString(R.string.pref_location_gps_waiting)
        @Suppress("MissingPermission")
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, object : LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                lm.removeUpdates(this)
                applyGpsResult(loc.latitude, loc.longitude)
                gpsPref?.summary = getString(R.string.pref_location_gps_summary)
            }
        })
    }

    private fun applyGpsResult(lat: Double, lon: Double) {
        val ctx = requireContext()
        WeatherPrefs.savePrimaryLocation(ctx, lat, lon, WeatherPrefs.city(ctx))
        // Sync EditTextPreference display values
        findPreference<EditTextPreference>(WeatherPrefs.KEY_LAT)?.text = "%.6f".format(lat)
        findPreference<EditTextPreference>(WeatherPrefs.KEY_LON)?.text = "%.6f".format(lon)
        Toast.makeText(ctx, "%.4f, %.4f".format(lat, lon), Toast.LENGTH_SHORT).show()
    }
}
