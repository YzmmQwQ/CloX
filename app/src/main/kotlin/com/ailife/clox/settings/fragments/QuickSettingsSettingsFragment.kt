package com.ailife.clox.settings.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.ailife.clox.R
import com.ailife.clox.overlay.global.GlobalOverlayPrefs
import com.ailife.clox.overlay.global.GlobalOverlayService
import com.ailife.clox.qs.CloXAccessibilityService
import com.ailife.clox.qs.QsPrefs
import com.ailife.clox.qs.privilege.PrivilegeLevel
import com.ailife.clox.qs.privilege.PrivilegeManager
import com.ailife.clox.settings.fragments.TileEditorFragment
import com.ailife.clox.util.safeStartActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class QuickSettingsSettingsFragment : BaseSettingsFragment() {

    private lateinit var tvQsPrivilegeStatus: TextView
    private lateinit var tvQsAccessibilityStatus: TextView
    private lateinit var tvQsColorConnected: TextView
    private lateinit var tvQsColorIdle: TextView
    private lateinit var tvQsColorDisconnected: TextView
    private lateinit var tvQsColorOff: TextView
    private lateinit var rowQsColorConnected: View
    private lateinit var rowQsColorIdle: View
    private lateinit var rowQsColorDisconnected: View
    private lateinit var rowQsColorOff: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_quick_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvQsPrivilegeStatus     = view.findViewById(R.id.tvQsPrivilegeStatus)
        tvQsAccessibilityStatus = view.findViewById(R.id.tvQsAccessibilityStatus)
        tvQsColorConnected      = view.findViewById(R.id.tvQsColorConnected)
        tvQsColorIdle           = view.findViewById(R.id.tvQsColorIdle)
        tvQsColorDisconnected   = view.findViewById(R.id.tvQsColorDisconnected)
        tvQsColorOff            = view.findViewById(R.id.tvQsColorOff)
        rowQsColorConnected     = view.findViewById(R.id.rowQsColorConnected)
        rowQsColorIdle          = view.findViewById(R.id.rowQsColorIdle)
        rowQsColorDisconnected  = view.findViewById(R.id.rowQsColorDisconnected)
        rowQsColorOff           = view.findViewById(R.id.rowQsColorOff)

        val tileRowEntries = resources.getStringArray(R.array.tile_rows_entries)
        val tileRowValues  = resources.getStringArray(R.array.tile_rows_values)
        val tileStyleEntries = resources.getStringArray(R.array.qs_tile_style_entries)
        val tileStyleValues  = resources.getStringArray(R.array.qs_tile_style_values)
        val privilegeEntries = resources.getStringArray(R.array.privilege_entries)
        val privilegeValues  = resources.getStringArray(R.array.privilege_values)

        // QS enabled
        val switchEnabled = view.findViewById<MaterialSwitch>(R.id.switchQsEnabled)
        switchEnabled.isChecked = prefs.getBoolean(QsPrefs.QS_ENABLED, true)
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(QsPrefs.QS_ENABLED, checked).apply()
        }

        // Sensitivity slider
        val sliderSens = view.findViewById<Slider>(R.id.sliderQsSensitivity)
        val tvSens     = view.findViewById<TextView>(R.id.tvQsSensitivity)
        val sensVal = prefs.getInt("qs_sensitivity", 60).toFloat()
        sliderSens.value = sensVal.coerceIn(20f, 200f); tvSens.text = "${sensVal.toInt()}"
        sliderSens.addOnChangeListener { _, value, _ ->
            tvSens.text = "${value.toInt()}"
            prefs.edit().putInt("qs_sensitivity", value.toInt()).apply()
        }

        // Tile rows picker
        val tvTileRows = view.findViewById<TextView>(R.id.tvQsTileRows)
        fun refreshTileRows() {
            val v = prefs.getString(QsPrefs.TILE_ROWS, "2") ?: "2"
            tvTileRows.text = tileRowEntries.getOrElse(tileRowValues.indexOf(v)) { v }
        }
        refreshTileRows()
        view.findViewById<View>(R.id.rowQsTileRows).setOnClickListener {
            showListPicker(getString(R.string.qs_tile_rows_title), tileRowEntries, tileRowValues,
                prefs.getString(QsPrefs.TILE_ROWS, "2") ?: "2") { v, _ ->
                prefs.edit().putString(QsPrefs.TILE_ROWS, v).apply()
                refreshTileRows()
            }
        }

        // Tile editor nav
        view.findViewById<View>(R.id.rowQsTileEditor).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, TileEditorFragment())
                .addToBackStack(null)
                .commit()
        }

        // Flashlight hardware
        val switchFlash = view.findViewById<MaterialSwitch>(R.id.switchQsFlashlightHw)
        switchFlash.isChecked = prefs.getBoolean(QsPrefs.FLASHLIGHT_HARDWARE, false)
        switchFlash.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(QsPrefs.FLASHLIGHT_HARDWARE, checked).apply()
        }

        // Tile style picker
        val tvTileStyle = view.findViewById<TextView>(R.id.tvQsTileStyle)
        fun refreshTileStyle() {
            val v = prefs.getString(QsPrefs.TILE_STYLE, QsPrefs.TILE_STYLE_STROKE) ?: QsPrefs.TILE_STYLE_STROKE
            tvTileStyle.text = tileStyleEntries.getOrElse(tileStyleValues.indexOf(v)) { v }
        }
        refreshTileStyle()
        view.findViewById<View>(R.id.rowQsTileStyle).setOnClickListener {
            showListPicker(getString(R.string.qs_setting_tile_style_title), tileStyleEntries, tileStyleValues,
                prefs.getString(QsPrefs.TILE_STYLE, QsPrefs.TILE_STYLE_STROKE) ?: QsPrefs.TILE_STYLE_STROKE) { v, _ ->
                prefs.edit().putString(QsPrefs.TILE_STYLE, v).apply()
                refreshTileStyle()
            }
        }

        // Color tiles switch
        val switchColorTiles = view.findViewById<MaterialSwitch>(R.id.switchQsColorTiles)
        switchColorTiles.isChecked = prefs.getBoolean(QsPrefs.COLORED_TILES, true)
        switchColorTiles.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(QsPrefs.COLORED_TILES, checked).apply()
        }

        // System color toggle
        val switchSystemColor = view.findViewById<MaterialSwitch>(R.id.switchQsSystemColor)
        switchSystemColor.isChecked = prefs.getBoolean(QsPrefs.USE_SYSTEM_COLOR, false)
        fun updateColorRowsEnabled(enabled: Boolean) {
            listOf(rowQsColorConnected, rowQsColorIdle, rowQsColorDisconnected, rowQsColorOff).forEach {
                it.alpha = if (enabled) 0.4f else 1f
                it.isEnabled = !enabled
            }
        }
        updateColorRowsEnabled(switchSystemColor.isChecked)
        switchSystemColor.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(QsPrefs.USE_SYSTEM_COLOR, checked).apply()
            updateColorRowsEnabled(checked)
        }

        // Color pickers
        tvQsColorConnected.text    = prefs.getString(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED) ?: QsPrefs.DEFAULT_COLOR_CONNECTED
        tvQsColorIdle.text         = prefs.getString(QsPrefs.COLOR_IDLE, QsPrefs.DEFAULT_COLOR_IDLE) ?: QsPrefs.DEFAULT_COLOR_IDLE
        tvQsColorDisconnected.text = prefs.getString(QsPrefs.COLOR_DISCONNECTED, QsPrefs.DEFAULT_COLOR_DISCONNECTED) ?: QsPrefs.DEFAULT_COLOR_DISCONNECTED
        tvQsColorOff.text          = prefs.getString(QsPrefs.COLOR_OFF, QsPrefs.DEFAULT_COLOR_OFF) ?: QsPrefs.DEFAULT_COLOR_OFF

        rowQsColorConnected.setOnClickListener {
            showColorDialog(getString(R.string.qs_color_connected_title), QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED, tvQsColorConnected)
        }
        rowQsColorIdle.setOnClickListener {
            showColorDialog(getString(R.string.qs_color_idle_title), QsPrefs.COLOR_IDLE, QsPrefs.DEFAULT_COLOR_IDLE, tvQsColorIdle)
        }
        rowQsColorDisconnected.setOnClickListener {
            showColorDialog(getString(R.string.qs_color_disconnected_title), QsPrefs.COLOR_DISCONNECTED, QsPrefs.DEFAULT_COLOR_DISCONNECTED, tvQsColorDisconnected)
        }
        rowQsColorOff.setOnClickListener {
            showColorDialog(getString(R.string.qs_color_off_title), QsPrefs.COLOR_OFF, QsPrefs.DEFAULT_COLOR_OFF, tvQsColorOff)
        }

        // Privilege picker
        val tvPrivilege = view.findViewById<TextView>(R.id.tvQsPrivilege)
        fun refreshPrivilege() {
            val v = prefs.getString(QsPrefs.PRIVILEGE_MODE, "none") ?: "none"
            tvPrivilege.text = privilegeEntries.getOrElse(privilegeValues.indexOf(v)) { v }
        }
        refreshPrivilege()
        view.findViewById<View>(R.id.rowQsPrivilege).setOnClickListener {
            showListPicker(getString(R.string.qs_privilege_title), privilegeEntries, privilegeValues,
                prefs.getString(QsPrefs.PRIVILEGE_MODE, "none") ?: "none") { v, _ ->
                if (v == "shizuku") { PrivilegeManager.invalidate(); PrivilegeManager.requestShizukuPermission(99) }
                prefs.edit().putString(QsPrefs.PRIVILEGE_MODE, v).apply()
                PrivilegeManager.invalidate()
                refreshPrivilege()
                updatePrivilegeStatus()
            }
        }

        // Accessibility — clear the 13+ restricted-settings lock (via Shizuku/root) before opening.
        view.findViewById<View>(R.id.rowQsAccessibility).setOnClickListener {
            com.ailife.clox.qs.privilege.RestrictedSettingsHelper.openGrantScreen(
                requireContext(), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setupGlobalQs(view)
    }

    // ── Global Quick Settings (draw over other apps) ───────────────────────────
    private fun setupGlobalQs(view: View) {
        val switchGlobal  = view.findViewById<MaterialSwitch>(R.id.switchGlobalQs)
        val switchEdge    = view.findViewById<MaterialSwitch>(R.id.switchGlobalQsEdge)
        val switchReplace = view.findViewById<MaterialSwitch>(R.id.switchGlobalQsReplace)
        val sliderArea    = view.findViewById<Slider>(R.id.sliderGlobalQsArea)
        val tvArea        = view.findViewById<TextView>(R.id.tvGlobalQsArea)
        val tvSbMode      = view.findViewById<TextView>(R.id.tvGlobalQsStatusBar)

        val sbEntries = resources.getStringArray(R.array.status_bar_overlay_mode_entries)
        val sbValues  = resources.getStringArray(R.array.status_bar_overlay_mode_values)

        switchGlobal.isChecked = prefs.getBoolean(GlobalOverlayPrefs.GLOBAL_QS_ENABLED, false)
        switchGlobal.setOnCheckedChangeListener { _, checked ->
            if (checked && !Settings.canDrawOverlays(requireContext())) {
                switchGlobal.isChecked = false
                Toast.makeText(requireContext(), R.string.global_qs_permission_needed, Toast.LENGTH_LONG).show()
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")))
                }
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(GlobalOverlayPrefs.GLOBAL_QS_ENABLED, checked).apply()
            GlobalOverlayService.refresh(requireContext())
        }

        switchEdge.isChecked = prefs.getBoolean(GlobalOverlayPrefs.GLOBAL_QS_EDGE_SWIPE, true)
        switchEdge.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(GlobalOverlayPrefs.GLOBAL_QS_EDGE_SWIPE, checked).apply()
            GlobalOverlayService.refresh(requireContext())
        }

        switchReplace.isChecked = prefs.getBoolean(GlobalOverlayPrefs.GLOBAL_QS_AUTO_REPLACE, true)
        switchReplace.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(GlobalOverlayPrefs.GLOBAL_QS_AUTO_REPLACE, checked).apply()
            GlobalOverlayService.refresh(requireContext())
        }

        val areaVal = prefs.getInt(GlobalOverlayPrefs.GLOBAL_QS_EDGE_AREA, GlobalOverlayPrefs.DEFAULT_EDGE_AREA)
            .coerceIn(8, 120)
        sliderArea.value = areaVal.toFloat()
        tvArea.text = "${areaVal}dp"
        sliderArea.addOnChangeListener { _, value, fromUser ->
            tvArea.text = "${value.toInt()}dp"
            if (fromUser) {
                prefs.edit().putInt(GlobalOverlayPrefs.GLOBAL_QS_EDGE_AREA, value.toInt()).apply()
                GlobalOverlayService.refresh(requireContext())
            }
        }

        fun refreshSbMode() {
            val v = prefs.getString(GlobalOverlayPrefs.STATUS_BAR_OVERLAY_MODE, GlobalOverlayPrefs.SB_HIDDEN)
                ?: GlobalOverlayPrefs.SB_HIDDEN
            tvSbMode.text = sbEntries.getOrElse(sbValues.indexOf(v)) { v }
        }
        refreshSbMode()
        view.findViewById<View>(R.id.rowGlobalQsStatusBar).setOnClickListener {
            showListPicker(getString(R.string.status_bar_overlay_title), sbEntries, sbValues,
                prefs.getString(GlobalOverlayPrefs.STATUS_BAR_OVERLAY_MODE, GlobalOverlayPrefs.SB_HIDDEN)
                    ?: GlobalOverlayPrefs.SB_HIDDEN) { v, _ ->
                prefs.edit().putString(GlobalOverlayPrefs.STATUS_BAR_OVERLAY_MODE, v).apply()
                refreshSbMode()
                GlobalOverlayService.refresh(requireContext())
            }
        }

        setupStatusBarItems(view)
    }

    private fun setupStatusBarItems(view: View) {
        data class Item(val id: Int, val key: String, val def: Boolean)
        val items = listOf(
            Item(R.id.switchSbClock,     GlobalOverlayPrefs.SB_ITEM_CLOCK,     true),
            Item(R.id.switchSbBattery,   GlobalOverlayPrefs.SB_ITEM_BATTERY,   true),
            Item(R.id.switchSbWifi,      GlobalOverlayPrefs.SB_ITEM_WIFI,      true),
            Item(R.id.switchSbData,      GlobalOverlayPrefs.SB_ITEM_DATA,      true),
            Item(R.id.switchSbBluetooth, GlobalOverlayPrefs.SB_ITEM_BLUETOOTH, true),
            Item(R.id.switchSbSpeed,     GlobalOverlayPrefs.SB_ITEM_SPEED,     false),
            Item(R.id.switchSbNotifs,    GlobalOverlayPrefs.SB_ITEM_NOTIFS,    false),
        )
        items.forEach { item ->
            val sw = view.findViewById<MaterialSwitch>(item.id)
            sw.isChecked = prefs.getBoolean(item.key, item.def)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(item.key, checked).apply()
                GlobalOverlayService.refresh(requireContext())
            }
        }

        val sliderInset = view.findViewById<Slider>(R.id.sliderSbInset)
        val tvInset = view.findViewById<TextView>(R.id.tvSbInset)
        val insetVal = prefs.getInt(GlobalOverlayPrefs.SB_INSET_PCT, GlobalOverlayPrefs.DEFAULT_INSET_PCT).coerceIn(0, 40)
        sliderInset.value = insetVal.toFloat()
        tvInset.text = "$insetVal%"
        sliderInset.addOnChangeListener { _, value, fromUser ->
            tvInset.text = "${value.toInt()}%"
            if (fromUser) {
                prefs.edit().putInt(GlobalOverlayPrefs.SB_INSET_PCT, value.toInt()).apply()
                GlobalOverlayService.refresh(requireContext())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePrivilegeStatus()
        updateAccessibilityStatus()
    }

    private fun updatePrivilegeStatus() {
        val level = PrivilegeManager.level(requireContext())
        tvQsPrivilegeStatus.text = when (level) {
            PrivilegeLevel.SHIZUKU -> getString(R.string.qs_privilege_shizuku)
            PrivilegeLevel.ROOT    -> getString(R.string.qs_privilege_root)
            PrivilegeLevel.NONE    -> getString(R.string.qs_privilege_none)
        }
    }

    private fun updateAccessibilityStatus() {
        val running = CloXAccessibilityService.isRunning()
        tvQsAccessibilityStatus.text = if (running) getString(R.string.qs_accessibility_enabled)
                                       else getString(R.string.qs_accessibility_disabled)
    }

    private fun showColorDialog(title: String, key: String, default: String, tvColor: TextView) {
        showColorDialog(title, prefs.getString(key, default) ?: default) { value ->
            prefs.edit().putString(key, value).apply()
            tvColor.text = value
        }
    }
}
