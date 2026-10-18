package com.yzmm.clox.debug

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports/imports user settings as JSON. SharedPreferences.getX() throws
 * ClassCastException if the stored type doesn't match the getter (e.g. a Float
 * key read with getInt), and raw JSON numbers don't distinguish int/long/float —
 * so every value is written with an explicit type tag rather than as a bare
 * JSON primitive, to round-trip the exact original type on import.
 */
object SettingsBackupManager {

    private const val TAG = "SettingsBackupManager"
    private const val VERSION = 1
    private val NAMED_PREF_FILES = listOf("app_list", "icon_settings", "pinned_shortcuts")

    fun export(ctx: Context, uri: Uri): Boolean = try {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("default", prefsToJson(PreferenceManager.getDefaultSharedPreferences(ctx)))

        val named = JSONObject()
        for (name in NAMED_PREF_FILES) {
            named.put(name, prefsToJson(ctx.getSharedPreferences(name, Context.MODE_PRIVATE)))
        }
        root.put("named", named)

        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(root.toString(2).toByteArray(Charsets.UTF_8))
        } ?: run {
            Log.e(TAG, "export: openOutputStream returned null for $uri")
            return false
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "export failed for $uri", e)
        false
    }

    fun import(ctx: Context, uri: Uri): Boolean = try {
        val text = ctx.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: run {
            Log.e(TAG, "import: openInputStream returned null for $uri")
            return false
        }
        val root = JSONObject(text)

        root.optJSONObject("default")?.let {
            jsonToPrefs(it, PreferenceManager.getDefaultSharedPreferences(ctx))
        }
        root.optJSONObject("named")?.let { named ->
            for (name in NAMED_PREF_FILES) {
                named.optJSONObject(name)?.let {
                    jsonToPrefs(it, ctx.getSharedPreferences(name, Context.MODE_PRIVATE))
                }
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "import failed for $uri", e)
        false
    }

    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val obj = JSONObject()
        for ((key, value) in prefs.all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "bool").put("v", value)
                is Int     -> entry.put("t", "int").put("v", value)
                is Long    -> entry.put("t", "long").put("v", value)
                is Float   -> entry.put("t", "float").put("v", value.toDouble())
                is String  -> entry.put("t", "string").put("v", value)
                is Set<*>  -> entry.put("t", "set").put("v", JSONArray(value.toList()))
                else -> continue
            }
            obj.put(key, entry)
        }
        return obj
    }

    private fun jsonToPrefs(json: JSONObject, prefs: SharedPreferences) {
        val editor = prefs.edit()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = json.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "bool"   -> editor.putBoolean(key, entry.optBoolean("v"))
                "int"    -> editor.putInt(key, entry.optInt("v"))
                "long"   -> editor.putLong(key, entry.optLong("v"))
                "float"  -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "string" -> editor.putString(key, entry.optString("v"))
                "set"    -> {
                    val arr = entry.optJSONArray("v") ?: JSONArray()
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }
}
