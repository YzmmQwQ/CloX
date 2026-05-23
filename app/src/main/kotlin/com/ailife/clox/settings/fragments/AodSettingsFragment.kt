package com.ailife.clox.settings.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.aod.AodPrefs
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class AodSettingsFragment : BaseSettingsFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_aod, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switchEnabled   = view.findViewById<MaterialSwitch>(R.id.switchAodEnabled)
        val cardDisplay     = view.findViewById<View>(R.id.cardAodDisplay)
        val cardBehavior    = view.findViewById<View>(R.id.cardAodBehavior)
        val sliderTimeout   = view.findViewById<Slider>(R.id.sliderAodTimeout)
        val tvTimeout       = view.findViewById<TextView>(R.id.tvAodTimeout)
        val sliderBrightness = view.findViewById<Slider>(R.id.sliderAodBrightness)
        val tvBrightness    = view.findViewById<TextView>(R.id.tvAodBrightness)
        val switchReawaken  = view.findViewById<MaterialSwitch>(R.id.switchAodReawaken)
        val switchLockGesture = view.findViewById<MaterialSwitch>(R.id.switchAodLockGesture)
        val rowLockDelay    = view.findViewById<View>(R.id.rowAodLockDelay)
        val sliderLockDelay = view.findViewById<Slider>(R.id.sliderAodLockDelay)
        val tvLockDelay     = view.findViewById<TextView>(R.id.tvAodLockDelay)
        val switchShallow   = view.findViewById<MaterialSwitch>(R.id.switchAodShallowSleep)
        val switchPowerExit = view.findViewById<MaterialSwitch>(R.id.switchAodPowerExit)

        fun updateDependentVisibility(enabled: Boolean) {
            val alpha = if (enabled) 1f else 0.4f
            cardDisplay.alpha = alpha
            cardDisplay.isEnabled = enabled
            cardBehavior.alpha = alpha
            cardBehavior.isEnabled = enabled
        }

        // Master switch
        val enabled = prefs.getBoolean(AodPrefs.KEY_ENABLED, false)
        switchEnabled.isChecked = enabled
        updateDependentVisibility(enabled)
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AodPrefs.KEY_ENABLED, checked).apply()
            updateDependentVisibility(checked)
        }

        // Timeout slider
        val timeout = prefs.getInt(AodPrefs.KEY_TIMEOUT_SECONDS, AodPrefs.DEFAULT_TIMEOUT_SECONDS).toFloat()
        sliderTimeout.value = timeout.coerceIn(5f, 300f)
        tvTimeout.text = "${timeout.toInt()}s"
        sliderTimeout.addOnChangeListener { _, value, _ ->
            tvTimeout.text = "${value.toInt()}s"
            prefs.edit().putInt(AodPrefs.KEY_TIMEOUT_SECONDS, value.toInt()).apply()
        }

        // Brightness slider
        val brightness = prefs.getInt(AodPrefs.KEY_BRIGHTNESS, AodPrefs.DEFAULT_BRIGHTNESS).toFloat()
        sliderBrightness.value = brightness.coerceIn(1f, 255f)
        tvBrightness.text = "${brightness.toInt()}"
        sliderBrightness.addOnChangeListener { _, value, _ ->
            tvBrightness.text = "${value.toInt()}"
            prefs.edit().putInt(AodPrefs.KEY_BRIGHTNESS, value.toInt()).apply()
        }

        // Reawaken
        switchReawaken.isChecked = prefs.getBoolean(AodPrefs.KEY_REAWAKEN_ON_SCREEN_OFF, true)
        switchReawaken.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AodPrefs.KEY_REAWAKEN_ON_SCREEN_OFF, checked).apply()
        }

        // Lock gesture + delay visibility
        val lockOn = prefs.getBoolean(AodPrefs.KEY_LOCK_GESTURE, true)
        switchLockGesture.isChecked = lockOn
        rowLockDelay.visibility = if (lockOn) View.VISIBLE else View.GONE
        switchLockGesture.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AodPrefs.KEY_LOCK_GESTURE, checked).apply()
            rowLockDelay.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Lock gesture delay slider
        val delay = prefs.getInt(AodPrefs.KEY_LOCK_GESTURE_DELAY, AodPrefs.DEFAULT_LOCK_GESTURE_DELAY).toFloat()
        sliderLockDelay.value = delay.coerceIn(0f, 30f)
        tvLockDelay.text = "${delay.toInt()}s"
        sliderLockDelay.addOnChangeListener { _, value, _ ->
            tvLockDelay.text = "${value.toInt()}s"
            prefs.edit().putInt(AodPrefs.KEY_LOCK_GESTURE_DELAY, value.toInt()).apply()
        }

        // Shallow sleep
        switchShallow.isChecked = prefs.getBoolean(AodPrefs.KEY_SHALLOW_SLEEP, false)
        switchShallow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AodPrefs.KEY_SHALLOW_SLEEP, checked).apply()
        }

        // Power button exit
        switchPowerExit.isChecked = prefs.getBoolean(AodPrefs.KEY_POWER_BUTTON_EXIT, true)
        switchPowerExit.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AodPrefs.KEY_POWER_BUTTON_EXIT, checked).apply()
        }
    }
}
