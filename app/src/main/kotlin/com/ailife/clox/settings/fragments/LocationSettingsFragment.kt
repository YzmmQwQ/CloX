package com.ailife.clox.settings.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.WeatherPrefs
import com.google.android.material.materialswitch.MaterialSwitch

class LocationSettingsFragment : BaseSettingsFragment() {

    private lateinit var tvLocationLat: TextView
    private lateinit var tvLocationLon: TextView
    private lateinit var tvLocationGps: TextView

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchGpsOnce()
        else Toast.makeText(requireContext(), R.string.pref_location_gps_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_location, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLocationLat = view.findViewById(R.id.tvLocationLat)
        tvLocationLon = view.findViewById(R.id.tvLocationLon)
        tvLocationGps = view.findViewById(R.id.tvLocationGps)

        val tzEntries = resources.getStringArray(R.array.timezone_offset_entries)
        val tzValues  = resources.getStringArray(R.array.timezone_offset_values)
        val coordInputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED

        // City name
        val tvCity = view.findViewById<TextView>(R.id.tvLocationCity)
        fun refreshCity() {
            val v = prefs.getString(WeatherPrefs.KEY_CITY, "") ?: ""
            tvCity.text = v.ifBlank { getString(R.string.pref_location_city_summary) }
        }
        refreshCity()
        view.findViewById<View>(R.id.rowLocationCity).setOnClickListener {
            showTextInputDialog(getString(R.string.pref_location_city_title),
                prefs.getString(WeatherPrefs.KEY_CITY, "") ?: "",
                hint = getString(R.string.pref_location_city_summary),
                inputType = InputType.TYPE_CLASS_TEXT
            ) { v ->
                prefs.edit().putString(WeatherPrefs.KEY_CITY, v).apply()
                refreshCity()
            }
        }

        // Latitude
        fun refreshLat() {
            val v = prefs.getString(WeatherPrefs.KEY_LAT, "") ?: ""
            tvLocationLat.text = v.ifBlank { getString(R.string.pref_location_lat_summary) }
        }
        refreshLat()
        view.findViewById<View>(R.id.rowLocationLat).setOnClickListener {
            showTextInputDialog(getString(R.string.pref_location_lat_title),
                prefs.getString(WeatherPrefs.KEY_LAT, "") ?: "",
                hint = getString(R.string.location_lat_hint),
                inputType = coordInputType
            ) { v ->
                val d = v.toDoubleOrNull()
                if (d == null || d < -90 || d > 90) {
                    Toast.makeText(requireContext(), R.string.pref_location_lat_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString(WeatherPrefs.KEY_LAT, v).apply()
                    refreshLat()
                }
            }
        }

        // Longitude
        fun refreshLon() {
            val v = prefs.getString(WeatherPrefs.KEY_LON, "") ?: ""
            tvLocationLon.text = v.ifBlank { getString(R.string.pref_location_lon_summary) }
        }
        refreshLon()
        view.findViewById<View>(R.id.rowLocationLon).setOnClickListener {
            showTextInputDialog(getString(R.string.pref_location_lon_title),
                prefs.getString(WeatherPrefs.KEY_LON, "") ?: "",
                hint = getString(R.string.location_lon_hint),
                inputType = coordInputType
            ) { v ->
                val d = v.toDoubleOrNull()
                if (d == null || d < -180 || d > 180) {
                    Toast.makeText(requireContext(), R.string.pref_location_lon_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString(WeatherPrefs.KEY_LON, v).apply()
                    refreshLon()
                }
            }
        }

        // GPS button
        tvLocationGps.text = getString(R.string.pref_location_gps_summary)
        view.findViewById<View>(R.id.rowLocationGps).setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) fetchGpsOnce()
            else locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Auto-update
        val switchAuto = view.findViewById<MaterialSwitch>(R.id.switchLocationAutoUpdate)
        switchAuto.isChecked = WeatherPrefs.autoUpdateLocation(requireContext())
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WeatherPrefs.KEY_AUTO_UPDATE_LOC, checked).apply()
        }

