package com.yzmm.clox.watchface.config

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.yzmm.clox.R
import com.yzmm.clox.rtos.RtosManager
import com.yzmm.clox.ui.theme.ScreenShapeConfig
import com.yzmm.clox.util.safeStartActivity
import com.yzmm.clox.watchface.WatchfaceConfigPrefs
import com.yzmm.clox.watchface.WatchfaceMetadata
import com.yzmm.clox.watchface.WatchfacePrefs
import com.yzmm.clox.watchface.WatchfaceStats
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Watch-face configuration panel. Two modes, same builders:
 *  - PER-FACE (default): four tabs — Alignment, Behavior, Frame rate, Information.
 *    The first three carry a "use app default" switch; when set, the group follows
 *    the app-wide defaults and its controls are disabled.
 *  - DEFAULTS ([EXTRA_DEFAULTS]): edits the app-wide defaults (Settings entry) —
 *    the three editable groups only, no use-default switches, no Information tab.
 *
 * Every change writes straight to [WatchfaceConfigPrefs], which re-pushes the
 * settings snapshot to the Lua engine — so the face behind this translucent panel
 * updates live. The whole panel scrolls as one unit on a small screen.
 */
class WatchfaceConfigActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenShapeConfig.wrap(newBase))
    }

    private lateinit var cfgPath: String
    private val editingDefaults: Boolean get() = intent.getBooleanExtra(EXTRA_DEFAULTS, false)
    private val cfg = WatchfaceConfigPrefs
    private var selectedTab = 0
    private var flipper: ViewFlipper? = null
    private var pageSwitcher: ((Int) -> Unit)? = null   // lazily builds + shows a page

    companion object {
        const val EXTRA_DEFAULTS = "edit_defaults"
        private const val KEY_TAB = "sel_tab"

        @Volatile var isActive: Boolean = false
            private set

        /** Per-face config for the active face (live preview over the watchface). */
        fun start(ctx: Context) {
            isActive = true
            ctx.safeStartActivity(Intent(ctx, WatchfaceConfigActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        /** App-wide defaults editor (from Settings). */
        fun startDefaults(ctx: Context) {
            isActive = true
            ctx.safeStartActivity(Intent(ctx, WatchfaceConfigActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_DEFAULTS, true)
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // resources.displayMetrics subtracts the navigation bar even when it's hidden,
    // same gotcha documented on MainActivity.realScreenSize() — using it here shrank
    // the panel short of the real window height, leaving a dead gap above it.
    private fun realScreenSize(): android.graphics.Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            return android.graphics.Point(b.width(), b.height())
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return android.graphics.Point(dm.widthPixels, dm.heightPixels)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Preserve the selected tab across recreate() (a "use default" toggle) —
        // otherwise the flipper would snap back to the first page.
        selectedTab = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0
        val active = WatchfacePrefs.getActivePath(this)
        cfgPath = WatchfaceConfigPrefs.pathFor(active)
        setContentView(buildRoot(active))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, flipper?.displayedChild ?: selectedTab)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // This activity has no app list, so "applist" mode behaves like hidden here.
        val statusMode = prefs.getString("status_bar_mode", "hidden") ?: "hidden"
        if (!RtosManager.isActive && statusMode == "show") controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())

        val navMode = prefs.getString("nav_bar_mode", "applist") ?: "applist"
        if (!RtosManager.isActive && navMode == "show") controller.show(WindowInsetsCompat.Type.navigationBars())
        else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    // ── root scrim + bottom panel ─────────────────────────────────────────────
    private fun buildRoot(activePath: String?): View {
        val root = FrameLayout(this)
        root.setOnClickListener { finish() }

        val card = MaterialCardView(this).apply {
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, dp(20).toFloat())
                .setTopRightCorner(CornerFamily.ROUNDED, dp(20).toFloat())
                .build()
            setCardBackgroundColor(0xD91A1A1A.toInt())
            cardElevation = dp(8).toFloat()
            strokeWidth = 0
            isClickable = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        content.addView(titleRow(activePath))

        val flip = ViewFlipper(this)
        flipper = flip
        // Tabs are icon-only — text was ellipsised to "…" in the narrow 3/4-way
        // toggle group. Icons: aspect-ratio (alignment), toggles (behavior),
        // speed (frame rate), info.
        // Tabs are icon-only. Pages are built LAZILY — only the visible tab's page
        // is created on open; the rest are built the first time their tab is shown.
        // Building all four Material pages eagerly made the dialog take ~1s to open.
        val icons: List<Pair<Int, Int>>   // icon res, content-description res
        val builders: List<() -> View>
        if (editingDefaults) {
            icons = listOf(
                R.drawable.ic_aspect_ratio to R.string.wfcfg_tab_alignment,
                R.drawable.ic_palette to R.string.wfcfg_tab_color,
                R.drawable.ic_toggle_on to R.string.wfcfg_tab_behavior,
                R.drawable.ic_speed to R.string.wfcfg_tab_framerate
            )
            builders = listOf({ alignmentPage() }, { colorPage() }, { behaviorPage() }, { frameRatePage() })
        } else {
            icons = listOf(
                R.drawable.ic_aspect_ratio to R.string.wfcfg_tab_alignment,
                R.drawable.ic_palette to R.string.wfcfg_tab_color,
                R.drawable.ic_toggle_on to R.string.wfcfg_tab_behavior,
                R.drawable.ic_speed to R.string.wfcfg_tab_framerate,
                R.drawable.ic_info to R.string.wfcfg_tab_info
            )
            builders = listOf({ alignmentPage() }, { colorPage() }, { behaviorPage() }, { frameRatePage() }, { infoPage(activePath) })
        }
        selectedTab = selectedTab.coerceIn(0, builders.size - 1)

        val containers = builders.map { FrameLayout(this) }
        val built = BooleanArray(builders.size)
        fun showPage(idx: Int) {
            if (!built[idx]) {
                built[idx] = true
                containers[idx].addView(builders[idx](), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            flip.displayedChild = idx
        }
        pageSwitcher = ::showPage
        // Containers must be the flipper's children before tabRow's initial check()
        // fires showPage (which sets displayedChild).
        containers.forEach { flip.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        content.addView(tabRow(flip, icons), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        showPage(selectedTab)
        content.addView(flip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        content.addView(MaterialButton(this).apply {
            text = getString(R.string.wfcfg_done)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        val maxCardHeight = realScreenSize().y
        val scroll = MaxHeightScrollView(this, maxCardHeight).apply {
            isVerticalScrollBarEnabled = false
        }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(card)
        (card.layoutParams as FrameLayout.LayoutParams).apply {
            width = MATCH_PARENT; height = WRAP_CONTENT; gravity = Gravity.BOTTOM
        }
        return root
    }

    private fun faceLabel(activePath: String?): String =
        if (activePath.isNullOrEmpty()) getString(R.string.wfcfg_title_default)
        else File(activePath).nameWithoutExtension

    /** First row: watch-face name, with a "Change" button (per-face mode) that
     *  closes the dialog and opens the selector. */
    private fun titleRow(activePath: String?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = if (editingDefaults) getString(R.string.wfcfg_defaults_title) else faceLabel(activePath)
            setTextColor(Color.WHITE); textSize = 18f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (!editingDefaults) {
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.wfcfg_change); textSize = 12f
                setTextColor(Color.WHITE); insetTop = 0; insetBottom = 0
                setOnClickListener {
                    safeStartActivity(Intent(this@WatchfaceConfigActivity,
                        com.yzmm.clox.watchface.selector.WatchfaceSelectorActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        return row
    }

    /** Reload the current face (re-setting the same path is a no-op, so use the
     *  explicit reload command). Closes the dialog so the loading placeholder shows. */
    private fun requestReload() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putLong(com.yzmm.clox.cocos.bridge.WatchfaceBridgeManager.KEY_RELOAD,
                     android.os.SystemClock.elapsedRealtime()).apply()
        finish()
    }

    // ── tab row toggling the flipper (icon-only tabs) ─────────────────────────
    private fun tabRow(flip: ViewFlipper, icons: List<Pair<Int, Int>>): View {
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val buttons = icons.map { (iconRes, descRes) ->
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = ""
                setIconResource(iconRes)
                iconTint = android.content.res.ColorStateList.valueOf(Color.WHITE)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = 0
                contentDescription = getString(descRes)
                isCheckable = true
                insetTop = 0; insetBottom = 0
            }
        }
        buttons.forEach { group.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        group.check(buttons[selectedTab.coerceIn(0, buttons.size - 1)].id)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx >= 0) { selectedTab = idx; (pageSwitcher ?: { flip.displayedChild = it })(idx) }
        }
        return group
    }

    // ── page 1: alignment (deform + offsets) ──────────────────────────────────
    private fun alignmentPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val useDefault = !editingDefaults && cfg.useDefaultAlign(this, cfgPath)
        if (!editingDefaults) {
            page.addChild(useDefaultSwitch(useDefault) { checked ->
                cfg.setUseDefaultAlign(this, cfgPath, checked); recreate()
            })
        }
        val editable = editingDefaults || !useDefault

        // Alignment mode selector — icon-only (Material icons: vertical arrows =
        // fill height, fit-screen = fit, horizontal arrows = stretch, sliders = custom).
        val modes = listOf(
            Triple(WatchfaceConfigPrefs.ALIGN_VERTICAL, R.drawable.ic_align_height, R.string.wfcfg_align_vertical),
            Triple(WatchfaceConfigPrefs.ALIGN_HORIZONTAL, R.drawable.ic_align_horizontal, R.string.wfcfg_align_horizontal),
            Triple(WatchfaceConfigPrefs.ALIGN_STRETCH, R.drawable.ic_align_stretch, R.string.wfcfg_align_stretch),
            Triple(WatchfaceConfigPrefs.ALIGN_CUSTOM, R.drawable.ic_tune, R.string.wfcfg_align_custom)
        )
        val curMode = getAlignMode()
        val modeGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
        }
        val modeButtons = modes.map { (_, iconRes, labelRes) ->
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = ""; isCheckable = true
                setIconResource(iconRes)
                iconTint = android.content.res.ColorStateList.valueOf(Color.WHITE)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; iconPadding = 0
                contentDescription = getString(labelRes)
                insetTop = 0; insetBottom = 0
            }
        }
        modeButtons.forEach { modeGroup.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        modeGroup.check(modeButtons[modes.indexOfFirst { it.first == curMode }.coerceAtLeast(0)].id)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val idx = modeButtons.indexOfFirst { it.id == checkedId }
            if (idx >= 0) { setAlignMode(modes[idx].first); recreate() }
        }
        page.addChild(modeGroup)

        // Width / Height size sliders — only meaningful in custom mode
        val custom = curMode == WatchfaceConfigPrefs.ALIGN_CUSTOM
        page.addChild(sizeSlider(getString(R.string.wfcfg_width), getSizeX(), editable && custom) { setSizeX(it) })
        page.addChild(sizeSlider(getString(R.string.wfcfg_height), getSizeY(), editable && custom) { setSizeY(it) })

        // Offsets (all modes)
        val r = WatchfaceConfigPrefs.OFFSET_RANGE
        page.addChild(offsetSlider(getString(R.string.wfcfg_h_offset), getXOffset(), r, editable) { setXOffset(it) })
        page.addChild(offsetSlider(getString(R.string.wfcfg_v_offset), getYOffset(), r, editable) { setYOffset(it) })

        page.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.wfcfg_reset_alignment)
            isEnabled = editable
            setOnClickListener {
                if (editingDefaults) {
                    cfg.setGdefAlignMode(this@WatchfaceConfigActivity, WatchfaceConfigPrefs.DEF_ALIGN_MODE)
                    cfg.setGdefSizeX(this@WatchfaceConfigActivity, WatchfaceConfigPrefs.DEF_SIZE)
                    cfg.setGdefSizeY(this@WatchfaceConfigActivity, WatchfaceConfigPrefs.DEF_SIZE)
                    cfg.setGdefXOffset(this@WatchfaceConfigActivity, WatchfaceConfigPrefs.DEF_OFFSET)
                    cfg.setGdefYOffset(this@WatchfaceConfigActivity, WatchfaceConfigPrefs.DEF_OFFSET)
                } else {
                    cfg.resetAlignment(this@WatchfaceConfigActivity, cfgPath)
                }
                recreate()
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        return page
    }

    // ── page 2: behavior toggles ──────────────────────────────────────────────
    private fun behaviorPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val useDefault = !editingDefaults && cfg.useDefaultBehavior(this, cfgPath)
        if (!editingDefaults) {
            page.addChild(useDefaultSwitch(useDefault) { checked ->
                cfg.setUseDefaultBehavior(this, cfgPath, checked); recreate()
            })
        }
        val editable = editingDefaults || !useDefault

        page.addChild(switchRow(R.string.wfcfg_allow_sound, R.string.wfcfg_allow_sound_summary, getAllowSound(), editable) { setAllowSound(it) })
        page.addChild(switchRow(R.string.wfcfg_allow_vibrate, R.string.wfcfg_allow_vibrate_summary, getAllowVibrate(), editable) { setAllowVibrate(it) })
        page.addChild(switchRow(R.string.wfcfg_allow_sensors, R.string.wfcfg_allow_sensors_summary, getAllowSensors(), editable) { setAllowSensors(it) })
        page.addChild(switchRow(R.string.wfcfg_allow_weather, R.string.wfcfg_allow_weather_summary, getAllowWeather(), editable) { setAllowWeather(it) })
        page.addChild(switchRow(R.string.wfcfg_allow_health, R.string.wfcfg_allow_health_summary, getAllowHealth(), editable) { setAllowHealth(it) })
        page.addChild(switchRow(R.string.wfcfg_allow_tap, R.string.wfcfg_allow_tap_summary, getAllowTap(), editable) { setAllowTap(it) })
        return page
    }

    // ── page: accent color ────────────────────────────────────────────────────
    // Overrides the face's {ucolor} accent: face default, the launcher's
    // Material You accent, or a fixed color from the preset grid / picker.
    // Changes apply live (a {ucolor} publish engine-side — no reload).
    private fun colorPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val useDefault = !editingDefaults && cfg.useDefaultColor(this, cfgPath)
        if (!editingDefaults) {
            page.addChild(useDefaultSwitch(useDefault) { checked ->
                cfg.setUseDefaultColor(this, cfgPath, checked); recreate()
            })
        }
        val editable = editingDefaults || !useDefault

        val swatches = mutableListOf<Pair<String, MaterialCardView>>()
        lateinit var refreshSwatches: () -> Unit

        // mode selector
        val modeIds = listOf(cfg.COLOR_DEFAULT, cfg.COLOR_THEME, cfg.COLOR_FIXED)
        val modeLabels = listOf(R.string.wfcfg_color_default, R.string.wfcfg_color_theme, R.string.wfcfg_color_fixed)
        val group = MaterialButtonToggleGroup(this).apply {
            orientation = LinearLayout.VERTICAL; isSingleSelection = true; isSelectionRequired = true
        }
        val modeButtons = modeLabels.map { res ->
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = getString(res); isCheckable = true
                setTextColor(Color.WHITE); isEnabled = editable
            }
        }
        modeButtons.forEach { group.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        group.check(modeButtons[modeIds.indexOf(getColorMode()).coerceAtLeast(0)].id)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val idx = modeButtons.indexOfFirst { it.id == checkedId }
            if (idx >= 0) { setColorMode(modeIds[idx]); refreshSwatches() }
        }
        page.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        page.addView(TextView(this).apply {
            text = getString(R.string.wfcfg_color_theme_hint)
            setTextColor(0xFF999999.toInt()); textSize = 11f
            setPadding(0, dp(6), 0, dp(6))
        })

        // preset swatch grid — tapping one selects it AND switches to "fixed"
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val perRow = 5
        cfg.COLOR_PRESETS.chunked(perRow).forEach { rowColors ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { hex ->
                val card = MaterialCardView(this).apply {
                    radius = dp(20).toFloat()
                    setCardBackgroundColor(Color.parseColor("#$hex"))
                    setOnClickListener {
                        if (!editable) return@setOnClickListener
                        setColorValue(hex); setColorMode(cfg.COLOR_FIXED)
                        group.check(modeButtons[modeIds.indexOf(cfg.COLOR_FIXED)].id)
                        refreshSwatches()
                    }
                }
                swatches += hex to card
                row.addView(card, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        page.addChild(grid)

        // current color + picker
        val preview = View(this)
        fun previewDrawable() = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#${getColorValue()}"))
            setStroke(dp(1), 0x66FFFFFF)
        }
        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        pickRow.addView(preview.apply { background = previewDrawable() },
            LinearLayout.LayoutParams(dp(32), dp(32)))
        val hexLabel = TextView(this).apply {
            text = "#${getColorValue()}"; setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(10), 0, 0, 0)
        }
        pickRow.addView(hexLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pickRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.wfcfg_color_pick); setTextColor(Color.WHITE)
            isEnabled = editable
            setOnClickListener {
                showColorPicker(getColorValue()) { hex ->
                    setColorValue(hex); setColorMode(cfg.COLOR_FIXED)
                    group.check(modeButtons[modeIds.indexOf(cfg.COLOR_FIXED)].id)
                    refreshSwatches()
                }
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        page.addChild(pickRow)

        refreshSwatches = {
            val fixed = getColorMode() == cfg.COLOR_FIXED
            val sel = getColorValue()
            swatches.forEach { (hex, card) ->
                card.strokeWidth = if (fixed && hex.equals(sel, true)) dp(3) else 0
                card.strokeColor = Color.WHITE
                card.alpha = if (editable) 1f else 0.5f
            }
            preview.background = previewDrawable()
            hexLabel.text = "#${getColorValue()}"
        }
        refreshSwatches()
        return page
    }

    /** Minimal RGB picker: preview + hex field + three sliders. */
    private fun showColorPicker(initial: String, onPicked: (String) -> Unit) {
        val start = try { Color.parseColor("#$initial") } catch (e: Exception) { Color.WHITE }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0)
        }
        val preview = View(this)
        var r = Color.red(start); var g = Color.green(start); var b = Color.blue(start)
        fun hex() = String.format("%02x%02x%02x", r, g, b)
        val hexEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.wfcfg_color_hex_hint)
            setText(hex())
        }
        fun refresh(fromSlider: Boolean) {
            preview.background = GradientDrawable().apply {
                setColor(Color.rgb(r, g, b)); cornerRadius = dp(6).toFloat()
                setStroke(dp(1), 0x66FFFFFF)
            }
            if (fromSlider) { hexEdit.setText(hex()) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(preview, LinearLayout.LayoutParams(dp(48), dp(32)))
        head.addView(hexEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        box.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val sliders = listOf("R" to { v: Int -> r = v }, "G" to { v: Int -> g = v }, "B" to { v: Int -> b = v })
        val channelInit = listOf(r, g, b)
        sliders.forEachIndexed { i, (label, setter) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 12f },
                LinearLayout.LayoutParams(dp(16), WRAP_CONTENT))
            row.addView(Slider(this).apply {
                valueFrom = 0f; valueTo = 255f; stepSize = 1f; value = channelInit[i].toFloat()
                addOnChangeListener { _, v, fromUser -> if (fromUser) { setter(v.toInt()); refresh(true) } }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        hexEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (t.length == 6) try {
                    val c = Color.parseColor("#$t")
                    r = Color.red(c); g = Color.green(c); b = Color.blue(c)
                    refresh(false)
                } catch (_: Exception) {}
            }
        })
        refresh(false)
        MaterialAlertDialogBuilder(this)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(hex()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── page 3: frame rate ────────────────────────────────────────────────────
    private fun frameRatePage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val useDefault = !editingDefaults && cfg.useDefaultFrameRate(this, cfgPath)
        if (!editingDefaults) {
            page.addChild(useDefaultSwitch(useDefault) { checked ->
                cfg.setUseDefaultFrameRate(this, cfgPath, checked); recreate()
            })
        }
        val editable = editingDefaults || !useDefault

        val unlimited = getUnlimited()
        val current = getMaxFps()
        val group = MaterialButtonToggleGroup(this).apply {
            orientation = LinearLayout.VERTICAL; isSingleSelection = true; isSelectionRequired = true
        }
        val options = cfg.FPS_CHOICES.map { getString(R.string.wfcfg_fps_value, it) } + getString(R.string.wfcfg_fps_unlimited)
        val buttons = options.map { label ->
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = label; isCheckable = true
                setTextColor(Color.WHITE); isEnabled = editable
            }
        }
        buttons.forEach { group.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        val selIdx = when {
            unlimited -> cfg.FPS_CHOICES.size
            cfg.FPS_CHOICES.indexOf(current) >= 0 -> cfg.FPS_CHOICES.indexOf(current)
            else -> cfg.FPS_CHOICES.indexOf(cfg.DEF_MAX_FPS).coerceAtLeast(0)
        }
        group.check(buttons[selIdx].id)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx == cfg.FPS_CHOICES.size) setUnlimited(true)
            else if (idx in cfg.FPS_CHOICES.indices) { setMaxFps(cfg.FPS_CHOICES[idx]); setUnlimited(false) }
        }
        page.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        page.addView(TextView(this).apply {
            text = getString(R.string.wfcfg_aod_hint)
            setTextColor(0xFF999999.toInt()); textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })

        // ── dynamic FPS ───────────────────────────────────────────────────────
        // the pref write triggers the bridge listener: RenderPolicy reload +
        // settings push, so the live face reacts without a reload
        page.addChild(switchRow(R.string.wfcfg_dynamic_fps, R.string.wfcfg_dynamic_fps_summary,
                                getDynamicFps(), editable) { setDynamicFps(it) })

        // ── layer-merge optimization ──────────────────────────────────────────
        // The stat line reflects the LIVE engine (reported after each build);
        // toggling forces a reload of the active face, so refresh it shortly
        // after while the new numbers arrive.
        val stat = TextView(this).apply {
            setTextColor(0xFF999999.toInt()); textSize = 11f
            setPadding(0, 0, 0, dp(4))
        }
        fun refreshStat() {
            stat.text = when {
                !getOptimize() -> getString(R.string.wfcfg_optimize_stat_off)
                WatchfaceStats.mergedLayers > 0 -> getString(
                    R.string.wfcfg_optimize_stat, WatchfaceStats.mergedLayers,
                    WatchfaceStats.layerCount.coerceAtLeast(WatchfaceStats.mergedLayers),
                    WatchfaceStats.mergedRuns)
                WatchfaceStats.mergedLayers == 0 -> getString(R.string.wfcfg_optimize_stat_none)
                else -> "—"
            }
        }
        refreshStat()
        page.addChild(switchRow(R.string.wfcfg_optimize, R.string.wfcfg_optimize_summary,
                                getOptimize(), editable) { v ->
            setOptimize(v)
            // the pref write pushes a settings snapshot → the engine rebuilds →
            // fresh merged counts land asynchronously
            stat.postDelayed({ refreshStat() }, 1500)
            stat.postDelayed({ refreshStat() }, 4000)
            refreshStat()
        })
        page.addChild(stat)
        return page
    }

    // ── page 4: information ───────────────────────────────────────────────────
    private fun infoPage(activePath: String?): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
        }
        // Descriptive metadata from the .watch package header (memoised read).
        val meta = activePath?.let { p ->
            File(p).takeIf { it.isFile }?.let { WatchfaceMetadata.read(it) }
        }
        if (meta != null) {
            if (meta.name.isNotBlank())
                page.addChild(infoRow(getString(R.string.wfcfg_info_name), meta.name))
            if (meta.author.isNotBlank())
                page.addChild(infoRow(getString(R.string.wfcfg_info_author), meta.author))
            val format = (if (meta.buildSource == "studio") "2.0 (Studio)" else "1.0") +
                (if (meta.isProtected) " · " + getString(R.string.wfcfg_info_protected) else "")
            page.addChild(infoRow(getString(R.string.wfcfg_info_format), format))
            if (meta.description.isNotBlank())
                page.addChild(infoBlock(getString(R.string.wfcfg_info_desc), meta.description))
            if (meta.tags.isNotBlank())
                page.addChild(infoBlock(getString(R.string.wfcfg_info_tags), meta.tags))
            if (meta.featureCodes.isNotEmpty())
                page.addChild(infoBlock(getString(R.string.wfcfg_info_features),
                    WatchfaceMetadata.featureLabels(meta.featureCodes).joinToString(", ")))
        }
        // Layer count (reported by the Lua engine on load, for the active face).
        val layers = if (WatchfaceStats.layerCount >= 0) WatchfaceStats.layerCount.toString() else "—"
        page.addChild(infoRow(getString(R.string.wfcfg_info_layers), layers))

        // Watch face package size on disk.
        val sizeText = activePath?.let { p ->
            val f = File(p)
            if (f.isFile) formatBytes(f.length()) else null
        } ?: "—"
        page.addChild(infoRow(getString(R.string.wfcfg_info_size), sizeText))

        // Persisted script-variable backup size (reported by the Lua engine).
        val stored = if (WatchfaceStats.storageBytes >= 0) formatBytes(WatchfaceStats.storageBytes.toLong()) else "—"
        page.addChild(infoRow(getString(R.string.wfcfg_info_storage), stored))

        // Maintenance actions: reload the current face, clear its persisted vars.
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.wfcfg_reload)
            setTextColor(Color.WHITE)
            setOnClickListener { requestReload() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.wfcfg_info_clear)
            setTextColor(Color.WHITE)
            setOnClickListener { confirmClearVars() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        page.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        return page
    }

    /** Confirm, then ask the engine to delete this face's persisted variables and
     *  reload it. The dialog closes so the watchface + loading placeholder show. */
    private fun confirmClearVars() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wfcfg_clear_confirm_title)
            .setMessage(R.string.wfcfg_clear_confirm_msg)
            .setPositiveButton(R.string.wfcfg_info_clear) { _, _ ->
                // A changing value (timestamp) so every clear fires the listener.
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putLong(com.yzmm.clox.cocos.bridge.WatchfaceBridgeManager.KEY_CLEAR_VARS,
                             android.os.SystemClock.elapsedRealtime()).apply()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
        b >= 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> "$b B"
    }

    // ── reusable control builders ─────────────────────────────────────────────
    private fun useDefaultSwitch(checked: Boolean, onChange: (Boolean) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = getString(R.string.wfcfg_use_default); setTextColor(Color.WHITE); textSize = 14f
        })
        labels.addView(TextView(this).apply {
            text = getString(R.string.wfcfg_use_default_summary); setTextColor(0xFF999999.toInt()); textSize = 11f
        })
        box.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return box
    }

    /** Float size slider (0.5..2.0), with a precise-input button in the header. */
    private fun sizeSlider(label: String, initial: Float, enabled: Boolean, onChange: (Float) -> Unit): View =
        labeledSlider(label, WatchfaceConfigPrefs.MIN_SIZE, WatchfaceConfigPrefs.MAX_SIZE, 0.01f,
            initial.coerceIn(WatchfaceConfigPrefs.MIN_SIZE, WatchfaceConfigPrefs.MAX_SIZE), enabled,
            fmt = { String.format(Locale.US, "%.2f×", it) },
            parse = { it.toFloatOrNull() },
            onChange = onChange)

    /** Integer offset slider (±range px). */
    private fun offsetSlider(label: String, initial: Int, range: Int, enabled: Boolean, onChange: (Int) -> Unit): View =
        labeledSlider(label, -range.toFloat(), range.toFloat(), 1f, initial.toFloat().coerceIn(-range.toFloat(), range.toFloat()), enabled,
            fmt = { "${it.roundToInt()} px" },
            parse = { it.toFloatOrNull() },
            onChange = { onChange(it.roundToInt()) })

    private fun labeledSlider(
        label: String, from: Float, to: Float, step: Float, initial: Float, enabled: Boolean,
        fmt: (Float) -> String, parse: (String) -> Float?, onChange: (Float) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 14f }
        // Tapping the value opens the precise numeric-entry dialog.
        val valueText = TextView(this).apply {
            text = fmt(initial); setTextColor(0xFF82ABD1.toInt()); textSize = 14f; gravity = Gravity.END
            isEnabled = enabled
            if (enabled) {
                setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground))
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
        }
        header.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(valueText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        box.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val slider = Slider(this).apply {
            valueFrom = from; valueTo = to; stepSize = step
            value = initial.coerceIn(from, to)
            isEnabled = enabled
        }
        slider.addOnChangeListener { _, v, fromUser ->
            valueText.text = fmt(v)
            if (fromUser) onChange(v)
        }
        // Commit a value from any source (dialog, ± buttons): clamp, snap to the
        // slider, refresh the label, notify. Setting slider.value fires the change
        // listener with fromUser=false (so it won't double-call onChange).
        fun commit(v: Float) {
            val c = v.coerceIn(from, to)
            slider.value = c
            valueText.text = fmt(c)
            onChange(c)
        }
        valueText.setOnClickListener {
            if (enabled) promptValue(label, fmt(slider.value), parse) { commit(it) }
        }

        // Row: [−]  slider  [+]  — step buttons with press-and-hold auto-repeat.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(stepButton("−", enabled) { commit(slider.value - step) })
        row.addView(slider, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(stepButton("+", enabled) { commit(slider.value + step) })
        box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }

    /**
     * Small square repeat button: fires [onStep] on press, then auto-repeats with
     * mild acceleration while held (long-press to keep adjusting), stopping on release.
     */
    private fun stepButton(label: String, enabled: Boolean, onStep: () -> Unit): View {
        val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; textSize = 18f; isEnabled = enabled
            insetTop = 0; insetBottom = 0; minWidth = dp(44); minimumWidth = dp(44)
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
        }
        if (enabled) {
            val handler = android.os.Handler(mainLooper)
            var delay = 400L
            val repeat = object : Runnable {
                override fun run() {
                    onStep()
                    delay = (delay * 0.8f).toLong().coerceAtLeast(40L)  // accelerate
                    handler.postDelayed(this, delay)
                }
            }
            btn.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        onStep(); delay = 400L
                        handler.postDelayed(repeat, delay)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(repeat)
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                true
            }
        }
        return btn.apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), WRAP_CONTENT)
        }
    }

    private fun resolveAttr(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }

    private fun promptValue(label: String, current: String, parse: (String) -> Float?, apply: (Float) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current.filter { it.isDigit() || it == '.' || it == '-' })
            setSelection(text.length)
        }
        val pad = dp(20)
        val wrap = FrameLayout(this).apply { setPadding(pad, dp(8), pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                parse(input.text.toString().trim())?.let { apply(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchRow(labelRes: Int, summaryRes: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8)); isEnabled = enabled; alpha = if (enabled) 1f else 0.5f
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply { text = getString(labelRes); setTextColor(Color.WHITE); textSize = 14f })
        labels.addView(TextView(this).apply { text = getString(summaryRes); setTextColor(0xFF999999.toInt()); textSize = 11f })
        box.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(MaterialSwitch(this).apply {
            isChecked = checked; isEnabled = enabled
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return box
    }

    /** Label with a wrapped multi-line value below — for description/tags/features. */
    private fun infoBlock(label: String, value: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10))
        }
        box.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 14f })
        box.addView(TextView(this).apply {
            text = value; setTextColor(0xFF82ABD1.toInt()); textSize = 13f
            setPadding(0, dp(2), 0, 0)
        })
        return box
    }

    private fun infoRow(label: String, value: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10))
        }
        box.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 14f },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(TextView(this).apply { text = value; setTextColor(0xFF82ABD1.toInt()); textSize = 14f },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return box
    }

    // ── mode-aware accessors (per-face effective/raw, or global defaults) ──────
    private fun getAlignMode() = if (editingDefaults) cfg.gdefAlignMode(this) else cfg.alignMode(this, cfgPath)
    private fun setAlignMode(v: String) = if (editingDefaults) cfg.setGdefAlignMode(this, v) else cfg.setAlignMode(this, cfgPath, v)
    private fun getSizeX() = if (editingDefaults) cfg.gdefSizeX(this) else cfg.sizeX(this, cfgPath)
    private fun setSizeX(v: Float) = if (editingDefaults) cfg.setGdefSizeX(this, v) else cfg.setSizeX(this, cfgPath, v)
    private fun getSizeY() = if (editingDefaults) cfg.gdefSizeY(this) else cfg.sizeY(this, cfgPath)
    private fun setSizeY(v: Float) = if (editingDefaults) cfg.setGdefSizeY(this, v) else cfg.setSizeY(this, cfgPath, v)
    private fun getXOffset() = if (editingDefaults) cfg.gdefXOffset(this) else cfg.xOffset(this, cfgPath)
    private fun setXOffset(v: Int) = if (editingDefaults) cfg.setGdefXOffset(this, v) else cfg.setXOffset(this, cfgPath, v)
    private fun getYOffset() = if (editingDefaults) cfg.gdefYOffset(this) else cfg.yOffset(this, cfgPath)
    private fun setYOffset(v: Int) = if (editingDefaults) cfg.setGdefYOffset(this, v) else cfg.setYOffset(this, cfgPath, v)

    private fun getAllowSound() = if (editingDefaults) cfg.gdefAllowSound(this) else cfg.allowSound(this, cfgPath)
    private fun setAllowSound(v: Boolean) = if (editingDefaults) cfg.setGdefAllowSound(this, v) else cfg.setAllowSound(this, cfgPath, v)
    private fun getAllowVibrate() = if (editingDefaults) cfg.gdefAllowVibrate(this) else cfg.allowVibrate(this, cfgPath)
    private fun setAllowVibrate(v: Boolean) = if (editingDefaults) cfg.setGdefAllowVibrate(this, v) else cfg.setAllowVibrate(this, cfgPath, v)
    private fun getAllowSensors() = if (editingDefaults) cfg.gdefAllowSensors(this) else cfg.allowSensors(this, cfgPath)
    private fun setAllowSensors(v: Boolean) = if (editingDefaults) cfg.setGdefAllowSensors(this, v) else cfg.setAllowSensors(this, cfgPath, v)
    private fun getAllowWeather() = if (editingDefaults) cfg.gdefAllowWeather(this) else cfg.allowWeather(this, cfgPath)
    private fun setAllowWeather(v: Boolean) = if (editingDefaults) cfg.setGdefAllowWeather(this, v) else cfg.setAllowWeather(this, cfgPath, v)
    private fun getAllowHealth() = if (editingDefaults) cfg.gdefAllowHealth(this) else cfg.allowHealth(this, cfgPath)
    private fun setAllowHealth(v: Boolean) = if (editingDefaults) cfg.setGdefAllowHealth(this, v) else cfg.setAllowHealth(this, cfgPath, v)
    private fun getAllowTap() = if (editingDefaults) cfg.gdefAllowTap(this) else cfg.allowTap(this, cfgPath)
    private fun setAllowTap(v: Boolean) = if (editingDefaults) cfg.setGdefAllowTap(this, v) else cfg.setAllowTap(this, cfgPath, v)

    private fun getMaxFps() = if (editingDefaults) cfg.gdefMaxFps(this) else cfg.maxFps(this, cfgPath)
    private fun setMaxFps(v: Int) = if (editingDefaults) cfg.setGdefMaxFps(this, v) else cfg.setMaxFps(this, cfgPath, v)
    private fun getUnlimited() = if (editingDefaults) cfg.gdefUnlimited(this) else cfg.unlimited(this, cfgPath)
    private fun setUnlimited(v: Boolean) = if (editingDefaults) cfg.setGdefUnlimited(this, v) else cfg.setUnlimited(this, cfgPath, v)
    private fun getOptimize() = if (editingDefaults) cfg.gdefOptimize(this) else cfg.optimize(this, cfgPath)
    private fun setOptimize(v: Boolean) = if (editingDefaults) cfg.setGdefOptimize(this, v) else cfg.setOptimize(this, cfgPath, v)
    private fun getDynamicFps() = if (editingDefaults) cfg.gdefDynamicFps(this) else cfg.dynamicFps(this, cfgPath)
    private fun setDynamicFps(v: Boolean) = if (editingDefaults) cfg.setGdefDynamicFps(this, v) else cfg.setDynamicFps(this, cfgPath, v)
    private fun getColorMode() = if (editingDefaults) cfg.gdefColorMode(this) else cfg.colorMode(this, cfgPath)
    private fun setColorMode(v: String) = if (editingDefaults) cfg.setGdefColorMode(this, v) else cfg.setColorMode(this, cfgPath, v)
    private fun getColorValue() = if (editingDefaults) cfg.gdefColorValue(this) else cfg.colorValue(this, cfgPath)
    private fun setColorValue(v: String) = if (editingDefaults) cfg.setGdefColorValue(this, v) else cfg.setColorValue(this, cfgPath, v)

    // small helper: add a full-width child to a LinearLayout
    private fun LinearLayout.addChild(v: View) =
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

/** A ScrollView that never grows past [maxHeightPx], so callers can reserve screen space. */
private class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
