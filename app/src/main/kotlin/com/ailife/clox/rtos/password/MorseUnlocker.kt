package com.ailife.clox.rtos.password

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent

/**
 * Long/short press on watchface maps to dash (1) / dot (0).
 * Press duration >= [LONG_THRESHOLD_MS] = dash, shorter = dot.
 * Symbol is registered on finger-up.
 * Sliding window checked after each symbol.
 */
class MorseUnlocker(
    private val ctx: Context,
    private val password: List<Int>,
    val onUnlocked: () -> Unit
) {

    companion object {
        private const val LONG_THRESHOLD_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val window = ArrayDeque<Int>(64)
    private var downTime = 0L

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> downTime = ev.eventTime
            MotionEvent.ACTION_UP -> {
                val duration = ev.eventTime - downTime
                val symbol = if (duration >= LONG_THRESHOLD_MS) 1 else 0
                window.addLast(symbol)
                if (window.size > 64) window.removeFirst()
                check()
            }
        }
    }

    private fun check() {
        if (password.isEmpty()) return
        if (PasswordValidator.slidingMatch(window.toList(), password)) {
            window.clear()
            onUnlocked()
        }
    }
}
