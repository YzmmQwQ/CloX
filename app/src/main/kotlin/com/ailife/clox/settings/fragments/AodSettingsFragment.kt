package com.ailife.clox.settings.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.aod.AodPrefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class AodSettingsFragment : BaseSettingsFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_aod, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switchEnabled   = view.findViewById<MaterialSwitch>(R.id.switchAodEnabled)
        val switchWhenCharging = view.findViewById<MaterialSwitch>(R.id.switchAodWhenCharging)
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
            // Re-awaken (on by default) needs to foreground the launcher from the background when the
            // screen turns off in another app — impossible without the overlay permission.
            if (checked && prefs.getBoolean(AodPrefs.KEY_REAWAKEN_ON_SCREEN_OFF, true)) ensureOverlayPermission()
        }

        // Always when charging
        switchWhenCharging.isChecked = prefs.getBoolean(AodPrefs.KEY_ALWAYS_WHEN_CHARGING, false)
        switchWhenCharging.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AodPrefs.KEY_ALWAYS_WHEN_CHARGING, checked).apply()
        }

        // Timeout slider
        val timeout = prefs.getInt(AodPrefs.KEY_TIMEOUT_SECONDS, AodPrefs.DEFAULT_TIMEOUT_SECONDS).toFloat()
        sliderTimeout.value = timeout.coerceIn(5f, 300f)
        tvTimeout.text = getString(R.string.unit_seconds, timeout.toInt())
        sliderTimeout.addOnChangeListener { _, value, _ ->
            tvTimeout.text = getString(R.string.unit_seconds, value.toInt())
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
            if (checked) ensureOverlayPermission()
        }
        // Nudge for the permission on entry if the feature is on but the grant is missing.
        if (prefs.getBoolean(AodPrefs.KEY_ENABLED, false) &&
            prefs.getBoolean(AodPrefs.KEY_REAWAKEN_ON_SCREEN_OFF, true)) ensureOverlayPermission()

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
        tvLockDelay.text = getString(R.string.unit_seconds, delay.toInt())
        sliderLockDelay.addOnChangeListener { _, value, _ ->
            tvLockDelay.text = getString(R.string.unit_seconds, value.toInt())
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

    /**
     * AOD re-awaken needs to start MainActivity from the background when the screen turns off while a
     * third-party app is foreground. Android blocks such background activity starts unless the app
     * holds SYSTEM_ALERT_WINDOW ("Display over other apps"). Since targetSdk is high, this can't be
     * granted silently — prompt the user to grant it via the system settings screen.
     */
    private fun ensureOverlayPermission() {
        if (Settings.canDrawOverlays(requireContext())) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.aod_overlay_perm_title)
            .setMessage(R.string.aod_overlay_perm_message)
            .setPositiveButton(R.string.aod_overlay_perm_grant) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                    )
                }.onFailure {
                    // Some devices reject the package-scoped Uri; fall back to the global list.
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
