package com.yzmm.clox.cocos.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

// ── Icon codes (sent to Lua as conditionIcon int) ─────────────────────────────
// Matches the numeric codes in the watchface example: 1=clear, 2=few clouds, etc.
object WeatherIcon {
    const val CLEAR            = 1
    const val FEW_CLOUDS       = 2
    const val SCATTERED_CLOUDS = 3
    const val OVERCAST         = 4
    const val DRIZZLE          = 9
    const val RAIN             = 10
    const val THUNDERSTORM     = 11
    const val SNOW             = 13
    const val SLEET            = 14
    const val FOG              = 50
}

// ── Result model ──────────────────────────────────────────────────────────────

data class WeatherResult(
    val currentTemp: Float,
    val highTemp: Float,
    val lowTemp: Float,
    val conditionText: String,
    val conditionIcon: Int,
    val humidity: Int,
    val pressure: Float,
    val windSpeed: Float,
    val windDirection: Int,
    val cloudiness: Int,
    val rainVolume: Float,
    val isDaytime: Boolean,
    val sunrise: String,
    val sunset: String,
    val moonPhase: Double,
    val lastUpdate: Long,
    val hourly: List<HourlyItem>,
    val daily: List<DailyItem>,
) {
    // hourOfDay = -1 means "derive from position"; providers set it where available
    data class HourlyItem(val temp: Float, val conditionText: String, val conditionIcon: Int, val hourOfDay: Int = -1)
    data class DailyItem(val avgTemp: Float, val high: Float, val low: Float, val conditionText: String, val conditionIcon: Int)

    fun toJson(roundTemp: Boolean, locationName: String = "", tempUnit: String = "C"): JSONObject {
        fun Float.t() = if (roundTemp) Math.round(this).toFloat() else this
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) // used for hourly index
        return JSONObject().apply {
            put("currentTemp",         currentTemp.t())
            put("highTemp",            highTemp.t())
            put("lowTemp",             lowTemp.t())
            put("conditionText",       conditionText)
            put("conditionIcon",       conditionIcon)
            put("humidity",            humidity)
            put("pressure",            pressure.toDouble())
            put("windSpeed",           windSpeed.toDouble())
            put("windDirection",  windDirection)
            put("cloudiness",     cloudiness)
            put("rainVolume",     rainVolume.toDouble())
            put("isDaytime",      isDaytime)
            put("sunrise",        sunrise)
            put("sunset",         sunset)
            put("moonPhase",           moonPhase)
            put("lastUpdate",          lastUpdate)
            put("locationName",        locationName)
            put("tempUnit",            tempUnit)
            put("hourly", JSONArray().also { arr ->
                hourly.forEachIndexed { index, h ->
                    arr.put(JSONObject().apply {
                        put("temp",          h.temp.t())
                        put("conditionText", h.conditionText)
                        put("conditionIcon", h.conditionIcon)
                        put("hourOfDay",     if (h.hourOfDay >= 0) h.hourOfDay else (currentHour + index) % 24)
                    })
                }
            })
            put("daily", JSONArray().also { arr ->
                daily.forEach { d ->
                    arr.put(JSONObject().apply {
                        put("avgTemp",       d.avgTemp.t())
                        put("high",          d.high.t())
                        put("low",           d.low.t())
                        put("conditionText", d.conditionText)
                        put("conditionIcon", d.conditionIcon)
                    })
                }
            })
        }
    }

}

// ── Provider interface ────────────────────────────────────────────────────────

sealed class WeatherProvider {

    abstract suspend fun fetch(ctx: Context, lat: Double, lon: Double): WeatherResult

    companion object {
        fun forKey(key: String): WeatherProvider = when (key) {
            "openmeteo"      -> OpenMeteo()
            "openweathermap" -> OpenWeatherMap()
            "weatherapi"     -> WeatherApiCom()
            "wttrin"         -> WttrIn()
            else             -> MetNo()
        }

        // Moon phase 0.0 (new) → 0.5 (full) → 1.0 (new again)
        fun moonPhase(date: LocalDate): Double {
            val knownNewMoon = LocalDate.of(2000, 1, 6)
            val days = (ChronoUnit.DAYS.between(knownNewMoon, date) % 29).let {
                if (it < 0) it + 29 else it
            }
            return days / 29.53058867
        }

        fun isDaytime(sunrise: String, sunset: String): Boolean {
            return try {
                val fmt = SimpleDateFormat("HH:mm", Locale.US)
                val now = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                now in sunrise..sunset
            } catch (_: Exception) { true }
        }
    }

