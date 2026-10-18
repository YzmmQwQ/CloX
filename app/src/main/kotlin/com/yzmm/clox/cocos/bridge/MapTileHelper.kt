package com.yzmm.clox.cocos.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * OSM tile fetcher for the watchface `map` layer. Lua asks for a tile via the
 * `requestMapTile` action (lat/lon/zoom); the tile is served from the disk
 * cache or downloaded once, then pushed back as a `map` category snapshot:
 * `{ file, zoom, tx, ty }` — Lua matches tx/ty against the tile it asked for.
 */
object MapTileHelper {
    private const val TAG = "MapTileHelper"
    private const val LAT_LIMIT = 85.05112878
    private const val MAX_CACHE_TILES = 64

    suspend fun fetch(ctx: Context, lat: Double, lon: Double, zoom: Int): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val n = 1 shl zoom
                val tx = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
                val latRad = Math.toRadians(lat.coerceIn(-LAT_LIMIT, LAT_LIMIT))
                val ty = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n)
                    .toInt().coerceIn(0, n - 1)

                val dir = File(ctx.cacheDir, "map_tiles").apply { mkdirs() }
                val file = File(dir, "${zoom}_${tx}_$ty.png")
                if (!file.exists() || file.length() == 0L) {
                    trimCache(dir)
                    val conn = URL("https://tile.openstreetmap.org/$zoom/$tx/$ty.png")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    // OSM tile usage policy requires an identifying User-Agent
                    conn.setRequestProperty("User-Agent", "CloX-Launcher/1.0 (watchface map layer)")
                    try {
                        conn.inputStream.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                if (!file.exists() || file.length() == 0L) return@withContext null
                JSONObject().apply {
                    put("file", file.absolutePath)
                    put("zoom", zoom)
                    put("tx", tx)
                    put("ty", ty)
                }
            } catch (e: Exception) {
                Log.w(TAG, "tile fetch failed ($lat,$lon z$zoom)", e)
                null
            }
        }

    private fun trimCache(dir: File) {
        val tiles = dir.listFiles() ?: return
        if (tiles.size < MAX_CACHE_TILES) return
        tiles.sortedBy { it.lastModified() }
            .take(tiles.size - MAX_CACHE_TILES / 2)
            .forEach { it.delete() }
    }
}
