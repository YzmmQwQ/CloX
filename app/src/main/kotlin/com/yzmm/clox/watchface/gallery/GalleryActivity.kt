package com.yzmm.clox.watchface.gallery

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yzmm.clox.R
import com.yzmm.clox.ui.theme.ScreenShapeConfig
import com.yzmm.clox.util.safeLaunch
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class GalleryActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenShapeConfig.wrap(newBase))
    }

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.gallery_title)

        recyclerView = findViewById(R.id.rvGallery)
        tvEmpty = findViewById(R.id.tvGalleryEmpty)
        fab = findViewById(R.id.fabImport)

        // Apply nav bar inset to FAB margin and RecyclerView bottom padding
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val lp = v.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            lp.bottomMargin = 16.dpToPx() + navBottom
            v.layoutParams = lp
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 88.dpToPx() + navBottom)
            insets
        }

        adapter = GalleryAdapter(lifecycleScope) { file, position ->
            confirmDelete(file, position)
        }
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        fab.setOnClickListener {
            if (PickVisualMedia.isPhotoPickerAvailable(this)) {
                pickViaPhotoPicker.safeLaunch(PickVisualMediaRequest(PickVisualMedia.ImageOnly), this)
            } else {
                pickViaContent.safeLaunch("image/*", this)
            }
        }

        loadGallery()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

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