    // ── Shared HTTP helper ────────────────────────────────────────────────────

    protected suspend fun fetchJson(urlStr: String, userAgent: String? = null): String =
        withContext(Dispatchers.IO) {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }

    protected fun langCode(ctx: Context) =
        if (WeatherPrefs.autoLanguage(ctx)) Locale.getDefault().language.lowercase(Locale.US)
        else "en"

    // 6 days: {wf0d*}..{wf5d*} — today + 5 ahead (WM serves all six)
    protected fun emptyHourly() = List(12) { WeatherResult.HourlyItem(0f, "", 0) }
    protected fun emptyDaily()  = List(6)  { WeatherResult.DailyItem(0f, 0f, 0f, "", 0) }

    // ── MET.no ───────────────────────────────────────────────────────────────

    class MetNo : WeatherProvider() {
        private val ua = "CloX/1.0 (Android Smartwatch; github.com/ailife)"

        override suspend fun fetch(ctx: Context, lat: Double, lon: Double): WeatherResult {
            val forecastUrl = "https://api.met.no/weatherapi/locationforecast/2.0/compact" +
                "?lat=%.4f&lon=%.4f".format(lat, lon)
            val root = JSONObject(fetchJson(forecastUrl, ua))
            val timeseries = root.getJSONObject("properties").getJSONArray("timeseries")

            val cur = timeseries.getJSONObject(0)
            val inst = cur.getJSONObject("data").getJSONObject("instant").getJSONObject("details")

            val currentTemp = inst.getDouble("air_temperature").toFloat()
            val windSpeed   = (inst.optDouble("wind_speed", 0.0) * 3.6).toFloat() // m/s → km/h
            val windDir     = inst.optDouble("wind_from_direction", 0.0).toInt()
            val humidity    = inst.optDouble("relative_humidity", 0.0).toInt()
            val pressure    = inst.optDouble("air_pressure_at_sea_level", 0.0).toFloat()
            val cloud       = inst.optDouble("cloud_area_fraction", 0.0).toInt()

            var rain = 0f
            var symbolCode = "clearsky_day"
            val curData = cur.getJSONObject("data")
            if (curData.has("next_1_hours")) {
                val n1 = curData.getJSONObject("next_1_hours")
                symbolCode = n1.getJSONObject("summary").getString("symbol_code")
                rain = n1.getJSONObject("details").optDouble("precipitation_amount", 0.0).toFloat()
            } else if (curData.has("next_6_hours")) {
                symbolCode = curData.getJSONObject("next_6_hours").getJSONObject("summary").getString("symbol_code")
            }

            // Sunrise/sunset via v3 API. No `offset` query param: its "+" was
            // sent unencoded (server saw " 08:00" → error), and the response
            // times carry their own offset anyway — which Instant.parse can't
            // read (it wants ISO_INSTANT/Z), so both ends were broken and the
            // 06:00/18:00 defaults always won. OffsetDateTime handles the
            // "+HH:MM"-suffixed times the API returns.
            val today = LocalDate.now()
            val zone  = ZoneId.systemDefault()
            val sunUrl = "https://api.met.no/weatherapi/sunrise/3.0/sun" +
                "?lat=%.4f&lon=%.4f&date=%s".format(lat, lon, today)
            var sunrise = "06:00"; var sunset = "18:00"
            try {
                val sunRoot = JSONObject(fetchJson(sunUrl, ua))
                val props = sunRoot.getJSONObject("properties")
                val fmt = DateTimeFormatter.ofPattern("HH:mm")
                sunrise = java.time.OffsetDateTime.parse(props.getJSONObject("sunrise").getString("time"))
                    .atZoneSameInstant(zone).toLocalTime().format(fmt)
                sunset = java.time.OffsetDateTime.parse(props.getJSONObject("sunset").getString("time"))
                    .atZoneSameInstant(zone).toLocalTime().format(fmt)
            } catch (_: Exception) {}

            val daytime = isDaytime(sunrise, sunset)
            val icon = mapMetNoSymbol(symbolCode, daytime)

            // Hourly: next 12 timeseries entries with next_1_hours
            val hourly = mutableListOf<WeatherResult.HourlyItem>()
            for (i in 0 until minOf(timeseries.length(), 24)) {
                if (hourly.size >= 12) break
                val entry = timeseries.getJSONObject(i)
                val eData = entry.getJSONObject("data")
                if (!eData.has("next_1_hours")) continue
                val eTemp = eData.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature").toFloat()
                val eSym  = eData.getJSONObject("next_1_hours").getJSONObject("summary").getString("symbol_code")
                hourly += WeatherResult.HourlyItem(eTemp, symText(eSym), mapMetNoSymbol(eSym, true))
            }

            // 6-day forecast: group by date, min/max from instant temps
            val daily = buildMetNoDailyForecast(timeseries, today)

            val high = daily.firstOrNull()?.high ?: currentTemp
            val low  = daily.firstOrNull()?.low  ?: currentTemp

            return WeatherResult(
                currentTemp, high, low, symText(symbolCode), icon,
                humidity, pressure, windSpeed, windDir, cloud, rain,
                daytime, sunrise, sunset, moonPhase(today), System.currentTimeMillis(),
                hourly.ifEmpty { emptyHourly() }, daily.ifEmpty { emptyDaily() }
            )
        }

