package com.ailife.clox.settings.fragments

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.ailife.clox.gesture.ButtonMappingPrefs
import com.ailife.clox.gesture.ButtonRecordDialogFragment
import com.ailife.clox.gesture.GestureAppPref
import com.ailife.clox.icon.renderToBitmap
import com.ailife.clox.qs.QsPermissionHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GesturesSettingsFragment : BaseSettingsFragment() {

    private lateinit var actionEntries: Array<String>
    private lateinit var actionValues: Array<String>

    private data class AppRow(val ri: ResolveInfo, val label: String, val icon: Bitmap)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_gestures, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        actionEntries = resources.getStringArray(R.array.gesture_action_entries)
        actionValues  = resources.getStringArray(R.array.gesture_action_values)

        // Double-tap
        val tvDoubleTap = view.findViewById<TextView>(R.id.tvDoubleTap)
        fun refreshDoubleTap() { tvDoubleTap.text = summaryFor("double_tap_action", "screen_off") }
        refreshDoubleTap()
        view.findViewById<View>(R.id.rowDoubleTap).setOnClickListener {
            pickAction(getString(R.string.pref_double_tap_title), "double_tap_action", "screen_off") {
                refreshDoubleTap()
            }
        }

        // Block first tap while waiting for a double tap
        val switchDoubleTapHold = view.findViewById<MaterialSwitch>(R.id.switchDoubleTapHold)
        switchDoubleTapHold.isChecked = prefs.getBoolean("double_tap_hold_taps", false)
        switchDoubleTapHold.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("double_tap_hold_taps", checked).apply()
        }

        // Long press
        val tvLongPress = view.findViewById<TextView>(R.id.tvLongPress)
        fun refreshLongPress() { tvLongPress.text = summaryFor("long_press_action", "watchface_selector") }
        refreshLongPress()
        view.findViewById<View>(R.id.rowLongPress).setOnClickListener {
            pickAction(getString(R.string.pref_long_press_title), "long_press_action", "watchface_selector") {
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

        // Left swipe opens app list
        val switchAppListSwipe = view.findViewById<MaterialSwitch>(R.id.switchAppListSwipe)
        switchAppListSwipe.isChecked = prefs.getBoolean("app_list_swipe_enabled", false)
        switchAppListSwipe.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("app_list_swipe_enabled", checked).apply()
        }

        // Reset to watchface when the screen turns on
        val switchResetWatchface = view.findViewById<MaterialSwitch>(R.id.switchResetWatchface)
        switchResetWatchface.isChecked = prefs.getBoolean("reset_watchface_on_screen_on", false)
        switchResetWatchface.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("reset_watchface_on_screen_on", checked).apply()
        }

        // Navigation bar visibility
        val navBarEntries = resources.getStringArray(R.array.nav_bar_mode_entries)
        val navBarValues  = resources.getStringArray(R.array.nav_bar_mode_values)
        fun navBarLabel(v: String) = navBarEntries.getOrElse(navBarValues.indexOf(v)) { v }
        val tvNavBar = view.findViewById<TextView>(R.id.tvNavBar)
        fun refreshNavBar() {
            tvNavBar.text = navBarLabel(prefs.getString("nav_bar_mode", "applist") ?: "applist")
        }
        refreshNavBar()
        view.findViewById<View>(R.id.rowNavBar).setOnClickListener {
            showListPicker(getString(R.string.pref_nav_bar_title), navBarEntries, navBarValues,
                prefs.getString("nav_bar_mode", "applist") ?: "applist") { v, _ ->
                prefs.edit().putString("nav_bar_mode", v).apply()
                refreshNavBar()
            }
        }

        // Status bar visibility
        val statusBarEntries = resources.getStringArray(R.array.status_bar_mode_entries)
        val statusBarValues  = resources.getStringArray(R.array.status_bar_mode_values)
        fun statusBarLabel(v: String) = statusBarEntries.getOrElse(statusBarValues.indexOf(v)) { v }
        val tvStatusBar = view.findViewById<TextView>(R.id.tvStatusBar)
        fun refreshStatusBar() {
            tvStatusBar.text = statusBarLabel(prefs.getString("status_bar_mode", "hidden") ?: "hidden")
        }
        refreshStatusBar()
        view.findViewById<View>(R.id.rowStatusBar).setOnClickListener {
            showListPicker(getString(R.string.pref_status_bar_title), statusBarEntries, statusBarValues,
                prefs.getString("status_bar_mode", "hidden") ?: "hidden") { v, _ ->
                prefs.edit().putString("status_bar_mode", v).apply()
                refreshStatusBar()
            }
        }

        // Swipe actions (apply only when the matching panel is disabled)
        val tvSwipeDown = view.findViewById<TextView>(R.id.tvSwipeDown)
        fun refreshSwipeDown() { tvSwipeDown.text = summaryFor("swipe_down_action", "nothing") }
        refreshSwipeDown()
        view.findViewById<View>(R.id.rowSwipeDown).setOnClickListener {
            pickAction(getString(R.string.pref_swipe_down_title), "swipe_down_action", "nothing") {
                refreshSwipeDown()
            }
        }
        val tvSwipeRight = view.findViewById<TextView>(R.id.tvSwipeRight)
        fun refreshSwipeRight() { tvSwipeRight.text = summaryFor("swipe_right_action", "nothing") }
        refreshSwipeRight()
        view.findViewById<View>(R.id.rowSwipeRight).setOnClickListener {
            pickAction(getString(R.string.pref_swipe_right_title), "swipe_right_action", "nothing") {
                refreshSwipeRight()
            }
        }

        // Hardware buttons (multiple)
        val containerButtons = view.findViewById<LinearLayout>(R.id.containerButtons)
        rebuildButtons(containerButtons)
        view.findViewById<View>(R.id.rowBtnRecord).setOnClickListener {
            val dialog = ButtonRecordDialogFragment()
            dialog.onKeyRecorded = { keyCode ->
                ButtonMappingPrefs.addKeyCode(requireContext(), keyCode)
                rebuildButtons(containerButtons)
            }
            dialog.show(childFragmentManager, ButtonRecordDialogFragment.TAG)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun labelFor(value: String) =
        actionEntries.getOrElse(actionValues.indexOf(value)) { value }

    /** Row summary for an action pref — shows the picked app when the action is "Open app". */
    private fun summaryFor(baseKey: String, default: String): String {
        val v = prefs.getString(baseKey, default) ?: default
        if (v == "open_app") {
            val app = GestureAppPref.getLabel(requireContext(), baseKey)
                ?: getString(R.string.gesture_open_app_none)
            return getString(R.string.gesture_open_app_summary, app)
        }
        return labelFor(v)
    }

    /** Pick an action for [baseKey]; if "Open app" is chosen, chain into the app picker. */
    private fun pickAction(title: String, baseKey: String, default: String, onDone: () -> Unit) {
        showListPicker(title, actionEntries, actionValues, prefs.getString(baseKey, default) ?: default) { v, _ ->
            prefs.edit().putString(baseKey, v).apply()
            if (v == "open_app") {
                showAppPicker(title, baseKey) { onDone() }
            } else {
                onDone()
            }
        }
    }

    private fun showAppPicker(title: String, baseKey: String, onDone: () -> Unit) {
        val ctx = requireContext()
        val pm  = ctx.packageManager
        val iconSizePx = (40 * resources.displayMetrics.density + 0.5f).toInt()

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).sortedBy { it.loadLabel(pm).toString().lowercase() }
                 .map { ri -> AppRow(ri, ri.loadLabel(pm).toString(), ri.loadIcon(pm).renderToBitmap(iconSizePx)) }
            }
            if (!isAdded) return@launch

            val labels = rows.map { it.label }
            val adapter = object : ArrayAdapter<String>(ctx, R.layout.item_app_list, labels) {
                override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                    val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_app_list, parent, false)
                    v.findViewById<ImageView>(R.id.appIcon).setImageBitmap(rows[pos].icon)
                    v.findViewById<TextView>(R.id.appLabel).text = labels[pos]
                    return v
                }
            }

            MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setAdapter(adapter) { _, which ->
                    val info = rows[which].ri.activityInfo
                    GestureAppPref.setComponent(ctx, baseKey, "${info.packageName}/${info.name}")
                    onDone()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> onDone() }
                .show()
        }
    }

    private fun rebuildButtons(container: LinearLayout) {
        container.removeAllViews()
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val keyCodes = ButtonMappingPrefs.getMappedKeyCodes(ctx)

        for ((index, keyCode) in keyCodes.withIndex()) {
            val block = inflater.inflate(R.layout.item_button_mapping, container, false)
            block.findViewById<TextView>(R.id.tvBtnName).text =
                ButtonMappingPrefs.keyCodeToName(ctx, keyCode)

            bindPressRow(block, R.id.rowBtnSingle, R.id.tvBtnSingle,
                getString(R.string.pref_btn_single_title), keyCode, ButtonMappingPrefs.PRESS_SINGLE)
            bindPressRow(block, R.id.rowBtnDouble, R.id.tvBtnDouble,
                getString(R.string.pref_btn_double_title), keyCode, ButtonMappingPrefs.PRESS_DOUBLE)
            bindPressRow(block, R.id.rowBtnLong, R.id.tvBtnLong,
                getString(R.string.pref_btn_long_title), keyCode, ButtonMappingPrefs.PRESS_LONG)

            block.findViewById<MaterialButton>(R.id.btnRemove).setOnClickListener {
                ButtonMappingPrefs.removeKeyCode(ctx, keyCode)
                rebuildButtons(container)
            }
            // Hide the trailing divider on the last button.
            if (index == keyCodes.lastIndex) block.findViewById<View>(R.id.btnDivider).visibility = View.GONE

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lp.topMargin = (14 * resources.displayMetrics.density).toInt()
            container.addView(block, lp)
        }
    }

    private fun bindPressRow(block: View, rowId: Int, tvId: Int, title: String, keyCode: Int, press: String) {
        val baseKey = ButtonMappingPrefs.actionKey(keyCode, press)
        val tv = block.findViewById<TextView>(tvId)
        tv.text = summaryFor(baseKey, "nothing")
        block.findViewById<View>(rowId).setOnClickListener {
            pickAction(title, baseKey, "nothing") { tv.text = summaryFor(baseKey, "nothing") }
        }
    }
}
