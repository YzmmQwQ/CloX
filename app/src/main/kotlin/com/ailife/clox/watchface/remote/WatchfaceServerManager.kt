package com.ailife.clox.watchface.remote

import android.content.Context
import android.util.Log

object WatchfaceServerManager {

    private const val TAG = "WatchfaceServerMgr"
    private var server: WatchfaceWebServer? = null

    val isRunning: Boolean get() = server?.isAlive == true
    val port: Int get() = WatchfaceWebServer.DEFAULT_PORT

    fun start(ctx: Context): Boolean {
        if (isRunning) return true
        return try {
            val s = WatchfaceWebServer(ctx.applicationContext, WatchfaceWebServer.DEFAULT_PORT)
            s.start()
            server = s
            Log.i(TAG, "Server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            false
        }
    }

    fun stop() {
        server?.stop()
        server = null
        Log.i(TAG, "Server stopped")
    }
}
