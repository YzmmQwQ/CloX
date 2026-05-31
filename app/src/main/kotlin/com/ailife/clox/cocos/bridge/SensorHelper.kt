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

    fun setEnabled(sensorName: String, enable: Boolean, callback: (String, JSONObject) -> Unit) {
        onData = callback
        if (enable) {
            // Respect the user's privacy setting for this sensor
            if (!SensorsPrefs.sensorEnabled(ctx, sensorName)) return
            // Silently ignore if the device doesn't have the hardware
            val checkType = sensorTypeFor(sensorName) ?: return
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

    private fun registerSensor(name: String) {
        val type = sensorTypeFor(name) ?: return
        sensorManager.getDefaultSensor(type)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // Compass needs both accelerometer and magnetic field
        if (name == "compass") {
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        // Gyroscope is a single sensor; no secondary registration needed
    }

    private fun unregisterSensor(name: String) {
        // Only unregister if no other active sensor needs this type
        val type = sensorTypeFor(name) ?: return
        if (activeSensors.none { sensorTypeFor(it) == type }) {
            sensorManager.getDefaultSensor(type)?.let { sensorManager.unregisterListener(this, it) }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val cb = onData ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if ("accelerometer" in activeSensors) {
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
                if ("barometer" in activeSensors) {
                    cb("barometer", JSONObject().apply {
                        put("pressure", event.values[0].toDouble())
                    })
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if ("gyroscope" in activeSensors) {
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

    private fun sensorTypeFor(name: String): Int? = when (name) {
        "accelerometer" -> Sensor.TYPE_ACCELEROMETER
        "barometer"     -> Sensor.TYPE_PRESSURE
        "gyroscope"     -> Sensor.TYPE_GYROSCOPE
        "compass"       -> Sensor.TYPE_ACCELEROMETER // primary; magnetic field registered separately
        else            -> null
    }
}
