package com.ailife.clox.settings.fragments

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.applist.AppListPrefs
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class AnimationSettingsFragment : BaseSettingsFragment() {

    private val appListPrefs by lazy { namedPrefs(AppListPrefs.PREFS_NAME) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_animation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switchOpenAnim      = view.findViewById<MaterialSwitch>(R.id.switchOpenAnim)
        val rowOpenAnimType     = view.findViewById<View>(R.id.rowOpenAnimType)
        val tvOpenAnimType      = view.findViewById<TextView>(R.id.tvOpenAnimType)
        val switchReturnAnim    = view.findViewById<MaterialSwitch>(R.id.switchReturnAnim)
        val tvReturnSummary     = view.findViewById<TextView>(R.id.tvReturnAnimSummary)
        val rowCloseAnimType    = view.findViewById<View>(R.id.rowCloseAnimType)
        val tvCloseAnimType     = view.findViewById<TextView>(R.id.tvCloseAnimType)
        val tvAnimSpeed         = view.findViewById<TextView>(R.id.tvAnimSpeed)
        val sliderAnimSpeed     = view.findViewById<Slider>(R.id.sliderAnimSpeed)
        val tvCloseAnimSpeed     = view.findViewById<TextView>(R.id.tvCloseAnimSpeed)
        val sliderCloseAnimSpeed = view.findViewById<Slider>(R.id.sliderCloseAnimSpeed)
        val tvCloseAnimDelay     = view.findViewById<TextView>(R.id.tvCloseAnimDelay)
        val sliderCloseAnimDelay = view.findViewById<Slider>(R.id.sliderCloseAnimDelay)

        val openTypeEntries  = resources.getStringArray(R.array.open_anim_type_entries)
        val openTypeValues   = resources.getStringArray(R.array.open_anim_type_values)
        val closeTypeEntries = resources.getStringArray(R.array.close_anim_type_entries)
        val closeTypeValues  = resources.getStringArray(R.array.close_anim_type_values)

        fun labelFor(entries: Array<String>, values: Array<String>, v: String) =
            entries.getOrElse(values.indexOf(v)) { v }

        // ── Open animation switch ────────────────────────────────────────────
        fun isOpenAnimOn() =
            appListPrefs.getString(AppListPrefs.KEY_LAUNCH_ANIM, AppListPrefs.LAUNCH_ANIM_SYSTEM) ==
                AppListPrefs.LAUNCH_ANIM_CUSTOM

        switchOpenAnim.isChecked = isOpenAnimOn()
        rowOpenAnimType.visibility = if (switchOpenAnim.isChecked) View.VISIBLE else View.GONE

        switchOpenAnim.setOnCheckedChangeListener { _, checked ->
            val value = if (checked) AppListPrefs.LAUNCH_ANIM_CUSTOM else AppListPrefs.LAUNCH_ANIM_SYSTEM
            appListPrefs.edit().putString(AppListPrefs.KEY_LAUNCH_ANIM, value).apply()
            rowOpenAnimType.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // ── Open animation style picker ──────────────────────────────────────
        fun refreshOpenType() {
            val v = appListPrefs.getString(AppListPrefs.KEY_OPEN_ANIM_TYPE, AppListPrefs.OPEN_ANIM_ICON_ZOOM)
                ?: AppListPrefs.OPEN_ANIM_ICON_ZOOM
            tvOpenAnimType.text = labelFor(openTypeEntries, openTypeValues, v)
        }
        refreshOpenType()

        rowOpenAnimType.setOnClickListener {
            val current = appListPrefs.getString(AppListPrefs.KEY_OPEN_ANIM_TYPE, AppListPrefs.OPEN_ANIM_ICON_ZOOM)
                ?: AppListPrefs.OPEN_ANIM_ICON_ZOOM
            showListPicker(getString(R.string.anim_setting_open_type_title), openTypeEntries, openTypeValues, current) { v, _ ->
                appListPrefs.edit().putString(AppListPrefs.KEY_OPEN_ANIM_TYPE, v).apply()
                refreshOpenType()
            }
        }

        // ── Return animation switch + close style picker ─────────────────────
        tvReturnSummary.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            getString(R.string.anim_setting_return_summary_11plus)
        else
            getString(R.string.anim_setting_return_summary_pre11)

        val returnOn = appListPrefs.getBoolean(AppListPrefs.KEY_RETURN_ANIM_ENABLED, true)
        switchReturnAnim.isChecked = returnOn
        rowCloseAnimType.visibility = if (returnOn) View.VISIBLE else View.GONE

        switchReturnAnim.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean(AppListPrefs.KEY_RETURN_ANIM_ENABLED, checked).apply()
            rowCloseAnimType.visibility = if (checked) View.VISIBLE else View.GONE
        }

        fun refreshCloseType() {
            val v = appListPrefs.getString(AppListPrefs.KEY_CLOSE_ANIM_TYPE, AppListPrefs.CLOSE_ANIM_ICON_ZOOM)
                ?: AppListPrefs.CLOSE_ANIM_ICON_ZOOM
            tvCloseAnimType.text = labelFor(closeTypeEntries, closeTypeValues, v)
        }
        refreshCloseType()

        rowCloseAnimType.setOnClickListener {
            val current = appListPrefs.getString(AppListPrefs.KEY_CLOSE_ANIM_TYPE, AppListPrefs.CLOSE_ANIM_ICON_ZOOM)
                ?: AppListPrefs.CLOSE_ANIM_ICON_ZOOM
            showListPicker(getString(R.string.anim_setting_close_type_title), closeTypeEntries, closeTypeValues, current) { v, _ ->
                appListPrefs.edit().putString(AppListPrefs.KEY_CLOSE_ANIM_TYPE, v).apply()
                refreshCloseType()
            }
        }

        // ── Open animation speed slider ──────────────────────────────────────
        val speedVal = appListPrefs.getFloat(AppListPrefs.KEY_ANIM_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
        sliderAnimSpeed.value = speedVal
        tvAnimSpeed.text = String.format("%.1f×", speedVal)

        sliderAnimSpeed.addOnChangeListener { _, value, _ ->
            tvAnimSpeed.text = String.format("%.1f×", value)
            appListPrefs.edit().putFloat(AppListPrefs.KEY_ANIM_SPEED, value).apply()
        }

        // ── Close animation speed slider ─────────────────────────────────────
        val closeSpeedVal = appListPrefs.getFloat(AppListPrefs.KEY_CLOSE_ANIM_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
        sliderCloseAnimSpeed.value = closeSpeedVal
        tvCloseAnimSpeed.text = String.format("%.1f×", closeSpeedVal)

        sliderCloseAnimSpeed.addOnChangeListener { _, value, _ ->
            tvCloseAnimSpeed.text = String.format("%.1f×", value)
            appListPrefs.edit().putFloat(AppListPrefs.KEY_CLOSE_ANIM_SPEED, value).apply()
        }

        // ── Close animation delay slider ──────────────────────────────────────
        val closeDelayVal = appListPrefs.getInt(AppListPrefs.KEY_CLOSE_ANIM_DELAY, 50)
            .coerceIn(0, 500).toFloat()
        sliderCloseAnimDelay.value = closeDelayVal
        tvCloseAnimDelay.text = "${closeDelayVal.toInt()} ms"

        sliderCloseAnimDelay.addOnChangeListener { _, value, _ ->
            tvCloseAnimDelay.text = "${value.toInt()} ms"
            appListPrefs.edit().putInt(AppListPrefs.KEY_CLOSE_ANIM_DELAY, value.toInt()).apply()
        }
    }
}
