package com.ailife.clox.cocos.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object BhtApiClient {

    const val PACKAGE = "com.ailife.betterhealth"

    /** v2 runtime permission — gates both the broadcast API and the secure provider.
     *  Ungranted: broadcasts are silently dropped (requests time out) and provider
     *  queries throw SecurityException (swallowed by the try/catch → zero values). */
    const val PERMISSION_READ_DATA = "$PACKAGE.permission.READ_DATA"

    private const val ACTION_REQUEST = "com.ailife.betterhealth.GET_DATA_API_V2"
    private const val ACTION_REPLY   = "com.ailife.betterhealth.DATA_REPLY"

    private val AUTHORITY = Uri.parse("content://$PACKAGE.health.secure")

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasReadPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION_READ_DATA) == PackageManager.PERMISSION_GRANTED

    // ── Content Provider ──────────────────────────────────────────────────────

    fun queryTodaySteps(ctx: Context): Int {
        val uri = Uri.withAppendedPath(AUTHORITY, "today/steps")
        var result = 0
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    result = cursor.getInt(cursor.getColumnIndexOrThrow("total_steps"))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun queryLastHeartRate(ctx: Context): Pair<Int, Long> {
        val uri = Uri.withAppendedPath(AUTHORITY, "last/heartrate")
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val bpm  = cursor.getInt(cursor.getColumnIndexOrThrow("heartrate"))
                    val unix = cursor.getLong(cursor.getColumnIndexOrThrow("unix"))
                    return Pair(bpm, unix)
                }
            }
        } catch (_: Exception) {}
        return Pair(0, 0L)
    }

    fun queryLastOxygen(ctx: Context): Pair<Int, Long> {
        val uri = Uri.withAppendedPath(AUTHORITY, "last/oxygen")
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pct  = cursor.getInt(cursor.getColumnIndexOrThrow("bloodoxygen"))
                    val unix = cursor.getLong(cursor.getColumnIndexOrThrow("unix"))
                    return Pair(pct, unix)
                }
            }
        } catch (_: Exception) {}
        return Pair(0, 0L)
    }

    fun queryRecentHrHistory(ctx: Context, count: Int = 10): List<Pair<Int, Long>> {
        val nowSec  = System.currentTimeMillis() / 1000
        val startSec = nowSec - 24 * 60 * 60
        val uri = Uri.withAppendedPath(AUTHORITY, "records/range/$startSec/$nowSec")
        val results = mutableListOf<Pair<Int, Long>>()
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val colHr   = cursor.getColumnIndex("heartrate")
                val colUnix = cursor.getColumnIndex("unix")
                if (colHr < 0 || colUnix < 0) return emptyList()
                while (cursor.moveToNext()) {
                    val bpm  = cursor.getInt(colHr)
                    val unix = cursor.getLong(colUnix)
                    if (bpm > 0) results.add(Pair(bpm, unix))
                }
            }
        } catch (_: Exception) {}
        return results.takeLast(count)
    }

    fun queryMaxHrLast24h(ctx: Context): Int {
        val nowSec   = System.currentTimeMillis() / 1000
        val startSec = nowSec - 24 * 60 * 60
        val uri = Uri.withAppendedPath(AUTHORITY, "records/range/$startSec/$nowSec")
        var max = 0
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val colHr = cursor.getColumnIndex("heartrate")
                if (colHr < 0) return 0
                while (cursor.moveToNext()) {
                    val bpm = cursor.getInt(colHr)
                    if (bpm > max) max = bpm
                }
            }
        } catch (_: Exception) {}
        return max
    }

    data class SleepSession(
        val sleepStartSec: Long,
        val wakeSec: Long,
        val awakeSec: Long,
        val remSec: Long,
        val lightSec: Long,
        val deepSec: Long,
        val totalSec: Long,
        val asleepSec: Long,
    )

    /** Last night's sleep session (noon-to-noon window around today's local midnight).
     *  Null when BHT found no viable session (< 4h) or on any error. */
    fun querySleep(ctx: Context, dayStartSec: Long = todayStartSec()): SleepSession? {
        val uri = Uri.withAppendedPath(AUTHORITY, "sleep/$dayStartSec")
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return SleepSession(
                        sleepStartSec = cursor.getLong(cursor.getColumnIndexOrThrow("sleep_start")),
                        wakeSec       = cursor.getLong(cursor.getColumnIndexOrThrow("wake")),
                        awakeSec      = cursor.getLong(cursor.getColumnIndexOrThrow("awake_sec")),
                        remSec        = cursor.getLong(cursor.getColumnIndexOrThrow("rem_sec")),
                        lightSec      = cursor.getLong(cursor.getColumnIndexOrThrow("light_sec")),
                        deepSec       = cursor.getLong(cursor.getColumnIndexOrThrow("deep_sec")),
                        totalSec      = cursor.getLong(cursor.getColumnIndexOrThrow("total_sec")),
                        asleepSec     = cursor.getLong(cursor.getColumnIndexOrThrow("asleep_sec")),
                    )
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Today's stress score 0-100. BHT currently stubs this endpoint at 0; the shape is stable. */
    fun queryStress(ctx: Context, dayStartSec: Long = todayStartSec()): Int {
        val uri = Uri.withAppendedPath(AUTHORITY, "stress/$dayStartSec")
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getInt(cursor.getColumnIndexOrThrow("stress_score"))
                }
            }
        } catch (_: Exception) {}
        return 0
    }

    private fun todayStartSec(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis / 1000L
    }

    // ── Broadcast API ─────────────────────────────────────────────────────────

    private fun buildRequest(data: String, type: String? = null): Intent =
        Intent(ACTION_REQUEST).apply {
            setPackage(PACKAGE)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra("DATA", data)
            if (type != null) putExtra("TYPE", type)
        }

    suspend fun requestStepRaw(ctx: Context): Int =
        requestIntBroadcast(ctx, buildRequest("STEP", "RAW"), timeoutMs = 5_000) ?: 0

    suspend fun requestDistance(ctx: Context): Double =
        requestDoubleBroadcast(ctx, buildRequest("DISTANCE"), timeoutMs = 5_000) ?: 0.0

    suspend fun requestCalorie(ctx: Context): Double =
        requestDoubleBroadcast(ctx, buildRequest("CALORIE"), timeoutMs = 5_000) ?: 0.0

    suspend fun requestHeartRate(
        ctx: Context,
        type: String,
        onInstant: ((Int) -> Unit)? = null,
    ): Int = requestIntBroadcast(ctx, buildRequest("HR", type), timeoutMs = 30_000, onInstant = onInstant) ?: 0

    suspend fun requestOxygen(
        ctx: Context,
        type: String,
        onInstant: ((Int) -> Unit)? = null,
    ): Int = requestIntBroadcast(ctx, buildRequest("OXYGEN", type), timeoutMs = 30_000, onInstant = onInstant) ?: 0

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun requestIntBroadcast(
        ctx: Context,
        intent: Intent,
        timeoutMs: Long,
        onInstant: ((Int) -> Unit)? = null,
    ): Int? = withContext(Dispatchers.Main) {
        val dataTag = intent.getStringExtra("DATA") ?: return@withContext null
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, received: Intent) {
                        if (received.getStringExtra("DATA") != dataTag) return
                        if (received.hasExtra("RESULT_INSTANT")) {
                            onInstant?.invoke(received.getIntExtra("RESULT_INSTANT", 0))
                            return
                        }
                        if (received.getStringExtra("ERROR") != "NONE") {
                            context.unregisterReceiver(this)
                            if (cont.isActive) cont.resume(0)
                            return
                        }
                        val result = received.getIntExtra("RESULT", 0)
                        context.unregisterReceiver(this)
                        if (cont.isActive) cont.resume(result)
                    }
                }
                registerReceiver(ctx, receiver)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
                ctx.sendBroadcast(intent)
            }
        }
    }

    private suspend fun requestDoubleBroadcast(
        ctx: Context,
        intent: Intent,
        timeoutMs: Long,
    ): Double? = withContext(Dispatchers.Main) {
        val dataTag = intent.getStringExtra("DATA") ?: return@withContext null
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, received: Intent) {
                        if (received.getStringExtra("DATA") != dataTag) return
                        if (received.hasExtra("RESULT_INSTANT")) return
                        if (received.getStringExtra("ERROR") != "NONE") {
                            context.unregisterReceiver(this)
                            if (cont.isActive) cont.resume(0.0)
                            return
                        }
                        val result = received.getDoubleExtra("RESULT", 0.0)
                        context.unregisterReceiver(this)
                        if (cont.isActive) cont.resume(result)
                    }
                }
                registerReceiver(ctx, receiver)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
                ctx.sendBroadcast(intent)
            }
        }
    }

    // ── Settings API ─────────────────────────────────────────────────────────

    private const val ACTION_SETTING_REQUEST = "com.ailife.betterhealth.SETTING_API"
    private const val ACTION_SETTING_REPLY   = "com.ailife.betterhealth.SETTING_REPLY"

    /** Read a single setting from BHT. Returns null on timeout or error. */
    suspend fun requestSetting(ctx: Context, key: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine { cont ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, received: Intent) {
                            if (received.getStringExtra("KEY") != key) return
                            context.unregisterReceiver(this)
                            if (received.getStringExtra("ERROR") != "NONE") {
                                if (cont.isActive) cont.resume(null)
                                return
                            }
                            if (cont.isActive) cont.resume(received.getStringExtra("VALUE"))
                        }
                    }
                    val filter = IntentFilter(ACTION_SETTING_REPLY)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        ctx.registerReceiver(receiver, filter)
                    }
                    cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
                    ctx.sendBroadcast(Intent(ACTION_SETTING_REQUEST).apply {
                        setPackage(PACKAGE)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra("KEY", key)
                        putExtra("MODE", "READ")
                    })
                }
            }
        }

    suspend fun requestGoalStep(ctx: Context): Int =
        requestSetting(ctx, "GOAL_STEP")?.toIntOrNull() ?: 10000

    private fun registerReceiver(ctx: Context, receiver: BroadcastReceiver) {
        val filter = IntentFilter(ACTION_REPLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
    }
}
