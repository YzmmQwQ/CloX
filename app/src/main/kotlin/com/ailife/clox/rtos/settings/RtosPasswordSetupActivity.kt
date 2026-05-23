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

        val title = label("How it works", 22f, Color.WHITE)
        col.addView(title)
        col.addView(spacer(dp(16)))

        val body = if (passwordType == RtosPrefs.PASSWORD_KEYPAD) {
            "The watch screen is divided into an invisible 3×3 grid:\n\n" +
            "  [ 1 ][ 2 ][ 3 ]\n  [ 4 ][ 5 ][ 6 ]\n  [ 7 ][ 8 ][ 9 ]\n\n" +
            "Tap the grid zones to enter your code. There is no visual feedback.\n\n" +
            "The system checks after every tap using a sliding window — " +
            "as long as your code appears anywhere in your recent taps it will unlock.\n\n" +
            "Your code must be at least 4 digits and not all the same."
        } else {
            "Tap anywhere on the watch face to enter Morse code:\n\n" +
            "  Short press = dot  (·)\n  Long press  = dash (—)\n\n" +
            "There is no visual feedback. The system checks after each press.\n\n" +
            "Your code must be at least 4 symbols and not all the same."
        }

        val bodyTv = label(body, 13f, Color.parseColor("#CCCCCC"))
        bodyTv.gravity = Gravity.START
        col.addView(bodyTv)
        col.addView(spacer(dp(32)))

        val btn = roundedButton("Understood (5…)").apply { isEnabled = false; alpha = 0.5f }
        col.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ))

        contentFrame.addView(scrollWrap(col), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        object : CountDownTimer(5000, 1000) {
            override fun onTick(ms: Long) {
                val s = (ms / 1000 + 1).toInt()
                btn.text = "Understood ($s…)"
            }
            override fun onFinish() {
                btn.text = "Understood — Set Password"
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

        val title = if (isConfirm) "Confirm Password" else "Set Password"
        val hint = if (passwordType == RtosPrefs.PASSWORD_KEYPAD)
            "Tap the grid zones to enter your code"
        else
            "Short/long press to enter Morse dots and dashes"

        val col = columnLayout()
        col.addView(label(title, 20f, Color.WHITE))
        col.addView(spacer(dp(8)))
        col.addView(label(hint, 13f, Color.parseColor("#AAAAAA")))
        col.addView(spacer(dp(24)))

        val indicator = label("", 18f, Color.parseColor("#4FC3F7"))
        indicator.gravity = Gravity.CENTER
        col.addView(indicator)
        inputIndicator = indicator
        col.addView(spacer(dp(16)))

        val clearBtn = roundedButton("Clear").apply {
            setOnClickListener {
                inputWindow.clear()
                updateIndicator()
            }
        }
        col.addView(clearBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ))
        col.addView(spacer(dp(8)))

        val doneBtn = roundedButton("Done").apply {
            setOnClickListener { onDonePressed(isConfirm) }
        }
        col.addView(doneBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ))

        // The big input surface
        val surface = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111111"))
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
            Toast.makeText(this,
                "Need at least 4 symbols, not all the same", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isConfirm) {
            firstEntry = inputWindow.toList()
            showInputScreen(isConfirm = true)
        } else {
            if (inputWindow == firstEntry) {
                val hash = PasswordValidator.hash(firstEntry)
                RtosPrefs.setPassword(this, firstEntry, hash)
                Toast.makeText(this, "Password saved", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Passwords don't match — try again", Toast.LENGTH_SHORT).show()
                showInputScreen(isConfirm = true)
            }
        }
    }

    private fun drawKeypadGrid(surface: FrameLayout) {
        surface.post {
            val w = surface.width; val h = surface.height
            val lineColor = Color.parseColor("#333333")
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
                    setTextColor(Color.parseColor("#555555"))
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
            setColor(Color.parseColor("#1F4068"))
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
