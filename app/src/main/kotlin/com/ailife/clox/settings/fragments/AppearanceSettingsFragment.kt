package com.ailife.clox.settings.fragments

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.google.android.material.slider.Slider

class AppearanceSettingsFragment : BaseSettingsFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_appearance, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvDpi       = view.findViewById<TextView>(R.id.tvDpiValue)
        val sliderBlur  = view.findViewById<Slider>(R.id.sliderBlurRadius)
        val tvBlurMode  = view.findViewById<TextView>(R.id.tvBlurMode)
        val tvBlurRadius = view.findViewById<TextView>(R.id.tvBlurRadius)

        val blurModeEntries = resources.getStringArray(R.array.blur_mode_entries)
        val blurModeValues  = resources.getStringArray(R.array.blur_mode_values)

        // DPI override row
        fun refreshDpi() {
            val v = prefs.getString("app_dpi_override", "0") ?: "0"
            tvDpi.text = if (v.isEmpty() || v == "0") "0 (system default)" else v
        }
        refreshDpi()
        view.findViewById<View>(R.id.rowDpiOverride).setOnClickListener {
            showTextInputDialog("App display density (DPI)",
                prefs.getString("app_dpi_override", "0") ?: "0",
                hint = "0 = system default",
                inputType = InputType.TYPE_CLASS_NUMBER
            ) { raw ->
                prefs.edit().putString("app_dpi_override", raw).apply()
                refreshDpi()
                // Restart MainActivity so applyOverrideConfiguration picks up the new value
                activity?.let { act ->
                    val restart = act.packageManager
                        .getLaunchIntentForPackage(act.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    act.finish()
                    if (restart != null) act.startActivity(restart)
                }
            }
        }

        // Blur mode picker
        fun refreshBlurMode() {
            val v = prefs.getString("blur_mode", "BLURVIEW") ?: "BLURVIEW"
            tvBlurMode.text = blurModeEntries.getOrElse(blurModeValues.indexOf(v)) { v }
        }
        refreshBlurMode()
        view.findViewById<View>(R.id.rowBlurMode).setOnClickListener {
            showListPicker("Blur mode", blurModeEntries, blurModeValues,
                prefs.getString("blur_mode", "BLURVIEW") ?: "BLURVIEW") { v, _ ->
                prefs.edit().putString("blur_mode", v).apply()
                refreshBlurMode()
            }
        }

        // Blur radius slider
        val radius = prefs.getInt("blur_radius", 15).toFloat()
        sliderBlur.value = radius.coerceIn(2f, 25f)
        tvBlurRadius.text = "${radius.toInt()}"
        sliderBlur.addOnChangeListener { _, value, _ ->
            tvBlurRadius.text = "${value.toInt()}"
            prefs.edit().putInt("blur_radius", value.toInt()).apply()
        }
    }
}
