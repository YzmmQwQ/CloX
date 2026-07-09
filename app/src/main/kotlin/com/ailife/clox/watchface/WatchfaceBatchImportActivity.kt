package com.ailife.clox.watchface

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.ailife.clox.debug.WatchfaceBackupManager
import com.ailife.clox.watchface.selector.WatchfaceSelectorActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchfaceBatchImportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WatchfaceBatchImport"
    }

    private val REQ_FOLDER = 1001
    private val REQ_FILES = 1002

    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnPickFolder: MaterialButton
    private lateinit var btnPickFiles: MaterialButton
    private lateinit var btnPickZip: MaterialButton
    private lateinit var btnBackupAll: MaterialButton

    private val pickZipLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importZip(uri)
    }
    private val backupAllLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backupAll(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wf_batch_import)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.wf_import)

        // fitsSystemWindows="true" on AppBarLayout handles status bar inset automatically

        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        btnPickFolder = findViewById(R.id.btnPickFolder)
        btnPickFiles = findViewById(R.id.btnPickFiles)
        btnPickZip = findViewById(R.id.btnPickZip)
        btnBackupAll = findViewById(R.id.btnBackupAll)

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

        btnPickZip.setOnClickListener {
            pickZipLauncher.launch(arrayOf("application/zip", "*/*"))
        }

        btnBackupAll.setOnClickListener {
            backupAllLauncher.launch("clox_watchfaces_backup.zip")
        }
    }

    private fun importZip(uri: Uri) {
        setBusy(true, R.string.wf_import_progress)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { WatchfaceBackupManager.importZip(this@WatchfaceBatchImportActivity, uri) }
            Log.i(TAG, "importZip from $uri: count=$count")
            if (count > 0) onImportDone(count) else {
                setBusy(false)
                val msg = getString(R.string.wf_import_zip_failed)
                tvStatus.text = msg
                Toast.makeText(this@WatchfaceBatchImportActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun backupAll(uri: Uri) {
        setBusy(true, R.string.wf_backup_progress)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { WatchfaceBackupManager.exportAll(this@WatchfaceBatchImportActivity, uri) }
            Log.i(TAG, "backupAll to $uri: count=$count")
            setBusy(false)
            val msg = if (count >= 0) getString(R.string.wf_backup_done, count) else getString(R.string.wf_backup_failed)
            tvStatus.text = msg
            Toast.makeText(this@WatchfaceBatchImportActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun setBusy(busy: Boolean, statusRes: Int? = null) {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        btnPickFolder.isEnabled = !busy
        btnPickFiles.isEnabled = !busy
        btnPickZip.isEnabled = !busy
        btnBackupAll.isEnabled = !busy
        if (statusRes != null) tvStatus.setText(statusRes)
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
        setBusy(importing, if (importing) R.string.wf_import_progress else null)
    }

    private fun onImportDone(count: Int) {
        setBusy(false)
        tvStatus.text = getString(R.string.wf_import_done, count)
        tvStatus.postDelayed({
            startActivity(Intent(this, WatchfaceSelectorActivity::class.java))
            finish()
        }, 1200)
    }
}