        private fun buildMetNoDailyForecast(ts: JSONArray, today: LocalDate): List<WeatherResult.DailyItem> {
            data class DayAgg(var min: Float = Float.MAX_VALUE, var max: Float = Float.MIN_VALUE, var sym: String = "clearsky_day")
            val map = LinkedHashMap<LocalDate, DayAgg>()
            val zone = ZoneId.systemDefault()
            for (i in 0 until ts.length()) {
                val entry = ts.getJSONObject(i)
                val date = Instant.parse(entry.getString("time")).atZone(zone).toLocalDate()
                if (date < today || date >= today.plusDays(6)) continue
                val agg = map.getOrPut(date) { DayAgg() }
                val t = entry.getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature").toFloat()
                if (t < agg.min) agg.min = t; if (t > agg.max) agg.max = t
                val d = entry.getJSONObject("data")
                if (d.has("next_6_hours")) agg.sym = d.getJSONObject("next_6_hours").getJSONObject("summary").getString("symbol_code")
                else if (d.has("next_1_hours") && agg.sym == "clearsky_day") agg.sym = d.getJSONObject("next_1_hours").getJSONObject("summary").getString("symbol_code")
            }
            return map.values.map { a ->
                val avg = (a.min + a.max) / 2f
                WeatherResult.DailyItem(avg, a.max, a.min, symText(a.sym), mapMetNoSymbol(a.sym, true))
            }
        }

        // met.no symbol bases are glued-together words ("partlycloudy",
        // "lightrainshowersandthunder") — WM shows spaced text ("Partly
        // cloudy"). Greedy longest-prefix tokenisation over the known parts.
        private val SYM_TOKENS = listOf(
            "showers", "partly", "cloudy", "thunder", "clear", "heavy", "light",
            "sleet", "storm", "rain", "snow", "fair", "fog", "sky", "and"
        )
        private fun symText(sym: String): String {
            var rest = sym.split("_")[0]
            val words = mutableListOf<String>()
            var glue = StringBuilder()
            while (rest.isNotEmpty()) {
                val tok = SYM_TOKENS.firstOrNull { rest.startsWith(it) }
                if (tok != null) {
                    if (glue.isNotEmpty()) { words += glue.toString(); glue = StringBuilder() }
                    words += tok
                    rest = rest.substring(tok.length)
                } else {
                    glue.append(rest[0])
                    rest = rest.substring(1)
                }
            }
            if (glue.isNotEmpty()) words += glue.toString()
            return words.joinToString(" ").replaceFirstChar { it.uppercase(Locale.US) }
        }

