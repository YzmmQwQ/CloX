package com.ailife.clox.cocos

import android.app.Activity
import android.util.Log
import android.view.SurfaceView
import dev.axmol.lib.AxmolEngine
import dev.axmol.lib.AxmolGLSurfaceView
import dev.axmol.lib.AxmolRenderer

object CocosManager {
    private const val TAG = "CocosManager"
    private var glView: AxmolGLSurfaceView? = null

    fun init(activity: Activity): SurfaceView {
        AxmolEngine.init(activity)
        val view = AxmolGLSurfaceView(activity)
        view.setEGLConfigChooser(5, 6, 5, 0, 16, 8)
        view.setRenderer(AxmolRenderer())
        glView = view
        Log.i(TAG, "AxmolGLSurfaceView initialized")
        return view
    }

    fun getGlView(): SurfaceView? = glView

    fun onResume()  { glView?.onResume() }
    fun onPause()   { glView?.onPause() }
    fun onDestroy() { glView = null }

    @JvmStatic external fun nativeSetBlurRadius(radius: Float)

    init { System.loadLibrary("clox") }
}
