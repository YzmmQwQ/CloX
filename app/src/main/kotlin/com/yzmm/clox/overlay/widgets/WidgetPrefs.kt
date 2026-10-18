package com.yzmm.clox.overlay.widgets

import android.content.Context
import androidx.preference.PreferenceManager
import com.yzmm.clox.overlay.widgets.model.WidgetCardConfig
import org.json.JSONArray

object WidgetPrefs {
    private const val KEY_CARDS = "widget_drawer_cards"

    fun loadCards(ctx: Context): MutableList<WidgetCardConfig> {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(KEY_CARDS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val result = mutableListOf<WidgetCardConfig>()
            for (i in 0 until arr.length()) {
                WidgetCardConfig.fromJson(arr.getJSONObject(i))?.let { result.add(it) }
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveCards(ctx: Context, cards: List<WidgetCardConfig>) {
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit().putString(KEY_CARDS, arr.toString()).apply()
    }
}
