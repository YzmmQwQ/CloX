package com.ailife.clox.watchface.config

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ViewFlipper
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
 * The visual dialog is intentionally lightweight (plain views, no AppCompat) so it
 * layers over the launcher home; the dev team can restyle it freely later.
 */
class WatchfaceConfigActivity : Activity() {

    private lateinit var cfgPath: String

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, WatchfaceConfigActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = WatchfacePrefs.getActivePath(this)
        cfgPath = WatchfaceConfigPrefs.pathFor(active)
        setContentView(buildRoot(active))
    }

    // ── root scrim + bottom panel ─────────────────────────────────────────────
    private fun buildRoot(activePath: String?): View {
        val root = FrameLayout(this)
        // Tap outside the panel dismisses.
        root.setOnClickListener { finish() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(0xF01A1A1A.toInt())
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            isClickable = true   // swallow taps so they don't dismiss
        }
        root.addChild(panel)
        (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = MATCH_PARENT; height = WRAP_CONTENT; gravity = Gravity.BOTTOM
        }

        panel.addView(title(faceLabel(activePath)))

        val flipper = ViewFlipper(this)
        val tabs = tabRow(flipper)
        panel.addView(tabs)
        val pageLp = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        flipper.addView(positionPage(), pageLp)
        flipper.addView(frameRatePage(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(flipper, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        panel.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

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
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("Position", "Frame rate")
        val tabViews = mutableListOf<TextView>()
        labels.forEachIndexed { i, label ->
            val tab = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    flipper.displayedChild = i
                    tabViews.forEachIndexed { j, tv -> tv.alpha = if (j == i) 1f else 0.5f }
                }
            }
            tab.alpha = if (i == 0) 1f else 0.5f
            tabViews.add(tab)
            row.addView(tab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return row
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

        page.addView(Button(this).apply {
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
        val value = TextView(this).apply {
            text = fmt(initial); setTextColor(0xFF82ABD1.toInt()); textSize = 14f
            gravity = Gravity.END
        }
        header.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        box.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val bar = SeekBar(this).apply {
            this.max = max
            progress = initial.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = fmt(p)
                    onChange(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

        val group = RadioGroup(this)
        val options = cfg.FPS_CHOICES.map { "$it FPS" } + "Unlimited"
        options.forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = label
                setTextColor(Color.WHITE)
            })
        }
        // initial selection
        val sel = when {
            unlimited -> 1000 + cfg.FPS_CHOICES.size
            cfg.FPS_CHOICES.indexOf(current) >= 0 -> 1000 + cfg.FPS_CHOICES.indexOf(current)
            else -> 1000 + cfg.FPS_CHOICES.indexOf(cfg.DEF_MAX_FPS).coerceAtLeast(0)
        }
        group.check(sel)
        group.setOnCheckedChangeListener { _, checkedId ->
            val idx = checkedId - 1000
            if (idx == cfg.FPS_CHOICES.size) {
                cfg.setUnlimited(this, cfgPath, true)
            } else if (idx in cfg.FPS_CHOICES.indices) {
                cfg.setMaxFps(this, cfgPath, cfg.FPS_CHOICES[idx])
                cfg.setUnlimited(this, cfgPath, false)
            }
        }
        page.addView(group)

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
