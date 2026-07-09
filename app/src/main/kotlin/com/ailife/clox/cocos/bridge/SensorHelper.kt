package com.ailife.clox.cocos.bridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONObject

/**
 * Streams accelerometer, compass (magnetic field + orientation), and barometer data to Lua.
 * Each sensor is enabled/disabled independently from the Lua side to save battery.
 *
 * Data contract:
 *   accelerometer: { x, y, z }  (m/s²)
 *   compass:       { azimuth, pitch, roll } (degrees, derived from accelerometer + magnetic field)
 *   barometer:     { pressure }  (hPa)
 */
class SensorHelper(private val ctx: Context) : SensorEventListener {

    private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var onData: ((sensor: String, data: JSONObject) -> Unit)? = null

    // Gravity smoothed for compass calculation
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    private val activeSensors = mutableSetOf<String>()

    // AOD power-save: re-register everything at a slow hardware rate and gate
    // emission to ~1 Hz per stream. The face renders at 1 FPS in AOD, so any
    // faster delivery is pure CPU/HAL wakeup waste.
    private var lowPower = false
    private val lastEmitMs = mutableMapOf<String, Long>()

    /** Slow all active sensors down for the AOD dim state (and back). Idempotent. */
    fun setLowPower(low: Boolean) {
        if (low == lowPower) return
        lowPower = low
        lastEmitMs.clear()
        if (activeSensors.isEmpty()) return
        sensorManager.unregisterListener(this)
        activeSensors.forEach { registerSensor(it) }
    }

    /** Rate-gate a stream while in low power: at most one emit per second. */
    private fun emitAllowed(name: String): Boolean {
        if (!lowPower) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (lastEmitMs[name] ?: 0L) < 950L) return false
        lastEmitMs[name] = now
        return true
    }

    fun setEnabled(sensorName: String, enable: Boolean, callback: (String, JSONObject) -> Unit) {
        onData = callback
        if (enable) {
            // Respect the user's privacy setting for this sensor
            if (!SensorsPrefs.sensorEnabled(ctx, sensorName)) return
            // Silently ignore if the device doesn't have the hardware
            val checkType = typesFor(sensorName).firstOrNull() ?: return
            if (sensorManager.getDefaultSensor(checkType) == null) return

            if (activeSensors.add(sensorName)) registerSensor(sensorName)
        } else {
            if (activeSensors.remove(sensorName)) unregisterSensor(sensorName)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        activeSensors.clear()
    }

    // Every hardware sensor type a named stream needs. Compass derives its
    // orientation from accelerometer + magnetic field, so BOTH must register
    // on enable and BOTH must drop on disable (only unregistering the
    // "primary" type used to leak the magnetometer forever — visible in
    // dumpsys sensorservice as a never-removed AK09918C registration).
    private fun typesFor(name: String): List<Int> = when (name) {
        "accelerometer" -> listOf(Sensor.TYPE_ACCELEROMETER)
        "barometer"     -> listOf(Sensor.TYPE_PRESSURE)
        "gyroscope"     -> listOf(Sensor.TYPE_GYROSCOPE)
        "compass"       -> listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD)
        else            -> emptyList()
    }

    private fun registerSensor(name: String) {
        // ~16 Hz for live faces; 2 Hz requested in AOD (the emit gate handles
        // hardware that ignores slow sampling periods).
        val periodUs = if (lowPower) 500_000 else SensorManager.SENSOR_DELAY_UI
        for (type in typesFor(name)) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, periodUs)
            }
        }
    }

    private fun unregisterSensor(name: String) {
        // Drop each hardware type this stream needed — unless another active
        // stream still needs it (compass and accelerometer share the
        // accelerometer hardware).
        for (type in typesFor(name)) {
            if (activeSensors.none { type in typesFor(it) }) {
                sensorManager.getDefaultSensor(type)?.let { sensorManager.unregisterListener(this, it) }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val cb = onData ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if ("accelerometer" in activeSensors && emitAllowed("accelerometer")) {
                    cb("accelerometer", JSONObject().apply {
                        put("x", event.values[0].toDouble())
                        put("y", event.values[1].toDouble())
                        put("z", event.values[2].toDouble())
                    })
                }
                if ("compass" in activeSensors) {
                    val alpha = 0.8f
                    gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                    gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                    gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
                    emitCompass(cb)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if ("compass" in activeSensors) {
                    geomagnetic[0] = event.values[0]
                    geomagnetic[1] = event.values[1]
                    geomagnetic[2] = event.values[2]
                    emitCompass(cb)
                }
            }
            Sensor.TYPE_PRESSURE -> {
                if ("barometer" in activeSensors && emitAllowed("barometer")) {
                    cb("barometer", JSONObject().apply {
                        put("pressure", event.values[0].toDouble())
                    })
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if ("gyroscope" in activeSensors && emitAllowed("gyroscope")) {
                    cb("gyroscope", JSONObject().apply {
                        put("x", event.values[0].toDouble())
                        put("y", event.values[1].toDouble())
                        put("z", event.values[2].toDouble())
                    })
                }
            }
        }
    }

    private fun emitCompass(cb: (String, JSONObject) -> Unit) {
        if (!emitAllowed("compass")) return
        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        if (SensorManager.getRotationMatrix(rotMatrix, null, gravity, geomagnetic)) {
            SensorManager.getOrientation(rotMatrix, orientation)
            cb("compass", JSONObject().apply {
                put("azimuth", Math.toDegrees(orientation[0].toDouble()))
                put("pitch",   Math.toDegrees(orientation[1].toDouble()))
                put("roll",    Math.toDegrees(orientation[2].toDouble()))
            })
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
