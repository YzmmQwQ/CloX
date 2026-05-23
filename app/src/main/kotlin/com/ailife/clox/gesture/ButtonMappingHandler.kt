package com.ailife.clox.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

class ButtonMappingHandler(private val ctx: Context, private val gestureHandler: GestureHandler) {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false
    private var pendingSingleRunnable: Runnable? = null

    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val mapped = ButtonMappingPrefs.getMappedKeyCode(ctx)
        if (mapped == 0 || keyCode != mapped) return false
        if (event.repeatCount != 0) return true  // swallow repeats

        longPressTriggered = false
        val r = Runnable {
            longPressTriggered = true
            pendingSingleRunnable?.let { handler.removeCallbacks(it) }
            pendingSingleRunnable = null
            gestureHandler.executeAction(ButtonMappingPrefs.getLongAction(ctx))
        }
        longPressRunnable = r
        handler.postDelayed(r, 500L)
        return true
    }

    fun handleKeyUp(keyCode: Int, @Suppress("UNUSED_PARAMETER") event: KeyEvent): Boolean {
        val mapped = ButtonMappingPrefs.getMappedKeyCode(ctx)
        if (mapped == 0 || keyCode != mapped) return false

        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null

        if (longPressTriggered) return true

        if (pendingSingleRunnable != null) {
            // Second tap within window → double press
            handler.removeCallbacks(pendingSingleRunnable!!)
            pendingSingleRunnable = null
            gestureHandler.executeAction(ButtonMappingPrefs.getDoubleAction(ctx))
        } else {
            val r = Runnable {
                pendingSingleRunnable = null
                gestureHandler.executeAction(ButtonMappingPrefs.getSingleAction(ctx))
            }
            pendingSingleRunnable = r
            handler.postDelayed(r, 300L)
        }
        return true
    }
}
