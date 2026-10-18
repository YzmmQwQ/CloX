package com.yzmm.clox.rtos.password

import android.content.Context
import android.view.MotionEvent

/**
 * Maps tap positions on a 3×3 invisible grid to digits 1–9:
 *   1 2 3
 *   4 5 6
 *   7 8 9
 *
 * Keeps a rolling input window and checks it against the stored password hash
 * after each new tap. Call [onUnlocked] when a match is found.
 */
class KeypadUnlocker(
    private val ctx: Context,
    private val storedHash: String,
    private val password: List<Int>,     // decoded from hash not stored; caller passes it
    val onUnlocked: () -> Unit
) {

    private val window = ArrayDeque<Int>(32)

    fun onTouch(ev: MotionEvent, viewWidth: Int, viewHeight: Int) {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return
        val col = (ev.x / viewWidth * 3).toInt().coerceIn(0, 2)
        val row = (ev.y / viewHeight * 3).toInt().coerceIn(0, 2)
        val digit = row * 3 + col + 1   // 1..9
        window.addLast(digit)
        if (window.size > 64) window.removeFirst()
        check()
    }

    private fun check() {
        if (password.isEmpty()) return
        if (PasswordValidator.slidingMatch(window.toList(), password)) {
            window.clear()
            onUnlocked()
        }
    }
}
