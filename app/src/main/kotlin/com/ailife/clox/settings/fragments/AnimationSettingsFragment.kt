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

    private lateinit var switchDetectClosing: MaterialSwitch
    private var pendingEnableDetect = false
    private var suppressDetectListener = false

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

        // The real-system-window (Path A) option is only offered when the privileged permission is
        // held (CloX shipped as a priv-app). It is the default type in that case (see AppListPrefs).
        val hasRemoteAnim = AppListPrefs.hasRemoteAnimPermission(requireContext())
        val realEntry = getString(R.string.anim_setting_real_window_entry)
        fun withReal(entries: Array<String>, values: Array<String>, realValue: String) =
            if (hasRemoteAnim) Pair(entries + realEntry, values + realValue) else Pair(entries, values)
        val (openTypeEntries, openTypeValues) = withReal(
            resources.getStringArray(R.array.open_anim_type_entries),
            resources.getStringArray(R.array.open_anim_type_values),
            AppListPrefs.OPEN_ANIM_REAL_WINDOW)
        val (closeTypeEntries0, closeTypeValues0) = withReal(
            resources.getStringArray(R.array.close_anim_type_entries),
            resources.getStringArray(R.array.close_anim_type_values),
            AppListPrefs.CLOSE_ANIM_REAL_WINDOW)
        // Snapshot close (CloX-drawn app-screenshot morph) — API 31+ where the real close transition
        // can't be owned; it's the default there. Needs the remote-anim permission (same as real window).
        val (closeTypeEntries, closeTypeValues) =
            if (hasRemoteAnim && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Pair(closeTypeEntries0 + getString(R.string.anim_setting_snapshot_entry),
                     closeTypeValues0 + AppListPrefs.CLOSE_ANIM_SNAPSHOT)
            else Pair(closeTypeEntries0, closeTypeValues0)

        fun labelFor(entries: Array<String>, values: Array<String>, v: String) =
            entries.getOrElse(values.indexOf(v)) { v }

        // ── Open animation switch ────────────────────────────────────────────
        fun isOpenAnimOn() =
            appListPrefs.getString(AppListPrefs.KEY_LAUNCH_ANIM, AppListPrefs.LAUNCH_ANIM_CUSTOM) ==
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
            tvOpenAnimType.text =
                labelFor(openTypeEntries, openTypeValues, AppListPrefs.openAnimType(requireContext()))
        }
        refreshOpenType()

        rowOpenAnimType.setOnClickListener {
            val current = AppListPrefs.openAnimType(requireContext())
            showListPicker(getString(R.string.anim_setting_open_type_title), openTypeEntries, openTypeValues, current) { v, _ ->
                appListPrefs.edit().putString(AppListPrefs.KEY_OPEN_ANIM_TYPE, v).apply()
                refreshOpenType()
            }
        }

        // ── Blur app list behind the window ──────────────────────────────────
        val switchLaunchBlur = view.findViewById<MaterialSwitch>(R.id.switchLaunchBlur)
        switchLaunchBlur.isChecked = appListPrefs.getBoolean(AppListPrefs.KEY_LAUNCH_BLUR, false)
        switchLaunchBlur.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean(AppListPrefs.KEY_LAUNCH_BLUR, checked).apply()
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
            tvCloseAnimType.text =
                labelFor(closeTypeEntries, closeTypeValues, AppListPrefs.closeAnimType(requireContext()))
        }
        refreshCloseType()

        rowCloseAnimType.setOnClickListener {
            val current = AppListPrefs.closeAnimType(requireContext())
            showListPicker(getString(R.string.anim_setting_close_type_title), closeTypeEntries, closeTypeValues, current) { v, _ ->
                appListPrefs.edit().putString(AppListPrefs.KEY_CLOSE_ANIM_TYPE, v).apply()
                refreshCloseType()
            }
        }

        // ── Resume close-to-watchface ────────────────────────────────────────
        val switchResumeClose = view.findViewById<MaterialSwitch>(R.id.switchResumeClose)
        switchResumeClose.isChecked =
            appListPrefs.getBoolean(AppListPrefs.KEY_RESUME_CLOSE_ANIM, false)
        switchResumeClose.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean(AppListPrefs.KEY_RESUME_CLOSE_ANIM, checked).apply()
        }

        // ── Gesture return reveal ────────────────────────────────────────────
        val switchGestureReturn = view.findViewById<MaterialSwitch>(R.id.switchGestureReturn)
        val rowGestureReturnType = view.findViewById<View>(R.id.rowGestureReturnType)
        val tvGestureReturnType = view.findViewById<TextView>(R.id.tvGestureReturnType)
        val rowGestureReturnOnly = view.findViewById<View>(R.id.rowGestureReturnOnly)
        val switchGestureReturnOnly = view.findViewById<MaterialSwitch>(R.id.switchGestureReturnOnly)
        val groupGestureReturnSpeed = view.findViewById<View>(R.id.groupGestureReturnSpeed)
        val tvGestureReturnSpeed = view.findViewById<TextView>(R.id.tvGestureReturnSpeed)
        val sliderGestureReturnSpeed = view.findViewById<Slider>(R.id.sliderGestureReturnSpeed)
        val tvGestureReturnDelay = view.findViewById<TextView>(R.id.tvGestureReturnDelay)
        val sliderGestureReturnDelay = view.findViewById<Slider>(R.id.sliderGestureReturnDelay)
        val gestureReturnEntries = resources.getStringArray(R.array.gesture_return_anim_entries)
        val gestureReturnValues  = resources.getStringArray(R.array.gesture_return_anim_values)

        val gestureReturnOn = appListPrefs.getBoolean(AppListPrefs.KEY_GESTURE_RETURN_ENABLED, true)
        switchGestureReturn.isChecked = gestureReturnOn
        rowGestureReturnType.visibility = if (gestureReturnOn) View.VISIBLE else View.GONE
        rowGestureReturnOnly.visibility = if (gestureReturnOn) View.VISIBLE else View.GONE
        groupGestureReturnSpeed.visibility = if (gestureReturnOn) View.VISIBLE else View.GONE

        switchGestureReturn.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean(AppListPrefs.KEY_GESTURE_RETURN_ENABLED, checked).apply()
            rowGestureReturnType.visibility = if (checked) View.VISIBLE else View.GONE
            rowGestureReturnOnly.visibility = if (checked) View.VISIBLE else View.GONE
            groupGestureReturnSpeed.visibility = if (checked) View.VISIBLE else View.GONE
        }

        switchGestureReturnOnly.isChecked =
            appListPrefs.getBoolean(AppListPrefs.KEY_GESTURE_RETURN_ONLY_GESTURE, true)
        switchGestureReturnOnly.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean(AppListPrefs.KEY_GESTURE_RETURN_ONLY_GESTURE, checked).apply()
        }

        val switchGestureHandoff = view.findViewById<MaterialSwitch>(R.id.switchGestureHandoff)
        switchGestureHandoff.isChecked =
            appListPrefs.getBoolean(AppListPrefs.KEY_GESTURE_HANDOFF_SURFACE, false)
        switchGestureHandoff.setOnCheckedChangeListener { _, checked ->
            appListPrefs.edit().putBoolean(AppListPrefs.KEY_GESTURE_HANDOFF_SURFACE, checked).apply()
        }

        val grSpeedVal = appListPrefs.getFloat(AppListPrefs.KEY_GESTURE_RETURN_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
        sliderGestureReturnSpeed.value = grSpeedVal
        tvGestureReturnSpeed.text = String.format("%.1f×", grSpeedVal)
        sliderGestureReturnSpeed.addOnChangeListener { _, value, _ ->
            tvGestureReturnSpeed.text = String.format("%.1f×", value)
            appListPrefs.edit().putFloat(AppListPrefs.KEY_GESTURE_RETURN_SPEED, value).apply()
        }

        val grDelayVal = appListPrefs.getInt(AppListPrefs.KEY_GESTURE_RETURN_DELAY, 200).coerceIn(0, 500).toFloat()
        sliderGestureReturnDelay.value = grDelayVal
        tvGestureReturnDelay.text = "${grDelayVal.toInt()} ms"
        sliderGestureReturnDelay.addOnChangeListener { _, value, _ ->
            tvGestureReturnDelay.text = "${value.toInt()} ms"
            appListPrefs.edit().putInt(AppListPrefs.KEY_GESTURE_RETURN_DELAY, value.toInt()).apply()
        }

        // ── Detect closing app (needs usage-access permission) ───────────────
        switchDetectClosing = view.findViewById(R.id.switchDetectClosing)
        syncDetectClosingSwitch()
        switchDetectClosing.setOnCheckedChangeListener { _, checked ->
            if (suppressDetectListener) return@setOnCheckedChangeListener
            val ctx = requireContext()
            if (checked) {
                if (com.ailife.clox.util.ClosingAppDetector.hasUsagePermission(ctx)) {
                    AppListPrefs.setDetectClosingApp(ctx, true)
                } else {
                    // Need permission first — revert the switch, remember intent, and prompt.
                    pendingEnableDetect = true
                    suppressDetectListener = true
                    switchDetectClosing.isChecked = false
                    suppressDetectListener = false
                    promptUsageAccess()
                }
            } else {
                AppListPrefs.setDetectClosingApp(ctx, false)
            }
        }

        fun refreshGestureReturnType() {
            val v = appListPrefs.getString(AppListPrefs.KEY_GESTURE_RETURN_ANIM, AppListPrefs.GESTURE_RETURN_CIRCLE)
                ?: AppListPrefs.GESTURE_RETURN_CIRCLE
            tvGestureReturnType.text = labelFor(gestureReturnEntries, gestureReturnValues, v)
        }
        refreshGestureReturnType()

        rowGestureReturnType.setOnClickListener {
            val current = appListPrefs.getString(AppListPrefs.KEY_GESTURE_RETURN_ANIM, AppListPrefs.GESTURE_RETURN_CIRCLE)
                ?: AppListPrefs.GESTURE_RETURN_CIRCLE
            showListPicker(getString(R.string.anim_setting_gesture_return_type_title), gestureReturnEntries, gestureReturnValues, current) { v, _ ->
                appListPrefs.edit().putString(AppListPrefs.KEY_GESTURE_RETURN_ANIM, v).apply()
                refreshGestureReturnType()
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

    override fun onResume() {
        super.onResume()
        // Returning from the system "Usage access" screen — apply a pending enable and
        // reconcile the switch with the actual permission state.
        if (::switchDetectClosing.isInitialized) {
            val ctx = requireContext()
            if (pendingEnableDetect && com.ailife.clox.util.ClosingAppDetector.hasUsagePermission(ctx)) {
                pendingEnableDetect = false
                AppListPrefs.setDetectClosingApp(ctx, true)
            }
            syncDetectClosingSwitch()
        }
    }

    /** Reflects pref + permission in the switch; clears the pref if permission was revoked. */
    private fun syncDetectClosingSwitch() {
        val ctx = requireContext()
        val granted = com.ailife.clox.util.ClosingAppDetector.hasUsagePermission(ctx)
        if (!granted && AppListPrefs.detectClosingApp(ctx)) AppListPrefs.setDetectClosingApp(ctx, false)
        val on = AppListPrefs.detectClosingApp(ctx) && granted
        suppressDetectListener = true
        switchDetectClosing.isChecked = on
        suppressDetectListener = false
    }

    private fun promptUsageAccess() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anim_setting_detect_closing_perm_title)
            .setMessage(R.string.anim_setting_detect_closing_perm_msg)
            .setPositiveButton(R.string.anim_setting_detect_closing_grant) { _, _ ->
                com.ailife.clox.util.ClosingAppDetector.openUsageAccessSettings(requireContext())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingEnableDetect = false }
            .show()
    }
}
