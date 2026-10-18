package com.yzmm.clox.rtos

import android.content.Context
import android.view.MotionEvent
import com.yzmm.clox.rtos.password.KeypadUnlocker
import com.yzmm.clox.rtos.password.MorseUnlocker
import com.yzmm.clox.rtos.password.PasswordValidator

/**
 * Holds the active unlocker instance for the current RTOS session.
 * MainActivity routes all touches here when [RtosManager.isActive].
 */
class RtosUnlockHandler(ctx: Context, onUnlocked: () -> Unit) {

    private val passwordType = RtosPrefs.passwordType(ctx)
    private val sequence     = RtosPrefs.passwordSequence(ctx)

    private val keypad: KeypadUnlocker? =
        if (passwordType == RtosPrefs.PASSWORD_KEYPAD && sequence.isNotEmpty())
            KeypadUnlocker(ctx, RtosPrefs.passwordHash(ctx) ?: "", sequence, onUnlocked)
        else null

    private val morse: MorseUnlocker? =
        if (passwordType == RtosPrefs.PASSWORD_MORSE && sequence.isNotEmpty())
            MorseUnlocker(ctx, sequence, onUnlocked)
        else null

    /** Call from MainActivity.dispatchTouchEvent when RTOS is active. */
    fun onTouch(ev: MotionEvent, viewWidth: Int, viewHeight: Int) {
        keypad?.onTouch(ev, viewWidth, viewHeight)
        morse?.onTouch(ev)
    }
}
