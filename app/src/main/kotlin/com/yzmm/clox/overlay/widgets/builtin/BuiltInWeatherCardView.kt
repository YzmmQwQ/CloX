package com.yzmm.clox.overlay.widgets.builtin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yzmm.clox.R
import com.yzmm.clox.cocos.bridge.WeatherIcon
import com.yzmm.clox.cocos.bridge.WeatherPrefs
import com.yzmm.clox.cocos.bridge.WeatherRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
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
    private val currentRow: LinearLayout
    private val forecastRow: LinearLayout
    private val detailContainer: LinearLayout
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
    private var showTodayDetail = false
    private var refreshing = false

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
        currentRow   = findViewById(R.id.currentWeatherRow)
        forecastRow  = findViewById(R.id.forecastRow)
        detailContainer = findViewById(R.id.todayDetailContainer)
        btnDelete    = findViewById(R.id.btnDelete)
        dragHandle   = findViewById(R.id.dragHandle)

        btnDelete.setOnClickListener { onDelete?.invoke() }
        // Top half: toggle between the future forecast and today's full detail.
        currentRow.setOnClickListener {
            if (editing) return@setOnClickListener
            showTodayDetail = !showTodayDetail
            refresh()
        }
        // Forecast row: toggle daily <-> hourly (only meaningful in forecast mode).
        forecastRow.isClickable = true
        forecastRow.setOnClickListener {
            if (editing || showTodayDetail) return@setOnClickListener
            showHourly = !showHourly
            WeatherPrefs.saveWidgetHourly(context, showHourly)
            refresh()
        }
        // "xxm ago": tap to refresh immediately.
        tvLastUpdate.isClickable = true
        tvLastUpdate.setOnClickListener {
            if (editing) return@setOnClickListener
            triggerRefresh()
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

        if (!refreshing) updateLastUpdateLabel(lastUpdate)

        if (showTodayDetail) {
            forecastRow.visibility = View.GONE
            detailContainer.visibility = View.VISIBLE
            bindTodayDetail(json, round)
        } else {
            detailContainer.visibility = View.GONE
            forecastRow.visibility = View.VISIBLE
            // Forecast row — tapping it toggles between 5-day and hourly
            forecastRow.removeAllViews()
            if (showHourly) bindHourlyRow(json, round) else bindDailyRow(json, round)
        }
    }

    private fun bindTodayDetail(json: JSONObject, round: Boolean) {
        detailContainer.removeAllViews()
        val items = mutableListOf<Pair<String, String>>()

        val high = json.optDouble("highTemp", Double.NaN)
        val low  = json.optDouble("lowTemp", Double.NaN)
        if (!high.isNaN() || !low.isNaN()) {
            items += context.getString(R.string.weather_detail_high_low) to
                "${formatTemp(high, round)} / ${formatTemp(low, round)}"
        }
        if (json.has("humidity")) items += context.getString(R.string.weather_detail_humidity) to
            context.getString(R.string.weather_detail_humidity_value, json.optInt("humidity"))
        if (json.has("windSpeed")) items += context.getString(R.string.weather_detail_wind) to
            context.getString(R.string.weather_detail_wind_value,
                compass(json.optInt("windDirection", 0)), json.optDouble("windSpeed", 0.0))
        if (json.has("pressure")) items += context.getString(R.string.weather_detail_pressure) to
            context.getString(R.string.weather_detail_pressure_value, json.optDouble("pressure", 0.0))
        if (json.has("cloudiness")) items += context.getString(R.string.weather_detail_cloud) to
            context.getString(R.string.weather_detail_cloud_value, json.optInt("cloudiness"))
        if (json.has("rainVolume")) items += context.getString(R.string.weather_detail_rain) to
            context.getString(R.string.weather_detail_rain_value, json.optDouble("rainVolume", 0.0))
        json.optString("sunrise").takeIf { it.isNotBlank() }?.let {
            items += context.getString(R.string.weather_detail_sunrise) to it
        }
        json.optString("sunset").takeIf { it.isNotBlank() }?.let {
            items += context.getString(R.string.weather_detail_sunset) to it
        }
        if (json.has("moonPhase")) items += context.getString(R.string.weather_detail_moon) to
            moonPhaseName(json.optDouble("moonPhase", 0.0))

        // Two columns per row so the detail stays compact.
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) }
            }
            row.addView(detailCell(items[i].first, items[i].second))
            if (i + 1 < items.size) row.addView(detailCell(items[i + 1].first, items[i + 1].second))
            else row.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            detailContainer.addView(row)
            i += 2
        }
    }

    private fun detailCell(label: String, value: String): View {
        val onSurfaceVar = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(onSurfaceVar)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(onSurface)
            })
        }
    }

    private val compassDirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private fun compass(deg: Int): String {
        val n = ((deg % 360) + 360) % 360
        return compassDirs[((n + 22) / 45) % 8]
    }

    private fun moonPhaseName(phase: Double): String {
        val names = resources.getStringArray(R.array.moon_phase_names)
        val idx = (Math.round(phase * 8) % 8).toInt().coerceIn(0, 7)
        return names[idx]
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun triggerRefresh() {
        if (refreshing || !WeatherPrefs.enabled(context)) return
        val scope = flowScope ?: return
        refreshing = true
        tvLastUpdate.text = context.getString(R.string.weather_updating)
        tvLastUpdate.visibility = View.VISIBLE
        scope.launch {
            val err = WeatherRepository.refresh(context)
            refreshing = false
            if (err != null) {
                Log.w("WeatherWidget", "manual refresh failed", err)
                updateLastUpdateLabel()
            }
            // On success, weatherFlow.collect rebinds the card (which refreshes the label).
        }
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
