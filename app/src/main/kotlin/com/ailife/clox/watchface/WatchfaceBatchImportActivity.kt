package com.ailife.clox.watchface

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.ailife.clox.watchface.selector.WatchfaceSelectorActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchfaceBatchImportActivity : AppCompatActivity() {

    private val REQ_FOLDER = 1001
    private val REQ_FILES = 1002

    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnPickFolder: MaterialButton
    private lateinit var btnPickFiles: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wf_batch_import)

        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        btnPickFolder = findViewById(R.id.btnPickFolder)
        btnPickFiles = findViewById(R.id.btnPickFiles)

        btnPickFolder.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER
            )
        }

        btnPickFiles.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, REQ_FILES
            )
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.wf_import)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        when (requestCode) {
            REQ_FOLDER -> {
                val treeUri = data.data ?: return
                importFromTree(treeUri)
            }
            REQ_FILES -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
                } ?: data.data?.let { uris += it }
                importFiles(uris)
            }
        }
    }

    private fun importFromTree(treeUri: Uri) {
        setImporting(true)
        val destDir = WatchfaceRepository.getWatchfaceDir(this)
        lifecycleScope.launch {
            val count = WatchfaceRepository.importFromTree(this@WatchfaceBatchImportActivity, treeUri, destDir)
            onImportDone(count)
        }
    }

    private fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        setImporting(true)
        val destDir = WatchfaceRepository.getWatchfaceDir(this)
        lifecycleScope.launch {
            var count = 0
            uris.forEach { uri ->
                if (uri.toString().substringAfterLast('.').equals("watch", ignoreCase = true) ||
                    getFileNameFromUri(uri)?.endsWith(".watch") == true) {
                    if (WatchfaceRepository.importFromUri(this@WatchfaceBatchImportActivity, uri, destDir) != null)
                        count++
                }
            }
            onImportDone(count)
        }
    }

    private suspend fun getFileNameFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
    }

    private fun setImporting(importing: Boolean) {
        progressBar.visibility = if (importing) View.VISIBLE else View.GONE
        btnPickFolder.isEnabled = !importing
        btnPickFiles.isEnabled = !importing
        if (importing) tvStatus.setText(R.string.wf_import_progress)
    }

    private fun onImportDone(count: Int) {
        setImporting(false)
        tvStatus.text = getString(R.string.wf_import_done, count)
        // Navigate to selector after brief delay
        tvStatus.postDelayed({
            startActivity(Intent(this, WatchfaceSelectorActivity::class.java))
            finish()
        }, 1200)
    }
}
