package com.yzmm.clox.watchface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles com.android.watchengine.changeface broadcasts sent by third-party
 * watch companion apps (e.g. Universal Launcher transfer app).
 *
 * Extra "faceName" is a relative path from the sender's skin directory; only
 * the last path segment (name without extension) is used for matching so that
 * different absolute base dirs don't matter.
 */
class ChangeFaceReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val faceName = intent.getStringExtra(EXTRA_FACE_NAME)?.trim()
        if (faceName.isNullOrEmpty()) {
            Log.w(TAG, "Ignored: missing faceName extra")
            return
        }

        val baseName = faceName
            .replace('\\', '/')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .lowercase()

        if (baseName.isEmpty()) {
            Log.w(TAG, "Ignored: could not derive base name from faceName=$faceName")
            return
        }

        val match = WatchfaceRepository.scan(ctx)
            .firstOrNull { it.name.lowercase() == baseName }

        if (match == null) {
            Log.w(TAG, "No watchface found matching '$baseName'")
            return
        }

        WatchfacePrefs.setActivePath(ctx, match.absolutePath)
        Log.i(TAG, "Watchface changed to ${match.absolutePath} (matched '$baseName')")
    }

    companion object {
        const val ACTION = "com.android.watchengine.changeface"
        const val EXTRA_FACE_NAME = "faceName"
        private const val TAG = "ChangeFaceReceiver"
    }
}
