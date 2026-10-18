package com.yzmm.clox.graphics

import android.os.Build

/**
 * Workarounds for Android 8.x (API < 28) Skia/HWUI thread-safety bugs that crash
 * CloX natively when bitmap decode / vector rasterization runs on the shared
 * coroutine pool (threads named "DefaultDispatch"). Everything here is a NO-OP on
 * API >= 28 — Pie rewrote the HWUI/Skia rendering pipeline and is thread-safe — so
 * newer devices keep their existing parallel behavior untouched.
 *
 * Crash 1 (SIGABRT, SkColorSpaceXformer dtor): concurrent color-managed
 *   BitmapFactory decodes of profiled JPEGs race in 8.1's Skia. Fix: serialize the
 *   real decode so no two run at once — see [serializedDecode].
 *
 * Crash 2 (SIGSEGV, VectorDrawable::Tree::updateBitmapCache ->
 *   SkPixelRef::callGenIDChangeListeners): drawing a VectorDrawable off the main
 *   thread mutates a cached bitmap whose SkPixelRef gen-id listener list is shared,
 *   via ConstantState, with the on-screen instance. Fix: rasterize a mutate()d,
 *   non-shared copy — see IconResolver.renderToBitmap.
 */
object GraphicsCompat {

    /** True on devices whose Skia/HWUI predates the API 28 thread-safe rewrite. */
    val isLegacySkia = Build.VERSION.SDK_INT < Build.VERSION_CODES.P

    // App-wide monitor: on legacy devices only one native color-managed decode may
    // run at a time, so an SkColorSpaceXformer is fully constructed AND destructed
    // before the next decode begins (removes the cross-thread dtor race).
    private val decodeLock = Any()

    /**
     * Run [block] (a BitmapFactory real-decode) under the global decode lock on
     * API < 28; run it unguarded (no overhead, full parallelism) on API >= 28.
     */
    fun <T> serializedDecode(block: () -> T): T =
        if (isLegacySkia) synchronized(decodeLock) { block() } else block()
}
