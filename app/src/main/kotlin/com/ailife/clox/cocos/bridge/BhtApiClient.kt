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

    private const val ACTION_REQUEST = "com.ailife.betterhealth.GET_DATA_API"
    private const val ACTION_REPLY   = "com.ailife.betterhealth.DATA_REPLY"

    private val AUTHORITY = Uri.parse("content://$PACKAGE.healthprovider")

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

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
        // query a wide window and take the last `count` records with HR > 0
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

    private fun registerReceiver(ctx: Context, receiver: BroadcastReceiver) {
        val filter = IntentFilter(ACTION_REPLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
    }
}
