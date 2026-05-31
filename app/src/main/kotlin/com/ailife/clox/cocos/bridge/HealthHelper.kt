package com.ailife.clox.cocos.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Health data fetcher for the Lua bridge.
 *
 * Data contract per metric:
 *   steps:    { steps (int), distance (double, km), calories (double, kcal) }
 *   heartRate:{ bpm (int), timestamp (Long, ms), measuring (bool), history[10]: [{bpm, timestamp}] }
 *   spo2:     { percent (int), timestamp (Long, ms), measuring (bool) }
 *
 * fetchAndUpdate() is the main entry point; call it from WatchfaceBridgeManager's async loop.
 * onInstant is called during live measurement with intermediate values.
 */
object HealthHelper {

    enum class Metric { STEPS, HEART_RATE, SPO2 }

    /** Emits after each metric is fetched. Collected by WatchfaceBridgeManager (→ Lua) and
     *  HealthSettingsFragment (→ summary UI). One fetch, two listeners. */
    val dataUpdated = MutableSharedFlow<Metric>(extraBufferCapacity = 10)

    private val latestData = mutableMapOf(
        Metric.STEPS      to emptySteps(),
        Metric.HEART_RATE to emptyHeartRate(),
        Metric.SPO2       to emptySpo2(),
    )

    private val lastFetchTimeMs = mutableMapOf<Metric, Long>()

    fun getLastFetchTimeMs(metric: Metric): Long? = lastFetchTimeMs[metric]

    fun metricFromName(name: String): Metric? = when (name) {
        "steps"     -> Metric.STEPS
        "heartRate" -> Metric.HEART_RATE
        "spo2"      -> Metric.SPO2
        else        -> null
    }

    fun isEnabled(ctx: Context, metric: Metric): Boolean = when (metric) {
        Metric.STEPS      -> HealthPrefs.stepsEnabled(ctx)
        Metric.HEART_RATE -> HealthPrefs.hrEnabled(ctx)
        Metric.SPO2       -> HealthPrefs.spo2Enabled(ctx)
    }

    /** Returns null if the metric is disabled or BHT is not installed. */
    suspend fun fetchAndUpdate(
        metric: Metric,
        ctx: Context,
        onInstant: ((JSONObject) -> Unit)? = null,
    ): JSONObject? {
        if (!isEnabled(ctx, metric)) return null
        if (!BhtApiClient.isInstalled(ctx)) return null

        val data = when (metric) {
            Metric.STEPS      -> fetchSteps(ctx)
            Metric.HEART_RATE -> fetchHeartRate(ctx, onInstant)
            Metric.SPO2       -> fetchSpo2(ctx, onInstant)
        } ?: return null

        latestData[metric] = data
        lastFetchTimeMs[metric] = System.currentTimeMillis()
        dataUpdated.tryEmit(metric)
        return data
    }

    fun getLatestData(metric: Metric): JSONObject = latestData[metric] ?: JSONObject()

    fun updateFromSource(metric: Metric, data: JSONObject) {
        latestData[metric] = data
    }

    fun metricCategory(metric: Metric): String = when (metric) {
        Metric.STEPS      -> "health_steps"
        Metric.HEART_RATE -> "health_heartRate"
        Metric.SPO2       -> "health_spo2"
    }

    // ── Metric fetchers ───────────────────────────────────────────────────────

    private suspend fun fetchSteps(ctx: Context): JSONObject? = coroutineScope {
        val steps = if (HealthPrefs.instantSteps(ctx)) {
            BhtApiClient.requestStepRaw(ctx)
        } else {
            withContext(Dispatchers.IO) { BhtApiClient.queryTodaySteps(ctx) }
        }
        val distanceDeferred = async { BhtApiClient.requestDistance(ctx) }
        val calorieDeferred  = async { BhtApiClient.requestCalorie(ctx) }
        val stepGoalDeferred = async { BhtApiClient.requestGoalStep(ctx) }
        val distance = distanceDeferred.await()
        val calories = calorieDeferred.await()
        val stepGoal = stepGoalDeferred.await()
        JSONObject().apply {
            put("steps",    steps)
            put("distance", distance)
            put("calories", calories)
            put("stepGoal", stepGoal)
        }
    }

    private suspend fun fetchHeartRate(
        ctx: Context,
        onInstant: ((JSONObject) -> Unit)?,
    ): JSONObject? {
        val (history, maxHr) = withContext(Dispatchers.IO) {
            Pair(BhtApiClient.queryRecentHrHistory(ctx, 10), BhtApiClient.queryMaxHrLast24h(ctx))
        }
        val type = if (HealthPrefs.instantHrSpo2(ctx)) "NOW" else "AUTO"
        val bpm = BhtApiClient.requestHeartRate(ctx, type) { instantBpm ->
            onInstant?.invoke(buildHrJson(instantBpm, System.currentTimeMillis(), measuring = true, history, maxHr))
        }
        return buildHrJson(bpm, System.currentTimeMillis(), measuring = false, history, maxHr)
    }

    private suspend fun fetchSpo2(
        ctx: Context,
        onInstant: ((JSONObject) -> Unit)?,
    ): JSONObject? {
        val type = if (HealthPrefs.instantHrSpo2(ctx)) "NOW" else "AUTO"
        val result = BhtApiClient.requestOxygen(ctx, type) { instantPct ->
            onInstant?.invoke(JSONObject().apply {
                put("percent",   instantPct)
                put("timestamp", System.currentTimeMillis())
                put("measuring", true)
            })
        }
        return JSONObject().apply {
            put("percent",   result)
            put("timestamp", System.currentTimeMillis())
            put("measuring", false)
        }
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    private fun buildHrJson(
        bpm: Int,
        timestampMs: Long,
        measuring: Boolean,
        history: List<Pair<Int, Long>>,
        maxHeartRate: Int = 200,
    ): JSONObject {
        val historyArray = JSONArray().apply {
            for ((hBpm, hUnixSec) in history) {
                put(JSONObject().apply {
                    put("bpm",       hBpm)
                    put("timestamp", hUnixSec * 1000L)
                })
            }
        }
        return JSONObject().apply {
            put("bpm",          bpm)
            put("timestamp",    timestampMs)
            put("measuring",    measuring)
            put("history",      historyArray)
            put("maxHeartRate", maxHeartRate)
        }
    }

    private fun emptySteps() = JSONObject().apply {
        put("steps", 0); put("distance", 0.0); put("calories", 0.0)
        put("stepGoal", 10000)
    }

    private fun emptyHeartRate(): JSONObject {
        return buildHrJson(0, 0L, false, emptyList())
    }

    private fun emptySpo2() = JSONObject().apply {
        put("percent", 0); put("timestamp", 0L); put("measuring", false)
    }
}
