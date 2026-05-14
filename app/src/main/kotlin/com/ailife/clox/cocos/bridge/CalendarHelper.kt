package com.ailife.clox.cocos.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CalendarHelper {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun getEvents(ctx: Context): JSONObject {
        // Honour the user's privacy setting before checking system permission
        if (!SensorsPrefs.calendarEnabled(ctx)) return emptyResult()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyResult()
        }

        val now = System.currentTimeMillis()
        val end = now + 30L * 24 * 3600 * 1000 // look ahead 30 days

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_COLOR,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )

        val cursor = ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?" +
                " AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(now.toString(), end.toString()),
            "${CalendarContract.Events.DTSTART} ASC"  // no LIMIT — non-standard in ContentResolver
        ) ?: return emptyResult()

        val events = JSONArray()
        var actualCount = 0
        cursor.use {
            while (it.moveToNext() && actualCount < 10) {
                val dtStart = it.getLong(2)
                val dtEnd = it.getLong(3)
                val color = it.getInt(6)
                events.put(JSONObject().apply {
                    put("exists", true)
                    put("id", it.getString(0) ?: "")
                    put("title", it.getString(1) ?: "")
                    put("beginTime", timeFmt.format(Date(dtStart)))
                    put("endTime", timeFmt.format(Date(dtEnd)))
                    put("beginDate", dateFmt.format(Date(dtStart)))
                    put("endDate", dateFmt.format(Date(dtEnd)))
                    put("allDay", it.getInt(4) == 1)
                    put("location", it.getString(5) ?: "")
                    put("color", String.format("%06X", color and 0xFFFFFF))
                    put("calendar", it.getString(7) ?: "")
                })
                actualCount++
            }
        }

        // Pad to exactly 10 slots so Lua indexing is always consistent
        repeat(10 - actualCount) { events.put(JSONObject().put("exists", false)) }

        return JSONObject().apply {
            put("hasEvents", actualCount > 0)
            put("events", events)
        }
    }

    private fun emptyResult(): JSONObject {
        val events = JSONArray()
        repeat(10) { events.put(JSONObject().put("exists", false)) }
        return JSONObject().apply {
            put("hasEvents", false)
            put("events", events)
        }
    }
}
