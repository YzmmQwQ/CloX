package com.ailife.clox.qs

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QsStateManager(private val ctx: Context) {

    private val handler = Handler(Looper.getMainLooper())

    // ── State flows ──────────────────────────────────────────────────────────

    private val _wifi = MutableStateFlow(TileState.WifiState(false, false, null, null, 0))
    val wifi: StateFlow<TileState.WifiState> = _wifi.asStateFlow()

    private val _data = MutableStateFlow(TileState.DataState(false, false, null, 0, null))
    val data: StateFlow<TileState.DataState> = _data.asStateFlow()

    private val _bluetooth = MutableStateFlow(TileState.BtState(false, null))
    val bluetooth: StateFlow<TileState.BtState> = _bluetooth.asStateFlow()

    private val _volume = MutableStateFlow(TileState.VolumeState(0, 15, false, android.media.AudioManager.RINGER_MODE_NORMAL))
    val volume: StateFlow<TileState.VolumeState> = _volume.asStateFlow()

    private val _dnd = MutableStateFlow(TileState.DndState(false, AudioManager.RINGER_MODE_NORMAL))
    val dnd: StateFlow<TileState.DndState> = _dnd.asStateFlow()

    private val _rotate = MutableStateFlow(TileState.RotateState(false))
    val rotate: StateFlow<TileState.RotateState> = _rotate.asStateFlow()

    private val _flashlight = MutableStateFlow(TileState.FlashlightState(false))
    val flashlight: StateFlow<TileState.FlashlightState> = _flashlight.asStateFlow()

    private val _location = MutableStateFlow(TileState.LocationState(false))
    val location: StateFlow<TileState.LocationState> = _location.asStateFlow()

    private val _aod = MutableStateFlow(TileState.AodState(false))
    val aod: StateFlow<TileState.AodState> = _aod.asStateFlow()

    private val _airplane = MutableStateFlow(TileState.AirplaneState(false))
    val airplane: StateFlow<TileState.AirplaneState> = _airplane.asStateFlow()

    private val _hotspot = MutableStateFlow(TileState.HotspotState(false))
    val hotspot: StateFlow<TileState.HotspotState> = _hotspot.asStateFlow()

    private val _battery = MutableStateFlow(TileState.BatteryState(0, false))
    val battery: StateFlow<TileState.BatteryState> = _battery.asStateFlow()

    private val _time = MutableStateFlow("")
    val time: StateFlow<String> = _time.asStateFlow()

    // For external overrides (e.g. flashlight toggle from tile)
    fun setFlashlightState(on: Boolean) { _flashlight.value = TileState.FlashlightState(on) }

    fun stateFor(tileId: String): StateFlow<out TileState>? = when (tileId) {
        "wifi"       -> wifi
        "data"       -> data
        "bluetooth"  -> bluetooth
        "volume"     -> volume
        "dnd"        -> dnd
        "rotate"     -> rotate
        "flashlight" -> flashlight
        "location"   -> location
        "aod"        -> aod
        "airplane"   -> airplane
        "hotspot"    -> hotspot
        else         -> null
    }

    // ── Receivers ────────────────────────────────────────────────────────────

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { updateTime() }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                    BatteryManager.BATTERY_STATUS_CHARGING
            _battery.value = TileState.BatteryState((level * 100f / scale).toInt(), charging)
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { updateWifi() }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { updateBluetooth() }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { updateVolume() }
    }

    private val airplaneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            _airplane.value = TileState.AirplaneState(intent.getBooleanExtra("state", false))
        }
    }

    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra("wifi_state", 0)
            _hotspot.value = TileState.HotspotState(state == 13 || state == 12) // ENABLED=13, ENABLING=12
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { updateLocation() }
    }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            updateDnd()
            updateVolume()  // ringer mode is part of VolumeState — update together
        }
    }

    private val rotateObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { updateRotate() }
    }

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var dataCallback: ConnectivityManager.NetworkCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    // ── attach / detach ──────────────────────────────────────────────────────

    fun attach() {
        val cmgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Time
        ctx.registerReceiver(timeReceiver, IntentFilter(Intent.ACTION_TIME_TICK))

        // Battery (sticky)
        val batteryIntent = ctx.registerReceiver(batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let { batteryReceiver.onReceive(ctx, it) }

        // WiFi
        ctx.registerReceiver(wifiReceiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        })
        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateWifi() }
            override fun onLost(network: Network) { updateWifi() }
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { updateWifi() }
        }
        cmgr.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiCallback!!)

        // Bluetooth
        ctx.registerReceiver(btReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })

        // Volume
        ctx.registerReceiver(volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

        // Airplane
        ctx.registerReceiver(airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))

        // Hotspot
        ctx.registerReceiver(hotspotReceiver, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))

        // Location
        ctx.registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))

        // DND / ringer
        ctx.registerReceiver(dndReceiver, IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        })

        // Rotate
        ctx.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false, rotateObserver)

        // Mobile data network callback
        dataCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateData(cmgr) }
            override fun onLost(network: Network) { updateData(cmgr) }
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { updateData(cmgr) }
        }
        cmgr.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build(),
            dataCallback!!)

        // Phone state for signal + network type — requires READ_PHONE_STATE; skip if not granted
        try {
            @Suppress("DEPRECATION")
            phoneStateListener = object : PhoneStateListener() {
                override fun onDataConnectionStateChanged(state: Int, networkType: Int) { updateData(cmgr) }
                override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength?) { updateData(cmgr) }
            }
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .listen(phoneStateListener!!, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        } catch (_: SecurityException) {
            phoneStateListener = null
        }

        // All receiver registrations are done (main thread). Now read initial state
        // values on IO thread so IPC calls to WifiManager, BluetoothAdapter, etc.
        // don't block the UI. MutableStateFlow.value is thread-safe.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            updateTime()
            updateWifi()
            updateBluetooth()
            updateVolume()
            updateDnd()
            updateRotate()
            updateLocation()
            updateAirplane()
            updateHotspot()
            updateBattery()
            updateData(cmgr)
        }
    }

    fun detach() {
        runCatching { ctx.unregisterReceiver(timeReceiver) }
        runCatching { ctx.unregisterReceiver(batteryReceiver) }
        runCatching { ctx.unregisterReceiver(wifiReceiver) }
        runCatching { ctx.unregisterReceiver(btReceiver) }
        runCatching { ctx.unregisterReceiver(volumeReceiver) }
        runCatching { ctx.unregisterReceiver(airplaneReceiver) }
        runCatching { ctx.unregisterReceiver(hotspotReceiver) }
        runCatching { ctx.unregisterReceiver(locationReceiver) }
        runCatching { ctx.unregisterReceiver(dndReceiver) }
        ctx.contentResolver.unregisterContentObserver(rotateObserver)
        val cmgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiCallback?.let { runCatching { cmgr.unregisterNetworkCallback(it) } }
        dataCallback?.let { runCatching { cmgr.unregisterNetworkCallback(it) } }
        wifiCallback = null
        dataCallback = null
        @Suppress("DEPRECATION")
        phoneStateListener?.let {
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).listen(it, PhoneStateListener.LISTEN_NONE)
        }
        phoneStateListener = null
    }

    // ── Update helpers ───────────────────────────────────────────────────────

    private fun updateTime() {
        _time.value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun updateWifi() {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cmgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val enabled = wm.isWifiEnabled
        val netCaps = cmgr.getNetworkCapabilities(cmgr.activeNetwork)
        val connected = netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val info = if (connected) wm.connectionInfo else null
        // Strip surrounding quotes exactly as old app does
        val ssid = info?.ssid?.replaceFirst(Regex("^\"(.*)\"$"), "$1")
            ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val freqMhz = info?.frequency ?: 0
        val freq = when {
            freqMhz in 2400..2500 -> "2.4G"
            freqMhz in 5150..5900 -> "5G"
            freqMhz > 5900        -> "6G"
            else                  -> null
        }
        // Signal level 0-4 from RSSI (same thresholds as old app)
        val rssi = info?.rssi ?: -100
        val signalLevel = when {
            rssi >= -50 -> 4
            rssi >= -60 -> 3
            rssi >= -70 -> 2
            rssi >= -80 -> 1
            else        -> 0
        }
        _wifi.value = TileState.WifiState(enabled, connected, ssid, freq, if (connected) signalLevel else 0)
    }

    @Suppress("DEPRECATION")
    private fun updateBluetooth() {
        try {
            val bt = BluetoothAdapter.getDefaultAdapter()
            val enabled = bt?.isEnabled == true
            // Mirror old app's getAclConnectedDevices — find first bonded device where isConnected() is true
            val firstName = if (enabled && bt != null) {
                bt.bondedDevices?.firstOrNull { device ->
                    runCatching {
                        device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
                    }.getOrDefault(false)
                }?.name
            } else null
            _bluetooth.value = TileState.BtState(enabled, firstName)
        } catch (_: SecurityException) {
            _bluetooth.value = TileState.BtState(false, null)
        }
    }

    private fun updateVolume() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = am.isStreamMute(AudioManager.STREAM_MUSIC) || cur == 0
        _volume.value = TileState.VolumeState(cur, max, muted, am.ringerMode)
    }

    private fun updateDnd() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val filterActive =
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        _dnd.value = TileState.DndState(filterActive, am.ringerMode)
    }

    private fun updateRotate() {
        val locked = Settings.System.getInt(ctx.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION, 1) == 0
        _rotate.value = TileState.RotateState(locked)
    }

    private fun updateLocation() {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        }
        _location.value = TileState.LocationState(enabled)
    }

    private fun updateAirplane() {
        val on = Settings.Global.getInt(ctx.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        _airplane.value = TileState.AirplaneState(on)
    }

    @Suppress("DEPRECATION")
    private fun updateHotspot() {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val state = try {
            val method = wm.javaClass.getMethod("getWifiApState")
            method.invoke(wm) as? Int ?: 0
        } catch (_: Exception) { 0 }
        _hotspot.value = TileState.HotspotState(state == 13 || state == 12)
    }

    private fun updateBattery() {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                BatteryManager.BATTERY_STATUS_CHARGING
        _battery.value = TileState.BatteryState((level * 100f / scale).toInt(), charging)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun updateData(cmgr: ConnectivityManager) {
        val caps = cmgr.getNetworkCapabilities(cmgr.activeNetwork)
        // activelyRouting = cellular IS the current active network
        val activelyRouting = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        // enabled = data toggle is on in settings (regardless of active route)
        val enabled = activelyRouting || try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.isDataEnabled == true
            } else {
                Settings.Global.getInt(ctx.contentResolver, "mobile_data", 0) != 0
            }
        } catch (_: Exception) { activelyRouting }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        // All telephony calls require READ_PHONE_STATE — catch SecurityException gracefully
        val networkType = try { getNetworkTypeName(tm?.networkType ?: 0) }
                          catch (_: SecurityException) { null }
        val operatorName = try { tm?.networkOperatorName?.takeIf { it.isNotEmpty() } }
                           catch (_: SecurityException) { null }

        // Extract signal from CellInfo exactly as old app does
        var dbm = -1
        try {
            tm?.allCellInfo?.forEach { cell ->
                when (cell) {
                    is CellInfoLte -> {
                        val d = cell.cellSignalStrength.dbm
                        if (d != Int.MAX_VALUE) dbm = d
                    }
                    is CellInfoWcdma -> {
                        val d = cell.cellSignalStrength.dbm
                        if (d != Int.MAX_VALUE) dbm = d
                    }
                }
            }
        } catch (_: Exception) {}

        val signalLevel = when {
            dbm == -1  -> 0
            dbm >= -70 -> 4
            dbm >= -85 -> 3
            dbm >= -100-> 2
            dbm >= -110-> 1
            else       -> 0
        }
        _data.value = TileState.DataState(enabled, activelyRouting, if (activelyRouting) networkType else null, signalLevel, operatorName)
    }

    private fun getNetworkTypeName(type: Int) = when (type) {
        1, 2, 4, 7    -> "2G"
        3, 8, 10, 15  -> "3G"
        13            -> "4G"
        20            -> "5G"
        else          -> "?"
    }
}
