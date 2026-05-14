package com.ailife.clox.cocos

import android.util.Log

object LuaBridge {

    private const val TAG = "LuaBridge"

    // Called from Android → Lua
    external fun callLuaFunction(funcName: String, args: String): String

    // Called from Android → native touch pipeline
    external fun passTouchNative(x: Float, y: Float, action: Int)

    fun passTouch(x: Float, y: Float, action: Int) {
        try {
            passTouchNative(x, y, action)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not loaded — touch dropped")
        }
    }

    // Called from native (JNI) → Kotlin/Android
    @JvmStatic
    fun onLuaEvent(event: String, payload: String) {
        Log.d(TAG, "onLuaEvent: $event  payload=$payload")
        if (event == "wf_action") {
            watchfaceActionHandler?.invoke(payload)
        }
        eventListeners.forEach { it(event, payload) }
    }

    private val eventListeners = mutableListOf<(String, String) -> Unit>()

    fun addEventListner(listener: (String, String) -> Unit) {
        eventListeners += listener
    }

    fun removeEventListener(listener: (String, String) -> Unit) {
        eventListeners -= listener
    }

    /** Set by WatchfaceBridgeManager to handle actions sent from Lua (wf_action events). */
    var watchfaceActionHandler: ((String) -> Unit)? = null
}
