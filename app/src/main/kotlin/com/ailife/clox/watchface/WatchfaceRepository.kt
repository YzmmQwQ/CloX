package com.ailife.clox.watchface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object WatchfaceRepository {

    private val previewCache = object : LruCache<String, Bitmap>(32) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    // Cap concurrent preview decodes: a fling through the selector binds many
    // cells at once, and unbounded Dispatchers.IO would run every JPEG decode
    // in parallel — saturating all cores of a slow SoC and starving the
    // UI/render thread even though nothing runs on main.
    private val previewDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

    // Previews never need to exceed the watchface render square (512 canvas);
    // oversized preview.jpg decodes downsampled to save CPU and cache memory.
    private const val MAX_PREVIEW_DIM = 512

    fun getWatchfaceDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "watchfaces").also { it.mkdirs() }

    fun scan(ctx: Context): List<WatchfaceItem> {
        val dir = getWatchfaceDir(ctx)
        val activePath = WatchfacePrefs.getActivePath(ctx)
        val pinned = WatchfacePrefs.getPinned(ctx)
        val sortOrder = WatchfacePrefs.getSortOrder(ctx)
        val items = mutableListOf<WatchfaceItem>()

        dir.listFiles { f -> f.isFile && f.name.endsWith(".watch") && f.nameWithoutExtension.isNotBlank() }
            ?.forEach { file ->
                items += WatchfaceItem(
                    file = file, folder = null,
                    isPinned = file.absolutePath in pinned,
                    isActive = file.absolutePath == activePath,
                    lastModified = file.lastModified()
                )
            }

        dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }
            ?.forEach { subDir ->
                subDir.listFiles { f -> f.isFile && f.name.endsWith(".watch") }
                    ?.forEach { file ->
                        items += WatchfaceItem(
                            file = file, folder = subDir.name,
                            isPinned = file.absolutePath in pinned,
                            isActive = file.absolutePath == activePath,
                            lastModified = file.lastModified()
                        )
                    }
            }

        return when (sortOrder) {
            WatchfacePrefs.SortOrder.NAME -> items.sortedBy { it.name.lowercase() }
            WatchfacePrefs.SortOrder.MODIFIED -> items.sortedByDescending { it.lastModified }
        }
    }

    fun getFolders(ctx: Context): List<File> =
        getWatchfaceDir(ctx).listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    suspend fun loadPreview(file: File): Bitmap? {
        previewCache.get(file.absolutePath)?.let { return it }
        return withContext(previewDecodeDispatcher) {
            try {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry("preview.jpg") ?: return@withContext null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                    }
                    zip.getInputStream(entry).use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)?.also {
                            previewCache.put(file.absolutePath, it)
                        }
                    }
                }
            } catch (_: Exception) { null }
        }
    }

    /** Largest power-of-two downsample that keeps both dimensions >= MAX_PREVIEW_DIM. */
    private fun sampleSizeFor(w: Int, h: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= MAX_PREVIEW_DIM && h / (sample * 2) >= MAX_PREVIEW_DIM) {
            sample *= 2
        }
        return sample
    }

    fun getPreviewCached(path: String): Bitmap? = previewCache.get(path)

    fun invalidateCache(path: String) { previewCache.remove(path) }

    fun rename(file: File, newName: String): File? {
        val dest = File(file.parent, "$newName.watch")
        if (dest.exists()) return null
        return if (file.renameTo(dest)) dest else null
    }

    fun delete(file: File): Boolean = file.delete()

    fun moveToFolder(file: File, targetDir: File?): File? {
        val parent = file.parentFile ?: return null
        val dest = if (targetDir == null) File(parent.parentFile ?: parent, file.name)
                   else File(targetDir.also { it.mkdirs() }, file.name)
        if (dest.exists()) return null
        return if (file.renameTo(dest)) dest else null
    }

    fun createFolder(ctx: Context, name: String): File? {
        val dir = File(getWatchfaceDir(ctx), name)
        return if (dir.mkdirs() || dir.exists()) dir else null
    }

    fun deleteFolder(folder: File): Boolean {
        folder.listFiles()?.forEach { it.delete() }
        return folder.delete()
    }

    fun renameFolder(folder: File, newName: String): File? {
        val dest = File(folder.parent, newName)
        if (dest.exists()) return null
        return if (folder.renameTo(dest)) dest else null
    }

    suspend fun importFromUri(ctx: Context, uri: Uri, destDir: File): File? =
        withContext(Dispatchers.IO) {
            try {
                val name = getFileNameFromUri(ctx, uri) ?: "watchface_${System.currentTimeMillis()}.watch"
                val safeName = if (name.endsWith(".watch")) name else "$name.watch"
                val dest = File(destDir, safeName)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                dest
            } catch (_: Exception) { null }
        }

    suspend fun importFromTree(ctx: Context, treeUri: Uri, destDir: File): Int =
        withContext(Dispatchers.IO) {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            importFromDocId(ctx, treeUri, rootDocId, destDir)
        }

    private suspend fun importFromDocId(
        ctx: Context, treeUri: Uri, docId: String, destDir: File
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            ctx.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val childName = cursor.getString(1) ?: ""
                    val mime = cursor.getString(2) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                            // Recurse: keep treeUri, just pass the subdirectory's docId
                            count += importFromDocId(ctx, treeUri, childId, destDir)
                        childName.endsWith(".watch") -> {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            if (importFromUri(ctx, fileUri, destDir) != null) count++
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        count
    }

    private fun getFileNameFromUri(ctx: Context, uri: Uri): String? {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }
}
