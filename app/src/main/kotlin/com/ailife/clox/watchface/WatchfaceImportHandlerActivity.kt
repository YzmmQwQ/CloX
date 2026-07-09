package com.ailife.clox.watchface

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import kotlinx.coroutines.launch

/** Handles ACTION_VIEW for .watch files — copies to watchface dir and sets active. */
class WatchfaceImportHandlerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: uri.lastPathSegment ?: ""

        if (!fileName.endsWith(".watch", ignoreCase = true)) {
            Toast.makeText(this, R.string.wf_invalid_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val destDir = WatchfaceRepository.getWatchfaceDir(this@WatchfaceImportHandlerActivity)
            val result = WatchfaceRepository.importFromUri(this@WatchfaceImportHandlerActivity, uri, destDir)
            if (result != null) {
                WatchfacePrefs.setActivePath(this@WatchfaceImportHandlerActivity, result.absolutePath)
                Toast.makeText(this@WatchfaceImportHandlerActivity, R.string.wf_imported, Toast.LENGTH_SHORT).show()
                // Reset MainActivity to the bare watchface (collapsing any open overlay/app
                // list/context menu) before landing on the watch face picker, same as the
                // Settings > Watch Faces entry points do.
                startActivity(Intent(this@WatchfaceImportHandlerActivity,
                    com.ailife.clox.MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(com.ailife.clox.MainActivity.EXTRA_CLOSE_OVERLAYS, true)
                    .putExtra(com.ailife.clox.MainActivity.EXTRA_OPEN_WATCHFACE_SELECTOR, true))
            } else {
                Toast.makeText(this@WatchfaceImportHandlerActivity, R.string.wf_import_failed, Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
