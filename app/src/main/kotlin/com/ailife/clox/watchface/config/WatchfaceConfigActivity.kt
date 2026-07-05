package com.ailife.clox.watchface.config

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
import com.google.android.material.slider.Slider
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.ailife.clox.rtos.RtosManager
import com.ailife.clox.watchface.WatchfaceConfigPrefs
import com.ailife.clox.watchface.WatchfacePrefs
import java.io.File
import kotlin.math.roundToInt

/**
 * Per-watchface configuration dialog. Two pages: Position (scale + X/Y offset)
 * and Frame rate (max FPS). Every change writes straight to [WatchfaceConfigPrefs]
 * for the active face, which re-pushes the settings snapshot to the Lua engine —
 * so the watchface behind this translucent panel updates live.
 *
 * A watch screen has no room for a fixed header/footer alongside scrollable content,
 * so the whole panel scrolls as one unit when it doesn't fit.
 *
 * [isActive] tells [com.ailife.clox.MainActivity] to keep the Cocos GL view rendering
 * while this dialog is up — otherwise the launcher's normal onPause behavior (pausing
 * rendering whenever something else comes to front) would freeze the watchface and
 * defeat the live preview.
 */
class WatchfaceConfigActivity : AppCompatActivity() {

    private lateinit var cfgPath: String