        private fun mapMetNoSymbol(sym: String, day: Boolean): Int {
            val base = sym.split("_")[0]
            return when {
                base == "clearsky"                          -> WeatherIcon.CLEAR
                base == "fair"                              -> WeatherIcon.FEW_CLOUDS
                base == "partlycloudy"                      -> WeatherIcon.SCATTERED_CLOUDS
                base == "cloudy"                            -> WeatherIcon.OVERCAST
                base == "fog"                               -> WeatherIcon.FOG
                base.contains("thunder")                    -> WeatherIcon.THUNDERSTORM
                base.contains("sleet")                      -> WeatherIcon.SLEET
                base.contains("snow")                       -> WeatherIcon.SNOW
                base.contains("rain") || base.contains("shower") -> WeatherIcon.RAIN
                else                                        -> WeatherIcon.CLEAR
            }
        }
    }

    // ── Open-Meteo (free, no key) ─────────────────────────────────────────────

    class OpenMeteo : WeatherProvider() {
        override suspend fun fetch(ctx: Context, lat: Double, lon: Double): WeatherResult {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=%.4f&longitude=%.4f".format(lat, lon) +
                "&current=temperature_2m,relative_humidity_2m,precipitation,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,weather_code" +
                "&hourly=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                "&timezone=auto&forecast_days=6&forecast_hours=12"

            val root = JSONObject(fetchJson(url))
            val cur  = root.getJSONObject("current")

            val currentTemp = cur.getDouble("temperature_2m").toFloat()
            val humidity    = cur.optInt("relative_humidity_2m")
            val rain        = cur.optDouble("precipitation", 0.0).toFloat()
            val cloud       = cur.optInt("cloud_cover")
            val pressure    = cur.optDouble("pressure_msl", 0.0).toFloat()
            val windSpeed   = cur.optDouble("wind_speed_10m", 0.0).toFloat()
            val windDir     = cur.optInt("wind_direction_10m")
            val wmoCode     = cur.optInt("weather_code")

            val daily   = root.getJSONObject("daily")
            val sunrise = parseIsoTime(daily.getJSONArray("sunrise").getString(0))
            val sunset  = parseIsoTime(daily.getJSONArray("sunset").getString(0))
            val daytime = isDaytime(sunrise, sunset)
            val today   = LocalDate.now()

            val high5 = daily.getJSONArray("temperature_2m_max")
            val low5  = daily.getJSONArray("temperature_2m_min")
            val wmo5  = daily.getJSONArray("weather_code")
            val high  = high5.getDouble(0).toFloat()
            val low   = low5.getDouble(0).toFloat()

            val hourlyObj = root.getJSONObject("hourly")
            val hTemps   = hourlyObj.getJSONArray("temperature_2m")
            val hCodes   = hourlyObj.getJSONArray("weather_code")
            val hourly   = (0 until minOf(hTemps.length(), 12)).map { i ->
                val code = hCodes.getInt(i)
                WeatherResult.HourlyItem(hTemps.getDouble(i).toFloat(), wmoText(ctx, code), mapWmo(code))
            }

            val days = (0 until minOf(high5.length(), 6)).map { i ->
                val dCode = wmo5.getInt(i)
                val avg = ((high5.getDouble(i) + low5.getDouble(i)) / 2).toFloat()
                WeatherResult.DailyItem(avg, high5.getDouble(i).toFloat(), low5.getDouble(i).toFloat(), wmoText(ctx, dCode), mapWmo(dCode))
            }

            return WeatherResult(
                currentTemp, high, low, wmoText(ctx, wmoCode), mapWmo(wmoCode, daytime),
                humidity, pressure, windSpeed, windDir, cloud, rain,
                daytime, sunrise, sunset, moonPhase(today), System.currentTimeMillis(),
                hourly, days
            )
        }

        private fun parseIsoTime(iso: String) = iso.substringAfter("T").substring(0, 5)

        private fun mapWmo(code: Int, day: Boolean = true): Int = when {
            code == 0                    -> WeatherIcon.CLEAR
            code in 1..1                 -> WeatherIcon.FEW_CLOUDS
            code == 2                    -> WeatherIcon.SCATTERED_CLOUDS
            code == 3                    -> WeatherIcon.OVERCAST
            code in 45..48               -> WeatherIcon.FOG
            code in 51..57               -> WeatherIcon.DRIZZLE
            code in 61..67               -> WeatherIcon.RAIN
            code in 71..77               -> WeatherIcon.SNOW
            code in 80..82               -> WeatherIcon.RAIN
            code in 85..86               -> WeatherIcon.SNOW
            code in 95..99               -> WeatherIcon.THUNDERSTORM
            else                         -> WeatherIcon.CLEAR
        }

