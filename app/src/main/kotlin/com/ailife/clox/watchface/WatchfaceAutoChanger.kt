package com.ailife.clox.watchface

import android.content.Context
import android.widget.Toast
import com.ailife.clox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WatchfaceAutoChanger {

    fun pickRandom(ctx: Context, scope: CoroutineScope) {
        scope.launch {
            val items = withContext(Dispatchers.IO) { WatchfaceRepository.scan(ctx) }
            if (items.isEmpty()) return@launch
            val pick = items.random()
            WatchfacePrefs.setActivePath(ctx, pick.absolutePath)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, ctx.getString(R.string.toast_wf_selected, pick.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun checkOnResume(ctx: Context, scope: CoroutineScope) {
        val onScreenOn = WatchfacePrefs.getAutoChangeOnScreenOn(ctx)
        val interval   = WatchfacePrefs.getAutoChangeInterval(ctx)

        val shouldChange = onScreenOn || run {
            if (interval == WatchfacePrefs.AutoChangeInterval.NONE) return@run false
                val last = WatchfacePrefs.getAutoChangeLastTime(ctx)
            val elapsed = System.currentTimeMillis() - last
            elapsed >= interval.millis
        }

        if (shouldChange) {
            WatchfacePrefs.setAutoChangeLastTime(ctx)
            pickRandom(ctx, scope)
        }
    }
}
