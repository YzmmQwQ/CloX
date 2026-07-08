package com.ailife.clox.watchface

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Content-based identity for a .watch package, used to namespace per-face
 * config ([WatchfaceConfigPrefs]) so settings survive the file being MOVED or
 * RENAMED (the old identity — a hash of the absolute path — did not).
 *
 * The identity is built from CRC32s the zip format already stores in its
 * central directory (one per entry, precomputed at zip time): the face
 * definition (watch.pxml, falling back to watch.xml) combined with the script
 * (scripts/script.txt). Reading them costs a central-directory scan — no
 * decompression, no content hashing — and even that is skipped on the hot
 * path (selector scale animation reads canvasRect per frame) by a two-level
 * (size, mtime)-validated cache: an in-memory map, then a persisted
 * `wf_idc_<pathHash>` pref entry that survives process restarts.
 *
 * Semantics: moving/renaming a face keeps its config; editing the face's
 * definition or script resets it (same rule as the Lua-side wf_vars store);
 * a byte-identical copy shares config with the original.
 *
 * The first time a face's content identity is computed, any config stored
 * under its legacy path-hash namespace is copied over (one-time migration;
 * the legacy keys are left in place, they are simply never read again).
 */
object WatchfaceIdentity {

    private const val TAG = "WatchfaceIdentity"
    private const val CACHE_PREFIX = "wf_idc_"   // wf_idc_<pathHash> = "<size>:<mtime>:<identity>"

    /** path → (size, mtime, identity) */
    private val mem = ConcurrentHashMap<String, Triple<Long, Long, String>>()

    fun legacyHash(path: String): String = Integer.toHexString(path.hashCode())

    /**
     * The config-namespace identity for a face path. The built-in default face
     * ([WatchfaceConfigPrefs.DEFAULT_PATH]) keeps its literal key; a missing/
     * unreadable file falls back to the legacy path hash (degrades to the old
     * behaviour instead of failing).
     */
    fun idFor(ctx: Context, path: String): String {
        if (path == WatchfaceConfigPrefs.DEFAULT_PATH) return path
        val f = File(path)
        if (!f.isFile) return legacyHash(path)
        val size = f.length()
        val mtime = f.lastModified()
        mem[path]?.let { (s, m, id) -> if (s == size && m == mtime) return id }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val cacheKey = CACHE_PREFIX + legacyHash(path)
        prefs.getString(cacheKey, null)?.split(':', limit = 3)?.let { p ->
            if (p.size == 3 && p[0].toLongOrNull() == size && p[1].toLongOrNull() == mtime) {
                mem[path] = Triple(size, mtime, p[2])
                return p[2]
            }
        }

        val id = crcIdentity(f) ?: legacyHash(path)
        migrateLegacy(ctx, path, id)
        prefs.edit().putString(cacheKey, "$size:$mtime:$id").apply()
        mem[path] = Triple(size, mtime, id)
        return id
    }

    /** "c<hex>" from the central-directory CRCs of the definition + script. */
    private fun crcIdentity(f: File): String? = try {
        ZipFile(f).use { z ->
            val def = z.getEntry("watch.pxml") ?: z.getEntry("watch.xml") ?: return null
            val script = z.getEntry("scripts/script.txt")
            // Shift the script CRC so (def, script) and (script, def) differ.
            val combined = def.crc xor ((script?.crc ?: 0L) shl 13)
            "c" + java.lang.Long.toHexString(combined)
        }
    } catch (e: Exception) {
        Log.w(TAG, "crcIdentity failed for $f: $e")
        null
    }

    /** Copy wf_cfg_<legacyPathHash>_* → wf_cfg_<id>_* once, if the new namespace is empty. */
    private fun migrateLegacy(ctx: Context, path: String, id: String) {
        val legacy = legacyHash(path)
        if (legacy == id) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val oldPrefix = "${WatchfaceConfigPrefs.PREFIX}${legacy}_"
        val newPrefix = "${WatchfaceConfigPrefs.PREFIX}${id}_"
        val all = prefs.all
        if (all.keys.any { it.startsWith(newPrefix) }) return          // already populated
        val old = all.filterKeys { it.startsWith(oldPrefix) }
        if (old.isEmpty()) return
        val e = prefs.edit()
        for ((k, v) in old) {
            val nk = newPrefix + k.removePrefix(oldPrefix)
            when (v) {
                is Boolean -> e.putBoolean(nk, v)
                is Int -> e.putInt(nk, v)
                is Float -> e.putFloat(nk, v)
                is Long -> e.putLong(nk, v)
                is String -> e.putString(nk, v)
            }
        }
        e.apply()
        Log.i(TAG, "migrated ${old.size} config key(s) $legacy → $id for $path")
    }
}
