package com.ailife.clox.cocos

import android.app.Activity
import android.content.res.AssetManager
import android.view.SurfaceView
import android.view.SurfaceHolder

object CocosManager {

    private var surfaceView: SurfaceView? = null
    private var initialized = false

    fun init(activity: Activity): SurfaceView {
        val sv = SurfaceView(activity)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!initialized) {
                    nativeInit(
                        activity.assets,
                        holder.surfaceFrame.width(),
                        holder.surfaceFrame.height()
                    )
                    initialized = true
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        surfaceView = sv
        return sv
    }

    fun onResume() {
        if (initialized) nativeOnResume()
    }

    fun onPause() {
        if (initialized) nativeOnPause()
    }

    fun onDestroy() {
        if (initialized) {
            nativeOnDestroy()
            initialized = false
        }
        surfaceView = null
    }

    fun getSurfaceView(): SurfaceView? = surfaceView

    // JNI
    external fun nativeInit(assetManager: AssetManager, width: Int, height: Int)
    external fun nativeOnResume()
    external fun nativeOnPause()
    external fun nativeOnDestroy()

    init {
        System.loadLibrary("launcher_native")
    }
}
