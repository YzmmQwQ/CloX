package com.ailife.clox.overlay.widgets.builtin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.cocos.bridge.WeatherIcon
import com.ailife.clox.cocos.bridge.WeatherPrefs
import com.ailife.clox.cocos.bridge.WeatherRepository
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class BuiltInWeatherCardView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : MaterialCardView(ctx, attrs) {

    private val tvCity: TextView
    private val ivIcon: ImageView
    private val tvTemp: TextView
    private val tvCondition: TextView
    private val tvHigh: TextView
    private val tvLow: TextView
    private val tvLastUpdate: TextView
    private val forecastRow: LinearLayout
    private val btnDelete: ImageButton
    private val dragHandle: ImageView

    private var flowScope: CoroutineScope? = null
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = updateLastUpdateLabel()
    }

    var onDelete: (() -> Unit)? = null
    var onDragStart: ((View) -> Unit)? = null

    private var editing = false
    private var showHourly = WeatherPrefs.widgetHourly(ctx)

    init {
        radius = resources.getDimension(R.dimen.widget_card_radius)
        LayoutInflater.from(ctx).inflate(R.layout.card_builtin_weather, this, true)
        tvCity       = findViewById(R.id.tvWeatherCity)
        ivIcon       = findViewById(R.id.ivWeatherIcon)
        tvTemp       = findViewById(R.id.tvWeatherTemp)
        tvCondition  = findViewById(R.id.tvWeatherCondition)
        tvHigh       = findViewById(R.id.tvWeatherHigh)
        tvLow        = findViewById(R.id.tvWeatherLow)
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        forecastRow  = findViewById(R.id.forecastRow)
        btnDelete    = findViewById(R.id.btnDelete)
        dragHandle   = findViewById(R.id.dragHandle)

        btnDelete.setOnClickListener { onDelete?.invoke() }
        setOnClickListener {
            if (editing) return@setOnClickListener
            showHourly = !showHourly
            WeatherPrefs.saveWidgetHourly(context, showHourly)
            refresh()
        }
        dragHandle.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { onDragStart?.invoke(v); true } else false
        }
        refresh()
    }

    fun refresh() {
        val json = WeatherRepository.getLatestJson()
        tvCity.text = when {
            !WeatherPrefs.enabled(context) -> context.getString(R.string.widget_weather_disabled)
            json == null -> context.getString(R.string.widget_weather_no_data)
            else -> WeatherPrefs.city(context).ifBlank {
                context.getString(R.string.widget_weather_location_not_set)
            }
        }
        if (json != null) {
            bindWeather(json)
        } else {
            tvTemp.text = context.getString(R.string.weather_temp_placeholder)
            tvCondition.text = context.getString(R.string.widget_weather_no_data)
            tvHigh.text = ""; tvLow.text = ""
            ivIcon.setImageResource(R.drawable.ic_weather_cloudy)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        flowScope = CoroutineScope(Dispatchers.Main).also { scope ->
            scope.launch {
                WeatherRepository.weatherFlow.collect { refresh() }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(tickReceiver)
        flowScope?.cancel()
        flowScope = null
    }

    fun setEditMode(editing: Boolean) {
        this.editing = editing
        val vis = if (editing) View.VISIBLE else View.GONE
        btnDelete.visibility = vis
        dragHandle.visibility = vis
    }

    private fun bindWeather(json: JSONObject) {
        val round = WeatherPrefs.roundTemp(context)
        val temp = json.optDouble("currentTemp", Double.NaN)
        val high = json.optDouble("highTemp", Double.NaN)
        val low  = json.optDouble("lowTemp", Double.NaN)
        val conditionText = json.optString("conditionText", "")
        val conditionIcon = json.optInt("conditionIcon", 1)
        val lastUpdate    = json.optLong("lastUpdate", 0L)

        tvTemp.text      = formatTemp(temp, round)
        tvHigh.text      = String.format(context.getString(R.string.weather_high_indicator), formatTemp(high, round))
        tvLow.text       = String.format(context.getString(R.string.weather_low_indicator), formatTemp(low, round))
        tvCondition.text = conditionText
        ivIcon.setImageResource(conditionIconRes(conditionIcon))

        updateLastUpdateLabel(lastUpdate)

        // Forecast row — tapping the card toggles between 5-day and hourly
        forecastRow.removeAllViews()
        if (showHourly) bindHourlyRow(json, round) else bindDailyRow(json, round)
    }

    private fun bindDailyRow(json: JSONObject, round: Boolean) {
        val daily = json.optJSONArray("daily") ?: return
        val count = minOf(daily.length(), 5)
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        repeat(count) { i ->
            val day = daily.getJSONObject(i)
            val dayLabel = if (i == 0) {
                context.getString(R.string.widget_weather_today)
            } else {
                val cal = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, i) }
                dayFmt.format(cal.time)
            }
            forecastRow.addView(buildDayCell(dayLabel, day.optDouble("high"), day.optDouble("low"),
                day.optInt("conditionIcon", 1), round))
        }
    }

    private fun bindHourlyRow(json: JSONObject, round: Boolean) {
        val hourly = json.optJSONArray("hourly")
        if (hourly == null || hourly.length() == 0) { bindDailyRow(json, round); return }
        val count = minOf(hourly.length(), 5)
        val is24h = android.text.format.DateFormat.is24HourFormat(context)
        repeat(count) { i ->
            val hour = hourly.getJSONObject(i)
            val label = if (i == 0) {
                context.getString(R.string.widget_weather_now)
            } else {
                formatHour(hour.optInt("hourOfDay", -1), is24h)
            }
            forecastRow.addView(buildHourCell(label, hour.optDouble("temp"),
                hour.optInt("conditionIcon", 1), round))
        }
    }

    private fun buildDayCell(
        dayLabel: String,
        high: Double, low: Double,
        iconCode: Int, round: Boolean
    ): View {
        val cell = LayoutInflater.from(context).inflate(R.layout.item_weather_forecast_day, forecastRow, false)
        cell.findViewById<TextView>(R.id.tvForecastDay).text   = dayLabel
        cell.findViewById<ImageView>(R.id.ivForecastIcon).setImageResource(conditionIconRes(iconCode))
        cell.findViewById<TextView>(R.id.tvForecastHigh).text  = formatTemp(high, round)
        cell.findViewById<TextView>(R.id.tvForecastLow).text   = formatTemp(low, round)
        return cell
    }

    private fun buildHourCell(label: String, temp: Double, iconCode: Int, round: Boolean): View {
        val cell = LayoutInflater.from(context).inflate(R.layout.item_weather_forecast_day, forecastRow, false)
        cell.findViewById<TextView>(R.id.tvForecastDay).text  = label
        cell.findViewById<ImageView>(R.id.ivForecastIcon).setImageResource(conditionIconRes(iconCode))
        cell.findViewById<TextView>(R.id.tvForecastHigh).text = formatTemp(temp, round)
        // INVISIBLE (not GONE) keeps the cell the same height as a daily cell, so
        // toggling modes doesn't change the card height.
        cell.findViewById<TextView>(R.id.tvForecastLow).visibility = View.INVISIBLE
        return cell
    }

    private fun formatHour(hourOfDay: Int, is24h: Boolean): String {
        if (hourOfDay !in 0..23) return ""
        if (is24h) return "%02d".format(hourOfDay)
        val h = hourOfDay % 12
        return "${if (h == 0) 12 else h}${if (hourOfDay < 12) "AM" else "PM"}"
    }

    private fun updateLastUpdateLabel(epochMs: Long = WeatherRepository.getLatestJson()?.optLong("lastUpdate", 0L) ?: 0L) {
        if (epochMs > 0L) {
            tvLastUpdate.text       = relativeTime(epochMs)
            tvLastUpdate.visibility = View.VISIBLE
        } else {
            tvLastUpdate.visibility = View.GONE
        }
    }

    private fun relativeTime(epochMs: Long): String {
        val mins = ((System.currentTimeMillis() - epochMs) / 60_000).coerceAtLeast(0)
        return when {
            mins < 1  -> context.getString(R.string.weather_updated_just_now)
            mins < 60 -> context.getString(R.string.weather_updated_minutes_ago, mins)
            else      -> context.getString(R.string.weather_updated_hours_ago, mins / 60)
        }
    }

    private fun formatTemp(t: Double, round: Boolean): String {
        if (t.isNaN()) return context.getString(R.string.weather_temp_placeholder)
        return if (round) "${t.toInt()}°" else "%.1f°".format(t)
    }

    private fun conditionIconRes(code: Int): Int = when (code) {
        WeatherIcon.CLEAR            -> R.drawable.ic_weather_sunny
        WeatherIcon.FEW_CLOUDS,
        WeatherIcon.SCATTERED_CLOUDS -> R.drawable.ic_weather_partly_cloudy
        WeatherIcon.OVERCAST         -> R.drawable.ic_weather_cloudy
        WeatherIcon.DRIZZLE,
        WeatherIcon.RAIN             -> R.drawable.ic_weather_rain
        WeatherIcon.THUNDERSTORM     -> R.drawable.ic_weather_thunder
        WeatherIcon.SNOW,
        WeatherIcon.SLEET            -> R.drawable.ic_weather_snow
        WeatherIcon.FOG              -> R.drawable.ic_weather_fog
        else                         -> R.drawable.ic_weather_cloudy
    }
}