        // Extra timezone slots
        fun setupSlot(tvCity: TextView, rowCity: View, tvTz: TextView, rowTz: View,
                      cityKey: String, tzKey: String, cityLabel: String, tzLabel: String) {
            fun refreshSlotCity() {
                tvCity.text = prefs.getString(cityKey, "").orEmpty().ifBlank { getString(R.string.location_slot_placeholder) }
            }
            fun refreshSlotTz() {
                val v = prefs.getString(tzKey, "0") ?: "0"
                tvTz.text = tzEntries.getOrElse(tzValues.indexOf(v)) { v }
            }
            refreshSlotCity()
            refreshSlotTz()
            rowCity.setOnClickListener {
                showTextInputDialog(cityLabel, prefs.getString(cityKey, "") ?: "",
                    hint = getString(R.string.location_city_hint), inputType = InputType.TYPE_CLASS_TEXT) { v ->
                    prefs.edit().putString(cityKey, v).apply(); refreshSlotCity()
                }
            }
            rowTz.setOnClickListener {
                showListPicker(tzLabel, tzEntries, tzValues,
                    prefs.getString(tzKey, "0") ?: "0") { v, _ ->
                    prefs.edit().putString(tzKey, v).apply(); refreshSlotTz()
                }
            }
        }

        setupSlot(view.findViewById(R.id.tvSlot2City), view.findViewById(R.id.rowSlot2City),
            view.findViewById(R.id.tvSlot2Tz), view.findViewById(R.id.rowSlot2Tz),
            "location_name_2", "location_timezone_1",
            getString(R.string.pref_location_slot2_city), getString(R.string.pref_location_slot2_tz))

        setupSlot(view.findViewById(R.id.tvSlot3City), view.findViewById(R.id.rowSlot3City),
            view.findViewById(R.id.tvSlot3Tz), view.findViewById(R.id.rowSlot3Tz),
            "location_name_3", "location_timezone_2",
            getString(R.string.pref_location_slot3_city), getString(R.string.pref_location_slot3_tz))

        setupSlot(view.findViewById(R.id.tvSlot4City), view.findViewById(R.id.rowSlot4City),
            view.findViewById(R.id.tvSlot4Tz), view.findViewById(R.id.rowSlot4Tz),
            "location_name_4", "location_timezone_3",
            getString(R.string.pref_location_slot4_city), getString(R.string.pref_location_slot4_tz))

        setupSlot(view.findViewById(R.id.tvSlot5City), view.findViewById(R.id.rowSlot5City),
            view.findViewById(R.id.tvSlot5Tz), view.findViewById(R.id.rowSlot5Tz),
            "location_name_5", "location_timezone_4",
            getString(R.string.pref_location_slot5_city), getString(R.string.pref_location_slot5_tz))
    }

    private fun fetchGpsOnce() {
        val ctx = requireContext()
        val lm  = ctx.getSystemService(LocationManager::class.java)
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(ctx, R.string.pref_location_gps_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("MissingPermission")
        val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (last != null) { applyGpsResult(last.latitude, last.longitude); return }

        tvLocationGps.text = getString(R.string.pref_location_gps_waiting)
        @Suppress("MissingPermission")
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, object : LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                lm.removeUpdates(this)
                applyGpsResult(loc.latitude, loc.longitude)
                tvLocationGps.text = getString(R.string.pref_location_gps_summary)
            }
        })
    }

    private fun applyGpsResult(lat: Double, lon: Double) {
        val ctx = requireContext()
        WeatherPrefs.savePrimaryLocation(ctx, lat, lon, WeatherPrefs.city(ctx))
        tvLocationLat.text = "%.6f".format(lat)
        tvLocationLon.text = "%.6f".format(lon)
        Toast.makeText(ctx, "%.4f, %.4f".format(lat, lon), Toast.LENGTH_SHORT).show()
    }
}
