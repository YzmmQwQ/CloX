package com.ailife.clox.settings.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.ailife.clox.R
import com.ailife.clox.gesture.ButtonMappingPrefs
import com.ailife.clox.gesture.ButtonRecordDialogFragment
import com.ailife.clox.qs.QsPermissionHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class GesturesSettingsFragment : BaseSettingsFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_gestures, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val actionEntries = resources.getStringArray(R.array.gesture_action_entries)
        val actionValues  = resources.getStringArray(R.array.gesture_action_values)

        fun labelFor(value: String) = actionEntries.getOrElse(actionValues.indexOf(value)) { value }

        // Double-tap
        val tvDoubleTap = view.findViewById<TextView>(R.id.tvDoubleTap)
        fun refreshDoubleTap() {
            tvDoubleTap.text = labelFor(prefs.getString("double_tap_action", "screen_off") ?: "screen_off")
        }
        refreshDoubleTap()
        view.findViewById<View>(R.id.rowDoubleTap).setOnClickListener {
            showListPicker(getString(R.string.pref_double_tap_title), actionEntries, actionValues,
                prefs.getString("double_tap_action", "screen_off") ?: "screen_off") { v, _ ->
                prefs.edit().putString("double_tap_action", v).apply()
                refreshDoubleTap()
            }
        }

        // Long press
        val tvLongPress = view.findViewById<TextView>(R.id.tvLongPress)
        fun refreshLongPress() {
            tvLongPress.text = labelFor(prefs.getString("long_press_action", "watchface_selector") ?: "watchface_selector")
        }
        refreshLongPress()
        view.findViewById<View>(R.id.rowLongPress).setOnClickListener {
            showListPicker(getString(R.string.pref_long_press_title), actionEntries, actionValues,
                prefs.getString("long_press_action", "watchface_selector") ?: "watchface_selector") { v, _ ->
                prefs.edit().putString("long_press_action", v).apply()
                refreshLongPress()
            }
        }

        // Home press screen off
        val switchHome = view.findViewById<MaterialSwitch>(R.id.switchHomePressOff)
        switchHome.isChecked = prefs.getBoolean("home_press_screen_off", false)
        switchHome.setOnCheckedChangeListener { _, checked ->
            if (checked) QsPermissionHelper.ensureAccessibilityEnabled(requireContext())
            prefs.edit().putBoolean("home_press_screen_off", checked).apply()
        }

        // Button mapping
        val tvRecord = view.findViewById<TextView>(R.id.tvBtnRecord)
        val tvSingle = view.findViewById<TextView>(R.id.tvBtnSingle)
        val tvDouble = view.findViewById<TextView>(R.id.tvBtnDouble)
        val tvLong   = view.findViewById<TextView>(R.id.tvBtnLong)
        val rowSingle = view.findViewById<View>(R.id.rowBtnSingle)
        val rowDouble = view.findViewById<View>(R.id.rowBtnDouble)
        val rowLong   = view.findViewById<View>(R.id.rowBtnLong)
        val btnClear  = view.findViewById<MaterialButton>(R.id.btnClearBtnMap)

        fun refreshBtnState() {
            val ctx = requireContext()
            val keyCode = ButtonMappingPrefs.getMappedKeyCode(ctx)
            val hasButton = keyCode != 0
            val name = ButtonMappingPrefs.keyCodeToName(keyCode)
            tvRecord.text = if (hasButton) "Mapped: $name — tap to change" else getString(R.string.pref_btn_map_record_summary)
            val alpha = if (hasButton) 1f else 0.4f
            rowSingle.alpha = alpha; rowSingle.isEnabled = hasButton
            rowDouble.alpha = alpha; rowDouble.isEnabled = hasButton
            rowLong.alpha   = alpha; rowLong.isEnabled   = hasButton
            btnClear.visibility = if (hasButton) View.VISIBLE else View.GONE
            tvSingle.text = labelFor(prefs.getString(ButtonMappingPrefs.KEY_SINGLE, "nothing") ?: "nothing")
            tvDouble.text = labelFor(prefs.getString(ButtonMappingPrefs.KEY_DOUBLE, "nothing") ?: "nothing")
            tvLong.text   = labelFor(prefs.getString(ButtonMappingPrefs.KEY_LONG,   "nothing") ?: "nothing")
        }
        refreshBtnState()

        view.findViewById<View>(R.id.rowBtnRecord).setOnClickListener {
            val dialog = ButtonRecordDialogFragment()
            dialog.onKeyRecorded = { keyCode ->
                ButtonMappingPrefs.setMappedKeyCode(requireContext(), keyCode)
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putString(ButtonMappingPrefs.KEY_SINGLE, "nothing")
                    .putString(ButtonMappingPrefs.KEY_DOUBLE, "nothing")
                    .putString(ButtonMappingPrefs.KEY_LONG,   "nothing")
                    .apply()
                refreshBtnState()
            }
            dialog.show(childFragmentManager, ButtonRecordDialogFragment.TAG)
        }

        rowSingle.setOnClickListener {
            showListPicker(getString(R.string.pref_btn_single_title), actionEntries, actionValues,
                prefs.getString(ButtonMappingPrefs.KEY_SINGLE, "nothing") ?: "nothing") { v, _ ->
                prefs.edit().putString(ButtonMappingPrefs.KEY_SINGLE, v).apply()
                tvSingle.text = labelFor(v)
            }
        }
        rowDouble.setOnClickListener {
            showListPicker(getString(R.string.pref_btn_double_title), actionEntries, actionValues,
                prefs.getString(ButtonMappingPrefs.KEY_DOUBLE, "nothing") ?: "nothing") { v, _ ->
                prefs.edit().putString(ButtonMappingPrefs.KEY_DOUBLE, v).apply()
                tvDouble.text = labelFor(v)
            }
        }
        rowLong.setOnClickListener {
            showListPicker(getString(R.string.pref_btn_long_title), actionEntries, actionValues,
                prefs.getString(ButtonMappingPrefs.KEY_LONG, "nothing") ?: "nothing") { v, _ ->
                prefs.edit().putString(ButtonMappingPrefs.KEY_LONG, v).apply()
                tvLong.text = labelFor(v)
            }
        }
        btnClear.setOnClickListener {
            ButtonMappingPrefs.clearMappedKeyCode(requireContext())
            refreshBtnState()
        }
    }
}