        private fun wmoText(ctx: android.content.Context, code: Int) = when {
            code == 0            -> ctx.getString(com.yzmm.clox.R.string.weather_clear_sky)
            code == 1            -> ctx.getString(com.yzmm.clox.R.string.weather_mainly_clear)
            code == 2            -> ctx.getString(com.yzmm.clox.R.string.weather_partly_cloudy)
            code == 3            -> ctx.getString(com.yzmm.clox.R.string.weather_overcast)
            code in 45..48       -> ctx.getString(com.yzmm.clox.R.string.weather_fog)
            code in 51..55       -> ctx.getString(com.yzmm.clox.R.string.weather_drizzle)
            code in 61..65       -> ctx.getString(com.yzmm.clox.R.string.weather_rain)
            code in 71..75       -> ctx.getString(com.yzmm.clox.R.string.weather_snow)
            code in 80..82       -> ctx.getString(com.yzmm.clox.R.string.weather_rain_showers)
            code in 85..86       -> ctx.getString(com.yzmm.clox.R.string.weather_snow_showers)
            code in 95..99       -> ctx.getString(com.yzmm.clox.R.string.weather_thunderstorm)
            else                 -> ctx.getString(com.yzmm.clox.R.string.weather_unknown)
        }
    }

    // ── WeatherAPI.com (needs API key) ────────────────────────────────────────

    class WeatherApiCom : WeatherProvider() {
        override suspend fun fetch(ctx: Context, lat: Double, lon: Double): WeatherResult {
            val key  = WeatherPrefs.weatherApiKey(ctx).ifEmpty { "f9c3f11c61d9453da3d45340252402" }
            val lang = langCode(ctx)
            val url  = "https://api.weatherapi.com/v1/forecast.json?key=$key&q=%.4f,%.4f&days=6&aqi=no&alerts=no&lang=$lang".format(lat, lon)
            val root = JSONObject(fetchJson(url))
            if (root.has("error")) throw Exception(root.getJSONObject("error").getString("message"))

            val cur  = root.getJSONObject("current")
            val currentTemp = cur.getDouble("temp_c").toFloat()
            val windSpeed   = cur.optDouble("wind_kph", 0.0).toFloat()
            val windDir     = cur.optInt("wind_degree")
            val humidity    = cur.optInt("humidity")
            val pressure    = cur.optDouble("pressure_mb", 0.0).toFloat()
            val cloud       = cur.optInt("cloud")
            val rain        = cur.optDouble("precip_mm", 0.0).toFloat()
            val condCode    = cur.getJSONObject("condition").getInt("code")
            val condText    = cur.getJSONObject("condition").getString("text")

            val fdays = root.getJSONObject("forecast").getJSONArray("forecastday")
            val astro = fdays.getJSONObject(0).getJSONObject("astro")
            val sunrise = parseAmPm(astro.getString("sunrise"))
            val sunset  = parseAmPm(astro.getString("sunset"))
            val daytime = isDaytime(sunrise, sunset)

            val high = fdays.getJSONObject(0).getJSONObject("day").getDouble("maxtemp_c").toFloat()
            val low  = fdays.getJSONObject(0).getJSONObject("day").getDouble("mintemp_c").toFloat()

            // Hourly: first 12 from today
            val hourly = mutableListOf<WeatherResult.HourlyItem>()
            val todayHour = fdays.getJSONObject(0).getJSONArray("hour")
            for (i in 0 until minOf(todayHour.length(), 12)) {
                val h = todayHour.getJSONObject(i)
                hourly += WeatherResult.HourlyItem(
                    h.getDouble("temp_c").toFloat(),
                    h.getJSONObject("condition").getString("text"),
                    mapWeatherApiCode(h.getJSONObject("condition").getInt("code"))
                )
            }

            val daily = (0 until minOf(fdays.length(), 6)).map { i ->
                val d = fdays.getJSONObject(i).getJSONObject("day")
                val max = d.getDouble("maxtemp_c").toFloat()
                val min = d.getDouble("mintemp_c").toFloat()
                val dc  = d.getJSONObject("condition").getInt("code")
                WeatherResult.DailyItem((max + min) / 2f, max, min, d.getJSONObject("condition").getString("text"), mapWeatherApiCode(dc))
            }

            return WeatherResult(
                currentTemp, high, low, condText, mapWeatherApiCode(condCode, daytime),
                humidity, pressure, windSpeed, windDir, cloud, rain,
                daytime, sunrise, sunset, moonPhase(LocalDate.now()), System.currentTimeMillis(),
                hourly, daily
            )
        }

