package com.ailife.clox.watchface.remote

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.WatchfaceRepository
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class WatchfaceWebServer(private val ctx: Context, port: Int = 8080) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/" || uri == "/index.html" -> serveStaticHtml()
            uri == "/favicon.ico" || uri == "/favicon.png" -> serveFavicon()
            uri.startsWith("/api/preview") -> servePreview(session)
            uri.startsWith("/api/") -> serveApi(session, uri.removePrefix("/api"), method)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    // ── Favicon ───────────────────────────────────────────────────────────────

    private fun serveFavicon(): Response {
        return try {
            val drawable = ctx.packageManager.getApplicationIcon(ctx.packageName)
            val bmp = android.graphics.Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(64),
                drawable.intrinsicHeight.coerceAtLeast(64),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            val bytes = out.toByteArray()
            newFixedLengthResponse(Response.Status.OK, "image/png",
                bytes.inputStream(), bytes.size.toLong())
                .also { it.addHeader("Cache-Control", "max-age=86400") }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no icon")
        }
    }

    // ── Preview image from .watch zip ────────────────────────────────────────

    private fun servePreview(session: IHTTPSession): Response {
        val file = containedFile(session.parameters["path"]?.firstOrNull())
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing or out-of-scope path")
        return try {
            val bytes = java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry("preview.jpg")
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no preview")
                zip.getInputStream(entry).readBytes()
            }
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
                .also { it.addHeader("Cache-Control", "max-age=3600") }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    // ── Static HTML page ──────────────────────────────────────────────────────

    private fun serveStaticHtml(): Response {
        val html = try {
            ctx.assets.open("watchface_remote/index.html").bufferedReader().readText()
        } catch (_: Exception) {
            "<html><body><h1>Remote Management</h1><p>Loading UI…</p></body></html>"
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    // ── API ───────────────────────────────────────────────────────────────────

    private fun serveApi(session: IHTTPSession, path: String, method: Method): Response {
        addCorsHeaders()
        return when {
            path == "/watchfaces" && method == Method.GET -> listWatchfaces()
            path == "/folders" && method == Method.GET -> listFolders()
            path.startsWith("/watchfaces/") && method == Method.GET -> downloadWatchface(path)
            path.startsWith("/watchfaces/") && method == Method.DELETE -> deleteWatchface(path)
            path == "/watchfaces" && method == Method.POST -> uploadWatchface(session)
            path.startsWith("/rename") && method == Method.POST -> renameItem(session)
            path.startsWith("/move") && method == Method.POST -> moveItem(session)
            path.startsWith("/folders") && method == Method.POST -> createFolder(session)
            path.startsWith("/folders/") && method == Method.DELETE -> deleteFolder(path)
            path == "/active" && method == Method.GET -> getActive()
            path == "/active" && method == Method.POST -> setActive(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun listWatchfaces(): Response {
        val items = WatchfaceRepository.scan(ctx)
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("path", item.absolutePath)
                put("name", item.name)
                put("folder", item.folder ?: "")
                put("isPinned", item.isPinned)
                put("isActive", item.isActive)
                put("lastModified", item.lastModified)
            })
        }
        return jsonResponse(arr.toString())
    }

    private fun listFolders(): Response {
        val folders = WatchfaceRepository.getFolders(ctx)
        val arr = JSONArray()
        folders.forEach { arr.put(it.name) }
        return jsonResponse(arr.toString())
    }

    private fun downloadWatchface(path: String): Response {
        val file = containedFile(path.removePrefix("/watchfaces/"))
        return if (file != null && file.exists() && file.name.endsWith(".watch")) {
            newFixedLengthResponse(Response.Status.OK, "application/octet-stream", FileInputStream(file), file.length()).apply {
                addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            }
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun deleteWatchface(path: String): Response {
        val file = containedFile(path.removePrefix("/watchfaces/"))
        return if (file != null && file.exists() && file.name.endsWith(".watch")) {
            WatchfaceRepository.delete(file)
            jsonResponse("""{"ok":true}""")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun uploadWatchface(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val tmpPath = files["file"] ?: return badRequest("No file")
            val fileName = session.parameters["filename"]?.firstOrNull() ?: "upload.watch"
            val safeName = if (fileName.endsWith(".watch")) fileName else "$fileName.watch"
            if (!isSafeLeafName(safeName)) return badRequest("Invalid filename")
            val destDir = WatchfaceRepository.getWatchfaceDir(ctx)
            val dest = File(destDir, safeName)
            File(tmpPath).copyTo(dest, overwrite = true)
            jsonResponse("""{"ok":true,"path":"${dest.absolutePath}"}""")
        } catch (e: Exception) {
            serverError(e.message ?: "Upload failed")
        }
    }

    private fun renameItem(session: IHTTPSession): Response {
        return try {
            val body = parseJsonBody(session)
            val newName = body.optString("newName")
            val file = containedFile(body.optString("path")) ?: return badRequest("Invalid path")
            if (!isSafeLeafName(newName)) return badRequest("Invalid name")
            val result = WatchfaceRepository.rename(file, newName)
            if (result != null) jsonResponse("""{"ok":true,"path":"${result.absolutePath}"}""")
            else badRequest("Name already in use")
        } catch (e: Exception) { serverError(e.message ?: "Rename failed") }
    }

    private fun moveItem(session: IHTTPSession): Response {
        return try {
            val body = parseJsonBody(session)
            val targetFolder = body.optString("folder").takeIf { it.isNotEmpty() }
            val file = containedFile(body.optString("path")) ?: return badRequest("Invalid path")
            if (targetFolder != null && !isSafeLeafName(targetFolder)) return badRequest("Invalid folder")
            val targetDir = if (targetFolder != null) File(WatchfaceRepository.getWatchfaceDir(ctx), targetFolder) else null
            val result = WatchfaceRepository.moveToFolder(file, targetDir)
            if (result != null) jsonResponse("""{"ok":true,"path":"${result.absolutePath}"}""")
            else badRequest("Move failed")
        } catch (e: Exception) { serverError(e.message ?: "Move failed") }
    }

    private fun createFolder(session: IHTTPSession): Response {
        return try {
            val body = parseJsonBody(session)
            val name = body.optString("name")
            if (!isSafeLeafName(name)) return badRequest("Invalid folder name")
            val dir = WatchfaceRepository.createFolder(ctx, name)
            if (dir != null) jsonResponse("""{"ok":true}""") else badRequest("Folder exists")
        } catch (e: Exception) { serverError(e.message ?: "Create folder failed") }
    }

    private fun deleteFolder(path: String): Response {
        val name = path.removePrefix("/folders/")
        val dir = WatchfaceRepository.getFolders(ctx).firstOrNull { it.name == name }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        WatchfaceRepository.deleteFolder(dir)
        return jsonResponse("""{"ok":true}""")
    }

    private fun getActive(): Response {
        val path = WatchfacePrefs.getActivePath(ctx) ?: ""
        return jsonResponse("""{"path":"$path"}""")
    }

    private fun setActive(session: IHTTPSession): Response {
        return try {
            val body = parseJsonBody(session)
            val path = body.optString("path")
            if (containedFile(path) == null) return badRequest("Invalid path")
            WatchfacePrefs.setActivePath(ctx, path)
            jsonResponse("""{"ok":true}""")
        } catch (e: Exception) { serverError(e.message ?: "Set active failed") }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun jsonResponse(json: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json).also {
            it.addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)

    private fun serverError(msg: String) =
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, msg)

    private fun addCorsHeaders() {}

    /**
     * Resolve [rawPath] and return it only if it stays inside the watchfaces
     * dir. The remote API is intentionally unauthenticated for everything
     * WITHIN that dir — this containment check is the boundary that stops a
     * request from reading/writing/deleting any path outside it.
     */
    private fun containedFile(rawPath: String?): File? {
        if (rawPath.isNullOrBlank()) return null
        return try {
            val root = WatchfaceRepository.getWatchfaceDir(ctx).canonicalFile
            val f = File(rawPath).canonicalFile
            if (f.path == root.path || f.path.startsWith(root.path + File.separator)) f else null
        } catch (_: Exception) { null }
    }

    /** A single path segment (file/folder name) with no traversal or separators. */
    private fun isSafeLeafName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') &&
            name != "." && name != ".."

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        return if (body.isNotEmpty()) JSONObject(body) else JSONObject()
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
