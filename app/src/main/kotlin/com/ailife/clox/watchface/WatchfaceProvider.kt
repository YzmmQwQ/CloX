package com.ailife.clox.watchface

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * ContentProvider exposing the watchface directory ([WatchfaceRepository.getWatchfaceDir]) to
 * other apps for read/write. Files stay on the app-private external path so the Lua renderer keeps
 * reading them by absolute path — third parties go through content:// URIs and CloX does the file
 * I/O on their behalf.
 *
 * Authority: ${applicationId}.watchfaces   e.g. content://com.ailife.clox.watchfaces/
 * Access is gated by com.ailife.clox.permission.ACCESS_WATCHFACES (declared in the manifest,
 * protectionLevel=normal → auto-granted to any app that requests it).
 *
 * Setting the ACTIVE watchface is intentionally NOT here — that already has its own API
 * (ChangeFaceReceiver, com.android.watchengine.changeface). This provider is directory files only.
 *
 * URI shapes:
 *   content://<auth>/                      → the whole directory (query lists it)
 *   content://<auth>/<name>.watch          → a top-level face
 *   content://<auth>/<folder>/<name>.watch → a face inside a folder
 *
 * Every path is resolved against the canonical watchface dir and rejected if it escapes it, so the
 * provider can only ever touch the watchface directory — nothing else in app storage.
 */
class WatchfaceProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** Canonical watchface root, or null if external storage is unavailable. */
    private fun root(): File? =
        context?.let { runCatching { WatchfaceRepository.getWatchfaceDir(it).canonicalFile }.getOrNull() }

    /**
     * Map a URI to a real file inside the watchface dir. Returns null if it resolves outside the
     * root (path-traversal guard) or storage is unavailable.
     */
    private fun resolve(uri: Uri): File? {
        val root = root() ?: return null
        val rel = uri.pathSegments.joinToString(File.separator)
        val target = runCatching { File(root, rel).canonicalFile }.getOrNull() ?: return null
        val ok = target == root || target.path.startsWith(root.path + File.separator)
        return if (ok) target else null
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val root = root() ?: return null
        val cols = projection ?: DEFAULT_COLUMNS
        val cursor = MatrixCursor(cols)
        val target = resolve(uri) ?: return cursor

        when {
            // Root (or a folder): list its .watch files, its sub-folders, and one level of
            // files inside those sub-folders — everything the selector shows.
            target.isDirectory -> {
                target.listFiles { f -> f.isFile && f.name.endsWith(WATCH_EXT) }
                    ?.sortedBy { it.name }?.forEach { addRow(cursor, cols, root, it) }
                target.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
                    addRow(cursor, cols, root, dir)
                    dir.listFiles { f -> f.isFile && f.name.endsWith(WATCH_EXT) }
                        ?.sortedBy { it.name }?.forEach { addRow(cursor, cols, root, it) }
                }
            }
            // A single existing file/folder: one metadata row.
            target.exists() -> addRow(cursor, cols, root, target)
        }
        return cursor
    }

    private fun addRow(cursor: MatrixCursor, cols: Array<out String>, root: File, file: File) {
        val row = cursor.newRow()
        val rel = file.absolutePath.removePrefix(root.path).trimStart(File.separatorChar)
        for (col in cols) {
            row.add(
                when (col) {
                    COL_NAME -> file.name
                    COL_REL_PATH -> rel
                    COL_SIZE -> if (file.isFile) file.length() else 0L
                    COL_MODIFIED -> file.lastModified()
                    COL_IS_DIR -> if (file.isDirectory) 1 else 0
                    COL_MIME -> if (file.isDirectory) MIME_DIR else MIME_FILE
                    else -> null
                }
            )
        }
    }

    override fun getType(uri: Uri): String {
        val f = resolve(uri)
        return if (f != null && f.isDirectory) MIME_DIR else MIME_FILE
    }

    /**
     * Open a face for read or write. Write modes create the file (and any parent folder) so callers
     * can push new faces with `openOutputStream(uri, "w")`. The bytes land straight in the
     * Lua-readable directory — no copy.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolve(uri) ?: throw SecurityException("Path outside watchface directory: $uri")
        val bits = ParcelFileDescriptor.parseMode(mode)
        val writing = bits and ParcelFileDescriptor.MODE_READ_ONLY == 0
        if (writing) {
            file.parentFile?.mkdirs()
            // A fresh write invalidates any cached preview for this path.
            WatchfaceRepository.invalidateCache(file.absolutePath)
            notifyChange(uri)
        }
        return ParcelFileDescriptor.open(file, bits)
    }

    /** Delete a face (or a folder and its contents). */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val file = resolve(uri) ?: return 0
        if (!file.exists()) return 0
        val ok = if (file.isDirectory) WatchfaceRepository.deleteFolder(file)
                 else WatchfaceRepository.delete(file)
        if (ok) {
            WatchfaceRepository.invalidateCache(file.absolutePath)
            notifyChange(uri)
            Log.i(TAG, "Deleted via provider: ${file.name}")
        }
        return if (ok) 1 else 0
    }

    /**
     * Create a sub-folder. Callers pass the folder name as the last URI segment, e.g.
     * `insert(content://<auth>/MyFolder, null)`. Returns the folder URI, or null on failure.
     * (New FILES are created through openFile write mode, not insert.)
     */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val dir = resolve(uri) ?: return null
        return if (dir.mkdirs() || dir.isDirectory) { notifyChange(uri); uri } else null
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    private fun notifyChange(uri: Uri) {
        runCatching { context?.contentResolver?.notifyChange(uri, null) }
    }

    companion object {
        private const val TAG = "WatchfaceProvider"
        private const val WATCH_EXT = ".watch"

        const val MIME_FILE = "application/octet-stream"
        const val MIME_DIR = "vnd.android.document/directory"

        const val COL_NAME = "_display_name"
        const val COL_SIZE = "_size"
        const val COL_MODIFIED = "last_modified"
        const val COL_REL_PATH = "relative_path"
        const val COL_IS_DIR = "is_dir"
        const val COL_MIME = "mime_type"

        private val DEFAULT_COLUMNS =
            arrayOf(COL_NAME, COL_REL_PATH, COL_SIZE, COL_MODIFIED, COL_IS_DIR, COL_MIME)
    }
}
