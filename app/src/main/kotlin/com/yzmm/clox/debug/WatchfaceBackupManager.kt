package com.yzmm.clox.debug

import android.content.Context
import android.net.Uri
import android.util.Log
import com.yzmm.clox.watchface.WatchfaceRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Zips/unzips the installed .watch files under [WatchfaceRepository.getWatchfaceDir], preserving subfolder structure. */
object WatchfaceBackupManager {

    private const val TAG = "WatchfaceBackupManager"

    /** Returns the number of watchfaces written, or -1 on failure. */
    fun exportAll(ctx: Context, uri: Uri): Int {
        val root = WatchfaceRepository.getWatchfaceDir(ctx)
        var count = 0
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    root.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".watch") }
                        .forEach { file ->
                            val relPath = file.relativeTo(root).path.replace(File.separatorChar, '/')
                            zip.putNextEntry(ZipEntry(relPath))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            count++
                        }
                }
            } ?: run {
                Log.e(TAG, "exportAll: openOutputStream returned null for $uri")
                return -1
            }
            Log.i(TAG, "exportAll: wrote $count watchface(s) to $uri")
            count
        } catch (e: Exception) {
            Log.e(TAG, "exportAll failed for $uri (after $count written)", e)
            -1
        }
    }

    /**
     * Extracts every .watch entry from the zip into the watchfaces dir, recreating
     * the entry's folder path. Zip entries are resolved against the watchfaces dir
     * and rejected if they'd resolve outside it (zip-slip), since the archive is
     * user-supplied. Returns the number of watchfaces imported.
     */
    fun importZip(ctx: Context, uri: Uri): Int {
        val root = WatchfaceRepository.getWatchfaceDir(ctx)
        var count = 0
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".watch")) {
                            val dest = safeDestFile(root, entry.name)
                            if (dest == null) {
                                Log.w(TAG, "importZip: rejected entry outside watchfaces dir: ${entry.name}")
                            } else {
                                dest.parentFile?.mkdirs()
                                dest.outputStream().use { out -> zip.copyTo(out) }
                                count++
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: Log.e(TAG, "importZip: openInputStream returned null for $uri")
        } catch (e: Exception) {
            Log.e(TAG, "importZip failed for $uri (after $count imported)", e)
        }
        Log.i(TAG, "importZip: imported $count watchface(s) from $uri")
        return count
    }

    private fun safeDestFile(root: File, entryName: String): File? {
        val canonicalRoot = root.canonicalFile
        val dest = File(root, entryName).canonicalFile
        return if (dest.path == canonicalRoot.path || dest.path.startsWith(canonicalRoot.path + File.separator)) dest else null
    }
}
