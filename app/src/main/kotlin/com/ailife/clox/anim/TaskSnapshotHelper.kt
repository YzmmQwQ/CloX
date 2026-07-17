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
    private const val MAX_TASKS = 20   // enough to cover the closing app; getTasks is z-ordered front-first
    private const val USER_CURRENT = -2   // android.os.UserHandle.USER_CURRENT

    /**
     * Resolve the id of the running/recent task fronted by [pkg], via the same privileged
     * `IActivityTaskManager` path as [capture] (getTasks is gated by the same signature|recents
     * perms, obtained through the LSPosed hook). Returns -1 if the package has no live task or the
     * call is unavailable.
     *
     * This is the fix for the snapshot close targeting the WRONG task: [RemoteAppAnimator.
     * lastOpenedTaskId] is only set when CloX itself opens the app (an app-list tap), so for any
     * other launch source (notification, recents, chained start) it is stale/-1 and captures a
     * previous app's snapshot. Resolving by the closing app's package fixes every launch source.
     */
    fun taskIdForPackage(pkg: String): Int {
        return runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null) ?: return -1
            val iface = Class.forName("android.app.IActivityTaskManager")
            // 1) Running tasks (front-first). Covers the just-backgrounded app in the common case.
            findIn("getTasks", invokeGetTasks(iface, atm), pkg)?.let { return it }
            // 2) Recent tasks. The running-task list can drop an app the instant it backgrounds (the
            //    home task becomes the only "running" one — the "1 tasks" we saw), but recents still
            //    holds the outgoing task AND its cached snapshot. This is the reliable source for the
            //    close morph regardless of how/when the app left the foreground.
            findIn("getRecentTasks", invokeGetRecentTasks(iface, atm), pkg)?.let { return it }
            -1
        }.onFailure { Log.w(TAG, "taskIdForPackage($pkg) failed (LSPosed hook active?)", it) }
            .getOrDefault(-1)
    }

    /** Search [tasks] for the first task fronted by [pkg]; dumps the whole list for diagnosis. */
    private fun findIn(src: String, tasks: List<*>?, pkg: String): Int? {
        if (tasks == null) { Log.d(TAG, "$src -> null (unavailable)"); return null }
        var hit = -1
        val dump = tasks.mapNotNull { ti ->
            ti ?: return@mapNotNull null
            val id = taskIdOf(ti); val pkgs = packagesOf(ti)
            if (hit < 0 && id >= 0 && pkg in pkgs) hit = id
            "#$id${pkgs}"
        }.joinToString(" ")
        Log.d(TAG, "$src(${tasks.size}) [$dump] -> ${if (hit >= 0) "MATCH $pkg=$hit" else "no $pkg"}")
        return if (hit >= 0) hit else null
    }

    /** Call whichever getTasks(...) overload this platform exposes (signatures vary by API). */
    private fun invokeGetTasks(iface: Class<*>, atm: Any): List<*>? {
        // API 34+: getTasks(maxNum, filterOnlyVisibleRecents, keepIntentExtra, displayId)
        runCatching {
            val m = iface.getMethod("getTasks", Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            return m.invoke(atm, MAX_TASKS, false, false, -1 /* INVALID_DISPLAY = all displays */) as? List<*>
        }
        // API 31-33: getTasks(maxNum, filterOnlyVisibleRecents, keepIntentExtra)
        runCatching {
            val m = iface.getMethod("getTasks", Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            return m.invoke(atm, MAX_TASKS, false, false) as? List<*>
        }
        // Older: getTasks(maxNum)
        runCatching {
            val m = iface.getMethod("getTasks", Int::class.javaPrimitiveType)
            return m.invoke(atm, MAX_TASKS) as? List<*>
        }
        return null
    }

    /**
     * getRecentTasks(maxNum, flags, userId) -> ParceledListSlice<RecentTaskInfo>. flags=0 (no
     * excluded/unavailable extras); userId=USER_CURRENT (-2), resolved by the framework under the
     * system identity the hook grants. Unwraps the ParceledListSlice via getList().
     */
    private fun invokeGetRecentTasks(iface: Class<*>, atm: Any): List<*>? {
        runCatching {
            val m = iface.getMethod("getRecentTasks", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val slice = m.invoke(atm, MAX_TASKS, 0, USER_CURRENT) ?: return null
            return when (slice) {
                is List<*> -> slice
                else -> slice.javaClass.getMethod("getList").invoke(slice) as? List<*>
            }
        }
        return null
    }

    /** taskId of a RunningTaskInfo/TaskInfo (public field since API 29; `id` on older). */
    private fun taskIdOf(ti: Any): Int = runCatching {
        runCatching { ti.javaClass.getField("taskId").getInt(ti) }
            .getOrElse { ti.javaClass.getField("id").getInt(ti) }
    }.getOrDefault(-1)

    /** Every package this task is fronted by, across the @hide ComponentName/Intent fields. */
    private fun packagesOf(ti: Any): Set<String> {
        val out = HashSet<String>()
        for (field in arrayOf("topActivity", "baseActivity", "realActivity", "origActivity")) {
            runCatching {
                (ti.javaClass.getField(field).get(ti) as? android.content.ComponentName)?.packageName
            }.getOrNull()?.let { out += it }
        }
        runCatching {
            (ti.javaClass.getField("baseIntent").get(ti) as? android.content.Intent)?.component?.packageName
        }.getOrNull()?.let { out += it }
        return out
    }

    fun capture(taskId: Int): Bitmap? {
        if (taskId < 0) return null
        return runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null) ?: return null
            val iface = Class.forName("android.app.IActivityTaskManager")
            // Prefer the CACHED snapshot (cheap read). But WindowManager doesn't store it until the
            // app→home transition finishes (~200ms after Home), and during that gap home shows through
            // and then our snapshot pops in fullscreen — the close "flicker". So when the cache is empty
            // we FORCE a capture with takeTaskSnapshot (same READ_FRAME_BUFFER path, hooked), which grabs
            // the outgoing window's pixels immediately instead of waiting for WM to cache them.
            var snap = iface.getMethod("getTaskSnapshot", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(atm, taskId, /* isLowResolution = */ false)
            if (snap == null) {
                snap = takeTaskSnapshot(iface, atm, taskId)
                if (snap == null) { Log.w(TAG, "getTaskSnapshot: no cached snapshot for task $taskId (force failed)"); return null }
                Log.d(TAG, "snapshot task=$taskId via takeTaskSnapshot (forced)")
            }
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

    /** Force WM to capture a fresh snapshot NOW (bypasses the cache-store latency). Tries both known
     *  overloads; returns the TaskSnapshot object or null. */
    private fun takeTaskSnapshot(iface: Class<*>, atm: Any, taskId: Int): Any? {
        // API 33+: takeTaskSnapshot(int taskId, boolean updateCache)
        runCatching {
            val m = iface.getMethod("takeTaskSnapshot", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            return m.invoke(atm, taskId, true)
        }
        // Older: takeTaskSnapshot(int taskId)
        runCatching {
            val m = iface.getMethod("takeTaskSnapshot", Int::class.javaPrimitiveType)
            return m.invoke(atm, taskId)
        }
        return null
    }
}