        private fun parseAmPm(s: String): String {
            return try {
                val fmt = SimpleDateFormat("hh:mm a", Locale.US)
                SimpleDateFormat("HH:mm", Locale.US).format(fmt.parse(s)!!)
            } catch (_: Exception) { "00:00" }
        }

        private fun mapWeatherApiCode(code: Int, day: Boolean = true): Int = when (code) {
            1000 -> WeatherIcon.CLEAR
            1003 -> WeatherIcon.FEW_CLOUDS
            1006 -> WeatherIcon.SCATTERED_CLOUDS
            1009 -> WeatherIcon.OVERCAST
            1030 -> WeatherIcon.FOG
            1135, 1147 -> WeatherIcon.FOG
            1063, 1180, 1183, 1186, 1189, 1192, 1195, 1240, 1243, 1246, 1150, 1153 -> WeatherIcon.RAIN
            1066, 1114, 1210, 1213, 1216, 1219, 1222, 1225, 1255, 1258, 1117 -> WeatherIcon.SNOW
            1069, 1072, 1168, 1171, 1198, 1201, 1204, 1207, 1237, 1249, 1252, 1261, 1264, 1317, 1320 -> WeatherIcon.SLEET
            1087, 1273, 1276, 1279, 1282 -> WeatherIcon.THUNDERSTORM
            1264, 1282 -> WeatherIcon.DRIZZLE
            else -> WeatherIcon.CLEAR
        }
    }

    // ── OpenWeatherMap One Call 3.0 (needs API key) ───────────────────────────

    class OpenWeatherMap : WeatherProvider() {
        override suspend fun fetch(ctx: Context, lat: Double, lon: Double): WeatherResult {
            val key  = WeatherPrefs.owmApiKey(ctx).ifEmpty { "ba2412b4dbc464d10202d6dc04658618" }
            val lang = langCode(ctx)
            val url  = "https://api.openweathermap.org/data/3.0/onecall?lat=%.4f&lon=%.4f&appid=$key&units=metric&lang=$lang".format(lat, lon)
            val root = JSONObject(fetchJson(url))

            val cur = root.getJSONObject("current")
            val currentTemp = cur.getDouble("temp").toFloat()
            val windSpeed   = (cur.optDouble("wind_speed", 0.0) * 3.6).toFloat()
            val windDir     = cur.optInt("wind_deg")
            val humidity    = cur.optInt("humidity")
            val pressure    = cur.optDouble("pressure", 0.0).toFloat()
            val cloud       = cur.optInt("clouds")
            var rain = 0f
            if (cur.has("rain")) rain += cur.getJSONObject("rain").optDouble("1h", 0.0).toFloat()
            if (cur.has("snow")) rain += cur.getJSONObject("snow").optDouble("1h", 0.0).toFloat()

            val fmt = SimpleDateFormat("HH:mm", Locale.US)
            val sunrise = fmt.format(Date(cur.getLong("sunrise") * 1000))
            val sunset  = fmt.format(Date(cur.getLong("sunset")  * 1000))
            val daytime = isDaytime(sunrise, sunset)

            val wArr = cur.getJSONArray("weather").getJSONObject(0)
            val condCode = wArr.getInt("id")
            val condText = wArr.getString("description").replaceFirstChar { it.uppercase() }

            val daily = root.getJSONArray("daily")
            val high  = daily.getJSONObject(0).getJSONObject("temp").getDouble("max").toFloat()
            val low   = daily.getJSONObject(0).getJSONObject("temp").getDouble("min").toFloat()

            val hourlyArr = root.getJSONArray("hourly")
            val hourly = (0 until minOf(hourlyArr.length(), 12)).map { i ->
                val h = hourlyArr.getJSONObject(i)
                val hw = h.getJSONArray("weather").getJSONObject(0)
                WeatherResult.HourlyItem(h.getDouble("temp").toFloat(), hw.getString("description").replaceFirstChar { it.uppercase() }, mapOwmCode(hw.getInt("id")))
            }

            val days = (0 until minOf(daily.length(), 6)).map { i ->
                val d = daily.getJSONObject(i)
                val max = d.getJSONObject("temp").getDouble("max").toFloat()
                val min = d.getJSONObject("temp").getDouble("min").toFloat()
                val dw  = d.getJSONArray("weather").getJSONObject(0)
                WeatherResult.DailyItem((max + min) / 2f, max, min, dw.getString("description").replaceFirstChar { it.uppercase() }, mapOwmCode(dw.getInt("id")))
            }

            return WeatherResult(
                currentTemp, high, low, condText, mapOwmCode(condCode, daytime),
                humidity, pressure, windSpeed, windDir, cloud, rain,
                daytime, sunrise, sunset, moonPhase(LocalDate.now()), System.currentTimeMillis(),
                hourly, days
            )
        }

