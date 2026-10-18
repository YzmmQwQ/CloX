package com.yzmm.clox.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

class ButtonMappingHandler(private val ctx: Context, private val gestureHandler: GestureHandler) {

    private val handler = Handler(Looper.getMainLooper())
    // State is keyed by keycode so multiple mapped buttons don't clobber each other's timers.
    private val longPressRunnables = HashMap<Int, Runnable>()
    private val longPressTriggered = HashMap<Int, Boolean>()
    private val pendingSingleRunnables = HashMap<Int, Runnable>()

    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!ButtonMappingPrefs.isMapped(ctx, keyCode)) return false
        if (event.repeatCount != 0) return true  // swallow repeats

        longPressTriggered[keyCode] = false
        val r = Runnable {
            longPressTriggered[keyCode] = true
            pendingSingleRunnables.remove(keyCode)?.let { handler.removeCallbacks(it) }
            fire(keyCode, ButtonMappingPrefs.PRESS_LONG)
        }
        longPressRunnables[keyCode] = r
        handler.postDelayed(r, 500L)
        return true
    }

    fun handleKeyUp(keyCode: Int, @Suppress("UNUSED_PARAMETER") event: KeyEvent): Boolean {
        if (!ButtonMappingPrefs.isMapped(ctx, keyCode)) return false

        longPressRunnables.remove(keyCode)?.let { handler.removeCallbacks(it) }

        if (longPressTriggered[keyCode] == true) return true

        val pending = pendingSingleRunnables[keyCode]
        if (pending != null) {
            // Second tap within window → double press
            handler.removeCallbacks(pending)
            pendingSingleRunnables.remove(keyCode)
            fire(keyCode, ButtonMappingPrefs.PRESS_DOUBLE)
        } else if (ButtonMappingPrefs.getAction(ctx, keyCode, ButtonMappingPrefs.PRESS_DOUBLE) == "nothing") {
            // Nothing bound to double press — fire immediately instead of
            // making every single press wait out the double-tap window.
            fire(keyCode, ButtonMappingPrefs.PRESS_SINGLE)
        } else {
            val r = Runnable {
                pendingSingleRunnables.remove(keyCode)
                fire(keyCode, ButtonMappingPrefs.PRESS_SINGLE)
            }
            pendingSingleRunnables[keyCode] = r
            handler.postDelayed(r, 300L)
        }
        return true
    }

    private fun fire(keyCode: Int, press: String) {
        val action = ButtonMappingPrefs.getAction(ctx, keyCode, press)
        val app = GestureAppPref.getComponent(ctx, ButtonMappingPrefs.actionKey(keyCode, press))
        gestureHandler.executeAction(action, app)
    }
}