    companion object {
        @Volatile var isActive: Boolean = false
            private set

        fun start(ctx: Context) {
            isActive = true
            ctx.startActivity(Intent(ctx, WatchfaceConfigActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        val active = WatchfacePrefs.getActivePath(this)
        cfgPath = WatchfaceConfigPrefs.pathFor(active)
        setContentView(buildRoot(active))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // This dialog sits on top of MainActivity, which is always fullscreen (see
    // MainActivity.hideSystemBars) — without re-hiding the bars on our own window,
    // taking focus here lets the status/nav bars reappear over the whole screen.
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())

        val mode = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("nav_bar_mode", "applist") ?: "applist"
        val showNav = !RtosManager.isActive && mode == "show"
        if (showNav) controller.show(WindowInsetsCompat.Type.navigationBars())
        else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    // ── root scrim + bottom panel ─────────────────────────────────────────────
    private fun buildRoot(activePath: String?): View {
        val root = FrameLayout(this)
        // Tap outside the panel dismisses.
        root.setOnClickListener { finish() }

        val card = MaterialCardView(this).apply {
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, dp(20).toFloat())
                .setTopRightCorner(CornerFamily.ROUNDED, dp(20).toFloat())
                .build()
            setCardBackgroundColor(0xD91A1A1A.toInt())
            cardElevation = dp(8).toFloat()
            strokeWidth = 0
            isClickable = true   // swallow taps so they don't dismiss
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        content.addView(title(faceLabel(activePath)))

        val flipper = ViewFlipper(this)
        content.addView(tabRow(flipper), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        val pageLp = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        flipper.addView(positionPage(), pageLp)
        flipper.addView(frameRatePage(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(flipper, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        content.addView(MaterialButton(this).apply {
            text = "Done"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        // A watch screen has no room for a fixed header/footer, so the whole
        // panel — title, tabs, pages and Done button — scrolls as one unit
        // whenever it doesn't fit the window.
        val maxCardHeight = realScreenSize().y
        val scroll = MaxHeightScrollView(this, maxCardHeight).apply {
            isVerticalScrollBarEnabled = false
        }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        card.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addChild(card)
        (card.layoutParams as FrameLayout.LayoutParams).apply {
            width = MATCH_PARENT; height = WRAP_CONTENT; gravity = Gravity.BOTTOM
        }

        return root
    }

    private fun faceLabel(activePath: String?): String =
        if (activePath.isNullOrEmpty()) "Default watch face"
        else File(activePath).nameWithoutExtension

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(0, 0, 0, dp(8))
    }

    // ── tab row toggling the flipper ──────────────────────────────────────────
    private fun tabRow(flipper: ViewFlipper): View {
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val labels = listOf("Position", "Frame rate")
        val buttons = labels.map { label ->
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                setTextColor(Color.WHITE)
            }
        }
        buttons.forEach { group.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        group.check(buttons[0].id)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx >= 0) flipper.displayedChild = idx
        }
        return group
    }

    // ── page 1: scale + offsets ───────────────────────────────────────────────
    private fun positionPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Scale: 0.5..2.0 across 100 steps
        val cfg = WatchfaceConfigPrefs
        val scaleRange = cfg.MAX_SCALE - cfg.MIN_SCALE
        val scale0 = cfg.scale(this, cfgPath)
        page.addChild(labeledSlider(
            "Scale", 100, (((scale0 - cfg.MIN_SCALE) / scaleRange) * 100).roundToInt(),
            fmt = { p -> String.format("%.2f×", cfg.MIN_SCALE + scaleRange * p / 100f) },
            onChange = { p -> cfg.setScale(this, cfgPath, cfg.MIN_SCALE + scaleRange * p / 100f) }
        ))

        val r = cfg.OFFSET_RANGE
        page.addChild(labeledSlider(
            "Horizontal offset", 2 * r, cfg.xOffset(this, cfgPath) + r,
            fmt = { p -> "${p - r} px" },
            onChange = { p -> cfg.setXOffset(this, cfgPath, p - r) }
        ))
        page.addChild(labeledSlider(
            "Vertical offset", 2 * r, cfg.yOffset(this, cfgPath) + r,
            fmt = { p -> "${p - r} px" },
            onChange = { p -> cfg.setYOffset(this, cfgPath, p - r) }
        ))

        page.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Reset position"
            setOnClickListener {
                cfg.setScale(this@WatchfaceConfigActivity, cfgPath, cfg.DEF_SCALE)
                cfg.setXOffset(this@WatchfaceConfigActivity, cfgPath, cfg.DEF_OFFSET)
                cfg.setYOffset(this@WatchfaceConfigActivity, cfgPath, cfg.DEF_OFFSET)
                recreate()
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        return page
    }

    private fun labeledSlider(
        label: String, max: Int, initial: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 14f }
        val valueText = TextView(this).apply {
            text = fmt(initial); setTextColor(0xFF82ABD1.toInt()); textSize = 14f
            gravity = Gravity.END
        }
        header.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(valueText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        box.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val initialClamped = initial.coerceIn(0, max)
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = max.toFloat()
            stepSize = 1f
            value = initialClamped.toFloat()
            addOnChangeListener { _, v, _ ->
                val p = v.roundToInt()
                valueText.text = fmt(p)
                onChange(p)
            }
        }
        box.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }

    // ── page 2: max FPS ───────────────────────────────────────────────────────
    private fun frameRatePage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val cfg = WatchfaceConfigPrefs
        val unlimited = cfg.unlimited(this, cfgPath)
        val current = cfg.maxFps(this, cfgPath)

        val group = MaterialButtonToggleGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            isSingleSelection = true
            isSelectionRequired = true
        }
        val options = cfg.FPS_CHOICES.map { "$it FPS" } + "Unlimited"
        val buttons = options.map { label ->
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                setTextColor(Color.WHITE)
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
            if (idx == cfg.FPS_CHOICES.size) {
                cfg.setUnlimited(this, cfgPath, true)
            } else if (idx in cfg.FPS_CHOICES.indices) {
                cfg.setMaxFps(this, cfgPath, cfg.FPS_CHOICES[idx])
                cfg.setUnlimited(this, cfgPath, false)
            }
        }
        page.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        page.addView(TextView(this).apply {
            text = "Ambient (AOD) is always capped at 1 FPS to save power."
            setTextColor(0xFF999999.toInt())
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
        return page
    }

    // small helper: add a full-width child to a LinearLayout
    private fun LinearLayout.addChild(v: View) =
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    private fun FrameLayout.addChild(v: View) =
        addView(v, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

/** A ScrollView that never grows past [maxHeightPx], so callers can reserve screen space. */
private class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
