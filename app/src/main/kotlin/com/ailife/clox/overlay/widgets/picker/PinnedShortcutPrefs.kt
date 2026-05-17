package com.ailife.clox.overlay.widgets.picker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AcceptedShortcut(
    val packageName: String,
    val shortcutId: String,
    val label: String
)

object PinnedShortcutPrefs {

    private const val FILE = "pinned_shortcuts"
    private const val KEY  = "shortcuts"

    fun loadShortcuts(ctx: Context): List<AcceptedShortcut> {
        val json = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AcceptedShortcut(o.getString("package"), o.getString("id"), o.getString("label"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun addShortcut(ctx: Context, shortcut: AcceptedShortcut) {
        val existing = loadShortcuts(ctx).toMutableList()
        // Replace if already present (re-pin), otherwise append
        val idx = existing.indexOfFirst {
            it.packageName == shortcut.packageName && it.shortcutId == shortcut.shortcutId
        }
        if (idx >= 0) existing[idx] = shortcut else existing.add(shortcut)
        save(ctx, existing)
    }

    fun removeShortcut(ctx: Context, packageName: String, shortcutId: String) {
        save(ctx, loadShortcuts(ctx).filter {
            !(it.packageName == packageName && it.shortcutId == shortcutId)
        })
    }

    private fun save(ctx: Context, list: List<AcceptedShortcut>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("package", s.packageName); put("id", s.shortcutId); put("label", s.label)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
