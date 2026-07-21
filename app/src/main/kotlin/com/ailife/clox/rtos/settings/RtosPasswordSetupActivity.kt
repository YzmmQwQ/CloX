package com.ailife.clox.rtos.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ailife.clox.R
import com.ailife.clox.rtos.RtosPrefs
import com.ailife.clox.rtos.password.PasswordValidator

/**
 * Password setup flow:
 * Step 1 — Explain screen (5 s countdown before proceed button activates)
 * Step 2 — Input screen (first entry)
 * Step 3 — Confirm screen (must match first entry)
 */
class RtosPasswordSetupActivity : AppCompatActivity() {

    private lateinit var contentFrame: FrameLayout

    private val passwordType by lazy { RtosPrefs.passwordType(this) }

    // Collected during setup
    private var firstEntry: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        contentFrame = FrameLayout(this)
        root.addView(contentFrame, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        showExplainScreen()
    }

    // ── Step 1: Explain ───────────────────────────────────────────────────────

    private fun showExplainScreen() {
        contentFrame.removeAllViews()
        val col = columnLayout()

        val title = label(getString(R.string.rtos_pw_how_it_works), 22f, Color.WHITE)
        col.addView(title)
        col.addView(spacer(dp(16)))

        val body = if (passwordType == RtosPrefs.PASSWORD_KEYPAD)
            getString(R.string.rtos_pw_keypad_explain)
        else
            getString(R.string.rtos_pw_morse_explain)

        val bodyTv = label(body, 13f, getColor(R.color.rtos_body_text))
        bodyTv.gravity = Gravity.START
        col.addView(bodyTv)
        col.addView(spacer(dp(32)))

        val btn = roundedButton(getString(R.string.rtos_pw_understood_countdown, 5))
            .apply { isEnabled = false; alpha = 0.5f }
        col.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ))

        contentFrame.addView(scrollWrap(col), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        object : CountDownTimer(5000, 1000) {
            override fun onTick(ms: Long) {
                val s = (ms / 1000 + 1).toInt()
                btn.text = getString(R.string.rtos_pw_understood_countdown, s)
            }
            override fun onFinish() {
                btn.text = getString(R.string.rtos_pw_understood_done)
                btn.isEnabled = true
                btn.alpha = 1f
                btn.setOnClickListener { showInputScreen(isConfirm = false) }
            }
        }.start()
    }

    // ── Step 2 & 3: Input / Confirm ───────────────────────────────────────────

    private val inputWindow = mutableListOf<Int>()
    private var inputContentView: FrameLayout? = null
    private var inputIndicator: TextView? = null

    private fun showInputScreen(isConfirm: Boolean) {
        contentFrame.removeAllViews()
        inputWindow.clear()

        val title = if (isConfirm) getString(R.string.rtos_pw_confirm_title)
                    else getString(R.string.rtos_pw_set_title)
        val hint = if (passwordType == RtosPrefs.PASSWORD_KEYPAD)
            getString(R.string.rtos_pw_keypad_hint)
        else
            getString(R.string.rtos_pw_morse_hint)

        val col = columnLayout()
        col.addView(label(title, 20f, Color.WHITE))
        col.addView(spacer(dp(8)))
        col.addView(label(hint, 13f, getColor(R.color.rtos_hint_text)))
        col.addView(spacer(dp(24)))

        val indicator = label("", 18f, getColor(R.color.rtos_accent_blue))
        indicator.gravity = Gravity.CENTER
        col.addView(indicator)
        inputIndicator = indicator
        col.addView(spacer(dp(16)))

        val clearBtn = roundedButton(getString(R.string.rtos_pw_clear)).apply {
            setOnClickListener {
                inputWindow.clear()
                updateIndicator()
            }
        }
        col.addView(clearBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ))
        col.addView(spacer(dp(8)))

        val doneBtn = roundedButton(getString(R.string.rtos_pw_done)).apply {
            setOnClickListener { onDonePressed(isConfirm) }
        }
        col.addView(doneBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ))

        // The big input surface
        val surface = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(getColor(R.color.rtos_bg))
                cornerRadius = dp(12).toFloat()
            }
        }

        if (passwordType == RtosPrefs.PASSWORD_KEYPAD) {
            // Draw grid lines to help during setup
            drawKeypadGrid(surface)
            surface.setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    val col_ = (ev.x / v.width * 3).toInt().coerceIn(0, 2)
                    val row_ = (ev.y / v.height * 3).toInt().coerceIn(0, 2)
                    val digit = row_ * 3 + col_ + 1
                    inputWindow.add(digit)
                    updateIndicator()
                }
                true
            }
        } else {
            var downMs = 0L
            surface.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downMs = ev.eventTime
                    MotionEvent.ACTION_UP -> {
                        val sym = if (ev.eventTime - downMs >= 500L) 1 else 0
                        inputWindow.add(sym)
                        updateIndicator()
                    }
                }
                true
            }
        }

        inputContentView = surface
        col.addView(surface, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
        ).apply { topMargin = dp(16) })

        contentFrame.addView(scrollWrap(col), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun updateIndicator() {
        val sym = if (passwordType == RtosPrefs.PASSWORD_KEYPAD)
            inputWindow.joinToString("-")
        else
            inputWindow.joinToString("") { if (it == 0) "·" else "—" }
        inputIndicator?.text = sym
    }

    private fun onDonePressed(isConfirm: Boolean) {
        val valid = if (passwordType == RtosPrefs.PASSWORD_KEYPAD)
            PasswordValidator.isValidKeypadPassword(inputWindow)
        else
            PasswordValidator.isValidMorsePassword(inputWindow)

        if (!valid) {
            Toast.makeText(this, R.string.toast_rtos_password_too_short, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isConfirm) {
            firstEntry = inputWindow.toList()
            showInputScreen(isConfirm = true)
        } else {
            if (inputWindow == firstEntry) {
                val hash = PasswordValidator.hash(firstEntry)
                RtosPrefs.setPassword(this, firstEntry, hash)
                Toast.makeText(this, R.string.toast_rtos_password_saved, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, R.string.toast_rtos_passwords_dont_match, Toast.LENGTH_SHORT).show()
                showInputScreen(isConfirm = true)
            }
        }
    }

    private fun drawKeypadGrid(surface: FrameLayout) {
        surface.post {
            val w = surface.width; val h = surface.height
            val lineColor = getColor(R.color.rtos_line)
            // Vertical lines
            for (i in 1..2) {
                val x = w * i / 3
                val line = View(this).apply { setBackgroundColor(lineColor) }
                surface.addView(line, FrameLayout.LayoutParams(2, h).apply { leftMargin = x })
            }
            // Horizontal lines
            for (i in 1..2) {
                val y = h * i / 3
                val line = View(this).apply { setBackgroundColor(lineColor) }
                surface.addView(line, FrameLayout.LayoutParams(w, 2).apply { topMargin = y })
            }
            // Zone numbers
            for (row in 0..2) for (col in 0..2) {
                val digit = row * 3 + col + 1
                val tv = TextView(this).apply {
                    text = digit.toString()
                    textSize = 22f
                    setTextColor(getColor(R.color.rtos_dim_text))
                    gravity = Gravity.CENTER
                }
                surface.addView(tv, FrameLayout.LayoutParams(w / 3, h / 3).apply {
                    leftMargin = col * w / 3
                    topMargin = row * h / 3
                })
            }
        }
    }

    // ── View helpers ───────────────────────────────────────────────────────────

    private fun scrollWrap(col: LinearLayout) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun columnLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(48), dp(24), dp(24))
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun spacer(height: Int) = View(this).also { v ->
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
    }

    private fun roundedButton(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(getColor(R.color.rtos_highlight))
            cornerRadius = dp(28).toFloat()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
