package com.yzmm.clox.qs

sealed class TileState {
    data class WifiState(
        val enabled: Boolean,
        val connected: Boolean,
        val ssid: String?,
        val frequency: String?,   // "2.4G" / "5G" / "6G"
        val signalLevel: Int      // 0-4
    ) : TileState()

    data class DataState(
        val enabled: Boolean,          // data toggle is on in settings
        val activelyRouting: Boolean,  // cellular is the current active network
        val networkType: String?,      // "2G" / "3G" / "4G" / "5G"
        val signalLevel: Int,          // 0-4
        val operatorName: String?
    ) : TileState()

    data class BtState(
        val enabled: Boolean,
        val connectedDeviceName: String?  // name of first connected device, null if none
    ) : TileState()

    data class VolumeState(
        val level: Int,
        val max: Int,
        val muted: Boolean,
        val ringerMode: Int   // AudioManager.RINGER_MODE_NORMAL/VIBRATE/SILENT
    ) : TileState()

    data class DndState(
        val active: Boolean,
        val ringerMode: Int
    ) : TileState()

    data class RotateState(val locked: Boolean) : TileState()

    data class FlashlightState(val on: Boolean) : TileState()

    data class LocationState(val enabled: Boolean) : TileState()

    // Placeholder — no backend yet
    data class AodState(val enabled: Boolean) : TileState()

    data class AirplaneState(val enabled: Boolean) : TileState()

    data class HotspotState(val enabled: Boolean) : TileState()

    data class BatteryState(val level: Int, val charging: Boolean) : TileState()
}