        private fun mapOwmCode(id: Int, day: Boolean = true): Int = when {
            id in 200..232 -> WeatherIcon.THUNDERSTORM
            id in 300..321 -> WeatherIcon.DRIZZLE
            id in 500..531 -> WeatherIcon.RAIN
            id in 600..622 -> if (id in listOf(611, 612, 613)) WeatherIcon.SLEET else WeatherIcon.SNOW
            id in 700..781 -> WeatherIcon.FOG
            id == 800      -> WeatherIcon.CLEAR
            id == 801      -> WeatherIcon.FEW_CLOUDS
            id == 802      -> WeatherIcon.SCATTERED_CLOUDS
            id in 803..804 -> WeatherIcon.OVERCAST
            else           -> WeatherIcon.CLEAR
        }
    }

    // ── wttr.in (free, no key, 3-day only) ───────────────────────────────────

    class WttrIn : WeatherProvider() {
        override suspend fun fetch(ctx: Context, lat: Double, lon: Double): WeatherResult {
            val url  = "https://wttr.in/%.2f,%.2f?format=j1".format(lat, lon)
            val root = JSONObject(fetchJson(url))

            val cc = root.getJSONArray("current_condition").getJSONObject(0)
            val currentTemp = cc.getString("temp_C").toFloat()
            val windSpeed   = cc.optString("windspeedKmph", "0").toFloat()
            val windDir     = cc.optString("winddirDegree", "0").toInt()
            val humidity    = cc.optString("humidity", "0").toInt()
            val pressure    = cc.optString("pressure", "0").toFloat()
            val cloud       = cc.optString("cloudcover", "0").toInt()
            val rain        = cc.optString("precipMM", "0").toFloat()
            val wCode       = cc.optString("weatherCode", "113").toInt()
            val condText    = cc.getJSONArray("weatherDesc").getJSONObject(0).optString("value", "")

            val weather = root.getJSONArray("weather")
            val astro   = weather.getJSONObject(0).getJSONArray("astronomy").getJSONObject(0)
            val sunrise = parseAmPm12(astro.getString("sunrise"))
            val sunset  = parseAmPm12(astro.getString("sunset"))
            val daytime = isDaytime(sunrise, sunset)

            val day0    = weather.getJSONObject(0)
            val high    = day0.getString("maxtempC").toFloat()
            val low     = day0.getString("mintempC").toFloat()
            val today   = LocalDate.now()

            // Collect up to 12 hourly slots across available days (8 per day, every 3h)
            val hourly = mutableListOf<WeatherResult.HourlyItem>()
            outer@ for (di in 0 until weather.length()) {
                val dayHourly = weather.getJSONObject(di).getJSONArray("hourly")
                for (hi in 0 until dayHourly.length()) {
                    if (hourly.size >= 12) break@outer
                    val h = dayHourly.getJSONObject(hi)
                    val hCode = h.optString("weatherCode", "113").toInt()
                    hourly += WeatherResult.HourlyItem(
                        h.optString("tempC", "0").toFloat(),
                        h.getJSONArray("weatherDesc").getJSONObject(0).optString("value", ""),
                        mapWttrCode(hCode)
                    )
                }
            }

            // 3-day forecast (pad to 6 with empty)
            val daily = mutableListOf<WeatherResult.DailyItem>()
            for (di in 0 until minOf(weather.length(), 6)) {
                val d = weather.getJSONObject(di)
                val max = d.getString("maxtempC").toFloat()
                val min = d.getString("mintempC").toFloat()
                val avg = d.getString("avgtempC").toFloat()
                val dh  = d.getJSONArray("hourly")
                val dCode = dh.getJSONObject(dh.length() / 2).optString("weatherCode", "113").toInt()
                val dText = dh.getJSONObject(dh.length() / 2).getJSONArray("weatherDesc").getJSONObject(0).optString("value", "")
                daily += WeatherResult.DailyItem(avg, max, min, dText, mapWttrCode(dCode))
            }
            while (daily.size < 6) daily += WeatherResult.DailyItem(0f, 0f, 0f, "", 0)

            // Moon phase from wttr.in astronomy
            val moonIllum = astro.optString("moon_illumination", "0").toDoubleOrNull() ?: 0.0
            val moonPh    = moonPhase(today)

            return WeatherResult(
                currentTemp, high, low, condText, mapWttrCode(wCode, daytime),
                humidity, pressure, windSpeed, windDir, cloud, rain,
                daytime, sunrise, sunset, moonPh, System.currentTimeMillis(),
                hourly.ifEmpty { emptyHourly() }, daily
            )
        }

