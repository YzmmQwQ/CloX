package com.yzmm.clox.watchface

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Reads the descriptive attributes off the `<Watch …>` root element of a .watch
 * package for the config dialog's Information tab: name, description, author,
 * tags, features, format version. See wmformat/WM_FORMAT_2.0.md §2.
 *
 * Protected faces (stub watch.xml + obfuscated watch.pxml) are handled with the
 * same swapped-alphabet Base64 decode as wf_protect.lua — only enough of the
 * pxml is decoded to cover the root tag.
 *
 * Results are memoised per path, validated by (size, mtime).
 */
object WatchfaceMetadata {

    private const val TAG = "WatchfaceMetadata"
    /** Root tag must close within this many bytes of XML (attributes only, no layers). */
    private const val HEADER_CAP = 32 * 1024

    data class Metadata(
        val name: String,
        val description: String,
        val tags: String,
        val author: String,      // 1.0 faces only; Studio 2.0 doesn't write it
        val webLink: String,     // 1.0 faces only
        val featureCodes: List<String>,
        val shape: String,
        val buildSource: String, // "studio" = WM format 2.0; empty = 1.0/other
        val isProtected: Boolean,
    )

    /** path → (size, mtime, metadata) */
    private val cache = ConcurrentHashMap<String, Triple<Long, Long, Metadata?>>()

    fun read(file: File): Metadata? {
        if (!file.isFile) return null
        val size = file.length()
        val mtime = file.lastModified()
        cache[file.absolutePath]?.let { (s, m, meta) ->
            if (s == size && m == mtime) return meta
        }
        val meta = try {
            parse(file)
        } catch (e: Exception) {
            Log.w(TAG, "metadata read failed for $file: $e")
            null
        }
        cache[file.absolutePath] = Triple(size, mtime, meta)
        return meta
    }

    // ── feature codes (WM_FORMAT_2.0.md §2.2) ─────────────────────────────────

    private val FEATURE_NAMES = mapOf(
        "dt"    to "Date",
        "12"    to "12h time",
        "24"    to "24h time",
        "u"     to "Themeable",
        "an"    to "Analog",
        "bt"    to "Battery",
        "pbt"   to "Phone battery",
        "w"     to "Weather",
        "tsk"   to "Tasker",
        "sport" to "Sport",
        "mn"    to "Moon phase",
        "st"    to "Steps / health",
        "hr"    to "Heart rate",
        "sw"    to "Stopwatch",
        "zne"   to "Time zones",
        "gyr"   to "Gyroscope",
        "cps"   to "Compass",
        "cpn"   to "Complications",
        "sfx"   to "Sound",
        "ani"   to "Animations",
        "gif"   to "GIF",
        "tap"   to "Tap actions",
        "cd"    to "Countdown",
        "cal"   to "Calendar",
    )

    /** Human-readable feature list, unknown codes passed through as-is. */
    fun featureLabels(codes: List<String>): List<String> =
        codes.map { FEATURE_NAMES[it] ?: it }

    // ── parsing ───────────────────────────────────────────────────────────────

    private fun parse(file: File): Metadata? = ZipFile(file).use { zip ->
        val pxml = zip.getEntry("watch.pxml")
        val isProtected = pxml != null
        val header: String = if (pxml != null) {
            // Base64 length covering HEADER_CAP decoded bytes.
            val raw = zip.getInputStream(pxml).use { readUpTo(it, HEADER_CAP / 3 * 4) }
            decodePxml(raw)
        } else {
            val xml = zip.getEntry("watch.xml") ?: return null
            zip.getInputStream(xml).use { String(readUpTo(it, HEADER_CAP), Charsets.UTF_8) }
        }

        val start = header.indexOf("<Watch")
        if (start < 0) return null
        val end = header.indexOf('>', start)
        if (end < 0) return null
        val attrs = parseAttributes(header.substring(start + "<Watch".length, end))

        Metadata(
            name         = attrs["name"] ?: "",
            description  = attrs["description"] ?: "",
            tags         = attrs["tags"] ?: "",
            author       = attrs["author"] ?: "",
            webLink      = attrs["web_link"] ?: "",
            featureCodes = (attrs["features"] ?: "").split(',')
                               .map { it.trim() }.filter { it.isNotEmpty() },
            shape        = attrs["shape"] ?: "",
            buildSource  = attrs["build_source"] ?: "",
            isProtected  = isProtected,
        )
    }

    /** Read at most [max] bytes (readNBytes needs API 33; minSdk is 26). */
    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val n = input.read(buf, off, max - off)
            if (n < 0) break
            off += n
        }
        return if (off == max) buf else buf.copyOf(off)
    }

    /** key="value" pairs from inside a start tag; values get basic XML unescaping. */
    private fun parseAttributes(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i].isWhitespace() || s[i] == '/')) i++
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim()
            val q = s.indexOf('"', eq)
            if (q < 0) break
            val qEnd = s.indexOf('"', q + 1)
            if (qEnd < 0) break
            out[key] = unescapeXml(s.substring(q + 1, qEnd))
            i = qEnd + 1
        }
        return out
    }

    private fun unescapeXml(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#10;", "\n")
        .replace("&amp;", "&")

    // ── protected pxml: Base64 with 'D'↔'g' and '4'↔'L' swapped (wf_protect.lua) ──

    private val DEC = IntArray(128) { -1 }.also { dec ->
        val std = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in std.indices) dec[std[i].code] = i
        dec['D'.code] = 32; dec['g'.code] = 3
        dec['4'.code] = 11; dec['L'.code] = 56
    }

    private fun decodePxml(data: ByteArray): String {
        val out = StringBuilder(data.size / 4 * 3)
        var bits = 0
        var nbits = 0
        for (b in data) {
            val c = b.toInt() and 0x7F
            if (c == '='.code) break
            val v = DEC[c]
            if (v < 0) continue
            bits = (bits shl 6) or v
            nbits += 6
            if (nbits >= 8) {
                nbits -= 8
                out.append(((bits shr nbits) and 0xFF).toChar())
            }
        }
        return out.toString()
    }
}
