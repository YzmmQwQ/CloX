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

        val switchOpenAnim   = view.findViewById<MaterialSwitch>(R.id.switchOpenAnim)
        val rowOpenAnimType  = view.findViewById<View>(R.id.rowOpenAnimType)
        val tvOpenAnimType   = view.findViewById<TextView>(R.id.tvOpenAnimType)
        val switchReturnAnim = view.findViewById<MaterialSwitch>(R.id.switchReturnAnim)
        val tvReturnSummary  = view.findViewById<TextView>(R.id.tvReturnAnimSummary)
        val tvAnimSpeed      = view.findViewById<TextView>(R.id.tvAnimSpeed)
        val sliderAnimSpeed  = view.findViewById<Slider>(R.id.sliderAnimSpeed)

        val typeEntries = resources.getStringArray(R.array.open_anim_type_entries)
        val typeValues  = resources.getStringArray(R.array.open_anim_type_values)

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

        // ── Animation style picker ───────────────────────────────────────────
        fun refreshType() {
            val v = appListPrefs.getString(AppListPrefs.KEY_OPEN_ANIM_TYPE, AppListPrefs.OPEN_ANIM_ICON_ZOOM)
                ?: AppListPrefs.OPEN_ANIM_ICON_ZOOM
            tvOpenAnimType.text = labelFor(typeEntries, typeValues, v)
        }
        refreshType()

        rowOpenAnimType.setOnClickListener {
            val current = appListPrefs.getString(AppListPrefs.KEY_OPEN_ANIM_TYPE, AppListPrefs.OPEN_ANIM_ICON_ZOOM)
                ?: AppListPrefs.OPEN_ANIM_ICON_ZOOM
            showListPicker(getString(R.string.anim_setting_open_type_title), typeEntries, typeValues, current) { v, _ ->
                appListPrefs.edit().putString(AppListPrefs.KEY_OPEN_ANIM_TYPE, v).apply()
                refreshType()
            }
        }

        // ── Return animation switch ──────────────────────────────────────────
        tvReturnSummary.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            getString(R.string.anim_setting_return_summary_11plus)
        else
            getString(R.string.anim_setting_return_summary_pre11)

        switchReturnAnim.isChecked = appListPrefs.getBoolean(AppListPrefs.KEY_RETURN_ANIM_ENABLED, true)
        switchReturnAnim.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean(AppListPrefs.KEY_RETURN_ANIM_ENABLED, checked).apply()
        }

        // ── Animation speed slider ───────────────────────────────────────────
        val speedVal = appListPrefs.getFloat(AppListPrefs.KEY_ANIM_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
        sliderAnimSpeed.value = speedVal
        tvAnimSpeed.text = String.format("%.1f×", speedVal)

        sliderAnimSpeed.addOnChangeListener { _, value, _ ->
            tvAnimSpeed.text = String.format("%.1f×", value)
            appListPrefs.edit().putFloat(AppListPrefs.KEY_ANIM_SPEED, value).apply()
        }
    }
}
