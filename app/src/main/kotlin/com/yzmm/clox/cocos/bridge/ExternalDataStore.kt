package com.yzmm.clox.cocos.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent 10-slot string store written by third-party apps via broadcast.
 * Slots are 1-indexed in the public API; stored internally as slot_N_* keys.
 */
object ExternalDataStore {

    const val SLOT_COUNT = 10
    private const val PREF_FILE = "clox_external_data"

    /** Emits the updated slot index (1-based) after every write. */
    val slotUpdated = MutableSharedFlow<Int>(extraBufferCapacity = 10)

    data class Slot(val data: String, val key: String, val timestamp: Long)

    fun write(ctx: Context, slot: Int, data: String, key: String) {
        require(slot in 1..SLOT_COUNT) { "slot must be 1–$SLOT_COUNT" }
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .putString("slot_${slot}_data", data)
            .putString("slot_${slot}_key",  key)
            .putLong(  "slot_${slot}_time", System.currentTimeMillis())
            .apply()
        slotUpdated.tryEmit(slot)
    }

    fun readAll(ctx: Context): List<Slot> {
        val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        return (1..SLOT_COUNT).map { i ->
            Slot(
                data      = prefs.getString("slot_${i}_data", "") ?: "",
                key       = prefs.getString("slot_${i}_key",  "") ?: "",
                timestamp = prefs.getLong(  "slot_${i}_time", 0L)
            )
        }
    }

    fun toJson(ctx: Context): JSONObject {
        val slots = readAll(ctx)
        return JSONObject().put("slots", JSONArray().also { arr ->
            slots.forEach { s ->
                arr.put(JSONObject().apply {
                    put("data",      s.data)
                    put("key",       s.key)
                    put("timestamp", s.timestamp)
                })
            }
        })
    }
}
