package com.ailife.clox.anim

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.util.Log

/**
 * Captures the most-recent cached snapshot (last visible frame) of a task via the hidden
 * `IActivityTaskManager.getTaskSnapshot`. That call is gated by READ_FRAME_BUFFER
 * (signature|recents) — CloX obtains it only through its LSPosed hook ([com.ailife.clox.xposed.
 * PermGrantHook]); without LSPosed this throws SecurityException and returns null (caller falls
 * back to a non-snapshot close). The returned Bitmap wraps the snapshot's HardwareBuffer.
 */
object TaskSnapshotHelper {
    private const val TAG = "CloX/PathA"

    fun capture(taskId: Int): Bitmap? {
        if (taskId < 0) return null
        return runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null) ?: return null
            val snap = Class.forName("android.app.IActivityTaskManager")
                .getMethod("getTaskSnapshot", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(atm, taskId, /* isLowResolution = */ false)
            if (snap == null) { Log.w(TAG, "getTaskSnapshot: no cached snapshot for task $taskId"); return null }
            val buffer = snap.javaClass.getMethod("getHardwareBuffer").invoke(snap) as? HardwareBuffer
                ?: return null
            val cs = runCatching { snap.javaClass.getMethod("getColorSpace").invoke(snap) as? ColorSpace }.getOrNull()
            // wrapHardwareBuffer keeps a reference to the buffer, so don't close it here; the
            // immutable Bitmap owns it for the life of the morph.
            Bitmap.wrapHardwareBuffer(buffer, cs).also {
                Log.d(TAG, "snapshot task=$taskId -> ${it?.width}x${it?.height}")
            }
        }.onFailure { Log.e(TAG, "getTaskSnapshot($taskId) failed (LSPosed hook active?)", it) }.getOrNull()
    }
}
