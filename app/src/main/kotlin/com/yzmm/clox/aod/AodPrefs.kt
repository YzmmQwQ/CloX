package com.yzmm.clox.aod

object AodPrefs {
    const val KEY_ENABLED                  = "aod_enabled"
    const val KEY_TIMEOUT_SECONDS          = "aod_timeout_seconds"
    const val KEY_BRIGHTNESS               = "aod_brightness"
    const val KEY_REAWAKEN_ON_SCREEN_OFF   = "aod_reawaken_on_screen_off"
    const val KEY_LOCK_GESTURE             = "aod_lock_gesture"
    const val KEY_LOCK_GESTURE_DELAY       = "aod_lock_gesture_delay_seconds"
    const val KEY_SHALLOW_SLEEP            = "aod_shallow_sleep"
    const val KEY_POWER_BUTTON_EXIT        = "aod_power_button_exit"
    const val KEY_ALWAYS_WHEN_CHARGING     = "aod_always_when_charging"

    // Written by AodManager (not user-facing) to notify Lua of AOD state
    const val KEY_LUA_AOD_ACTIVE           = "watchface_aod_enabled"

    const val DEFAULT_TIMEOUT_SECONDS      = 30
    const val DEFAULT_BRIGHTNESS           = 10
    const val DEFAULT_LOCK_GESTURE_DELAY   = 5
}
