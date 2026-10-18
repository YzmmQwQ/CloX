package com.yzmm.clox.rtos

import android.content.Context
import android.widget.Toast
import com.yzmm.clox.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RtosManager {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    val isActive get() = _active.value

    /** Which mode is currently running (only valid when isActive == true). */
    var currentMode: String = RtosPrefs.MODE_WATCHFACE_ONLY
        private set

    fun enter(ctx: Context) {
        currentMode = RtosPrefs.mode(ctx)
        RtosPrefs.get(ctx).edit().putBoolean(RtosPrefs.KEY_WAS_ACTIVE, true).apply()
        _active.value = true
        Toast.makeText(ctx, ctx.getString(R.string.toast_rtos_activated), Toast.LENGTH_SHORT).show()
    }

    fun exit(ctx: Context) {
        RtosPrefs.get(ctx).edit().putBoolean(RtosPrefs.KEY_WAS_ACTIVE, false).apply()
        _active.value = false
        Toast.makeText(ctx, ctx.getString(R.string.toast_rtos_deactivated), Toast.LENGTH_SHORT).show()
    }

    /** Restores active state after process death. Call from MainActivity after setup. */
    fun restoreIfNeeded(ctx: Context) {
        if (!isActive && RtosPrefs.get(ctx).getBoolean(RtosPrefs.KEY_WAS_ACTIVE, false)) {
            enter(ctx)
        }
    }

    /** Returns true if mode is MODE_FULL. */
    fun isFullMode() = isActive && currentMode == RtosPrefs.MODE_FULL

    /** Returns true if mode is MODE_WATCHFACE_ONLY. */
    fun isWatchfaceOnly() = isActive && currentMode == RtosPrefs.MODE_WATCHFACE_ONLY
}