        private fun parseAmPm12(s: String): String = try {
            SimpleDateFormat("HH:mm", Locale.US).format(SimpleDateFormat("hh:mm a", Locale.US).parse(s.trim())!!)
        } catch (_: Exception) { "00:00" }

        private fun mapWttrCode(code: Int, day: Boolean = true): Int = when (code) {
            113 -> WeatherIcon.CLEAR
            116 -> WeatherIcon.FEW_CLOUDS
            119 -> WeatherIcon.SCATTERED_CLOUDS
            122 -> WeatherIcon.OVERCAST
            143, 248, 260 -> WeatherIcon.FOG
            176, 293, 296, 299, 302, 305, 308, 353, 356, 359 -> WeatherIcon.RAIN
            179, 227, 230, 323, 326, 329, 332, 335, 338, 368, 371 -> WeatherIcon.SNOW
            182, 185, 317, 320, 350, 362, 365, 374, 377 -> WeatherIcon.SLEET
            200, 386, 389, 392, 395 -> WeatherIcon.THUNDERSTORM
            263, 266, 281, 284 -> WeatherIcon.DRIZZLE
            else -> WeatherIcon.CLEAR
        }
    }
}

// ── Elevation helper (open-meteo, no API key) ─────────────────────────────────

object ElevationApi {
    private const val UA = "CloX/1.0 (Android Smartwatch)"

    /** Terrain elevation in meters for the given coordinates, or null on failure. */
    suspend fun fetch(lat: Double, lon: Double): Double? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/elevation?latitude=%.4f&longitude=%.4f"
                .format(Locale.US, lat, lon)
            val conn = URL(url).openConnection() as HttpURLConnection
            val json = try {
                conn.setRequestProperty("User-Agent", UA)
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
            // Response shape: {"elevation":[38.0]}
            json.optJSONArray("elevation")?.optDouble(0)?.takeIf { !it.isNaN() }
        } catch (_: Exception) { null }
    }
}

// ── IP-based location helper ──────────────────────────────────────────────────

object LocationByIp {
    private const val URL = "http://ip-api.com/json/?fields=lat,lon,city"
    private const val UA  = "CloX/1.0 (Android Smartwatch)"

    suspend fun fetch(): Triple<Double, Double, String>? = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL(URL).openConnection() as HttpURLConnection
            val json = try {
                conn.setRequestProperty("User-Agent", UA)
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
            if (json.has("lat") && json.has("lon")) {
                Triple(json.getDouble("lat"), json.getDouble("lon"), json.optString("city", ""))
            } else null
        } catch (_: Exception) { null }
    }
}
