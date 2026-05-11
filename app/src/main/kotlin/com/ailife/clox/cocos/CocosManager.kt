package com.ailife.clox.cocos

import android.app.Activity
import android.content.res.AssetManager
import android.view.SurfaceView

object CocosManager {

    private var surfaceView: SurfaceView? = null
    private var initialized = false

    fun init(activity: Activity): SurfaceView {
        // Use Axmol's AxmolGLSurfaceView when the library is on the classpath.
        // Falls back to plain SurfaceView (black) when Axmol submodule is absent.
        val sv: SurfaceView = try {
            val glClass = Class.forName("dev.axmol.lib.AxmolGLSurfaceView")
            val glView = glClass.getConstructor(android.content.Context::class.java)
                .newInstance(activity) as SurfaceView
            // AxmolGLSurfaceView.setEGLConfigChooser(r, g, b, a, depth, stencil)
            glClass.getMethod("setEGLConfigChooser",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(glView, false, 5, 6, 5, 0, 16, 8)
            initialized = true
            glView
        } catch (e: ClassNotFoundException) {
            // Axmol not on classpath — JNI stub path
            android.util.Log.w("CocosManager",
                "AxmolGLSurfaceView not found — Axmol submodule missing. Screen will be black.")
            val plain = SurfaceView(activity)
            plain.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    if (!initialized) {
                        nativeInit(activity.assets,
                            holder.surfaceFrame.width(), holder.surfaceFrame.height())
                        initialized = true
                    }
                }
                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, h2: Int) {}
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
            })
            plain
        }
        surfaceView = sv
        return sv
    }

    fun onResume() { if (initialized) nativeOnResume() }
    fun onPause()  { if (initialized) nativeOnPause()  }
    fun onDestroy() {
        if (initialized) { nativeOnDestroy(); initialized = false }
        surfaceView = null
    }

    external fun nativeInit(assetManager: AssetManager, width: Int, height: Int)
    external fun nativeOnResume()
    external fun nativeOnPause()
    external fun nativeOnDestroy()

    init {
        try {
            System.loadLibrary("clox")
        } catch (e: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("launcher_native")
            } catch (e2: UnsatisfiedLinkError) {
                android.util.Log.e("CocosManager", "No native library found")
            }
        }
    }
}
