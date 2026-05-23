package com.ailife.clox.watchface.gallery

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var adapter: GalleryAdapter

    private val galleryDir: File by lazy {
        File(getExternalFilesDir(null), "gallery").also { it.mkdirs() }
    }

    private val pickViaPhotoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) importImages(uris) }

    private val pickViaContent = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) importImages(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.gallery_title)

        recyclerView = findViewById(R.id.rvGallery)
        tvEmpty = findViewById(R.id.tvGalleryEmpty)
        fab = findViewById(R.id.fabImport)

        adapter = GalleryAdapter(lifecycleScope) { file, position ->
            confirmDelete(file, position)
        }
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        fab.setOnClickListener {
            if (PickVisualMedia.isPhotoPickerAvailable(this)) {
                pickViaPhotoPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            } else {
                pickViaContent.launch("image/*")
            }
        }

        loadGallery()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun loadGallery() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                galleryDir.listFiles { f -> f.isFile && isImageFile(f.name) }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            }
            adapter.setItems(files)
            tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun importImages(uris: List<Uri>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri -> copyToGallery(uri) }
            }
            loadGallery()
        }
    }

    private fun copyToGallery(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
        val ext = when {
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }
        val displayName = queryDisplayName(uri)?.substringBeforeLast('.')
            ?: System.currentTimeMillis().toString()
        val destFile = uniqueFile(galleryDir, displayName, ext)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
            destFile.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }

    private fun uniqueFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$counter.$ext")
            counter++
        }
        return candidate
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".gif") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    private fun confirmDelete(file: File, position: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gallery_delete_title)
            .setMessage(getString(R.string.gallery_delete_message, file.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.gallery_delete_confirm) { _, _ ->
                file.delete()
                adapter.removeAt(position)
                if (adapter.itemCount == 0) tvEmpty.visibility = View.VISIBLE
            }
            .show()
    }
}
