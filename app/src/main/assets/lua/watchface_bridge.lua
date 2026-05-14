-- watchface_bridge.lua
-- Structured data bridge between Android (Java/Kotlin) and the watchface engine (Lua).
--
-- ── DATA ACCESS (per-frame safe, zero overhead) ───────────────────────────────
--   WF.battery.level          -- int 0-100
--   WF.battery.charging       -- bool
--   WF.weather.currentTemp    -- int °C
--   WF.calendar.events[1].title
--   etc.
--
--   IMPORTANT: always read WF.category.field directly. Do NOT cache WF.battery in a
--   local variable — when Java pushes a new snapshot, WF.battery is replaced entirely.
--
-- ── REACTIVE UPDATES ──────────────────────────────────────────────────────────
--   WatchfaceBridge.onChange("battery", function(data) ... end)
--   WatchfaceBridge.offChange("battery", handler)
--
-- ── ENABLE OPTIONAL SOURCES (default: all off) ────────────────────────────────
--   WatchfaceBridge.setWeatherEnabled(true)
--   WatchfaceBridge.setHealthEnabled("steps", true)     -- "steps"|"heartRate"|"spo2"
--   WatchfaceBridge.setSensorEnabled("compass", true)   -- "accelerometer"|"compass"|"barometer"
--
-- ── LUA → ANDROID ACTIONS ────────────────────────────────────────────────────
--   WatchfaceBridge.tapAction(shortcut)  -- trigger a watchface shortcut on Android
--
-- ── ANDROID → LUA COMMANDS (Java calls these, engine responds via onChange) ──
--   WatchfaceOnSetActive(path)   -- Java tells Lua to load a new watchface
--   WatchfaceOnReload()          -- Java tells Lua to reload the current watchface
--   WatchfaceOnReset()           -- Java tells Lua to clear storage and reload
--   Use WatchfaceBridge.onChange("cmd_setActive"|"cmd_reload"|"cmd_reset", fn)
--
-- ── DATA CATEGORIES & FIELDS ─────────────────────────────────────────────────
--   device:       apiLevel, language, deviceLabel, deviceModel, manufacturer,
--                 productName, osType, appVersion
--   settings:     watchfacePath, zoom, xOffset, yOffset, fullscreen, frameRate, aodEnabled
--   location:     names[1..5], timezones[1..4]
--   battery:      level (0-100), charging (bool), temperature (°C float)
--   network:      wifiEnabled, wifiConnected, wifiSsid, wifiFrequency, wifiSignal (0-4), wifiIp,
--                 dataEnabled, dataConnected, dataType ("4G"…), dataSignal (0-4), dataOperator
--   connectivity: bluetoothEnabled, bluetoothDevice, gpsEnabled
--   notification: count, last: {appName, title, content, time (unix ms)}
--   alarm:        hasAlarm (bool), alarmTime ("HH:mm")
--   storage:      totalStorage, usedStorage, totalRam, usedRam (bytes)
--   calendar:     hasEvents (bool), events[1..10] each:
--                   exists, id, title, beginTime, endTime, beginDate, endDate,
--                   allDay, location, color ("RRGGBB"), calendar
--   weather:      currentTemp, highTemp, lowTemp, conditionText, conditionIcon,
--                 humidity, pressure, windSpeed, windDirection, cloudiness, rainVolume,
--                 isDaytime, sunrise, sunset, moonPhase (0-1), lastUpdate (unix ms),
--                 hourly[1..12]: {temp, conditionText, conditionIcon}
--                 daily[1..5]:  {avgTemp, high, low, conditionText, conditionIcon}
--   health_steps:     steps, calories (kcal), distance (km)
--   health_heartRate: bpm, timestamp (unix ms), history[1..10]: {bpm, timestamp} (1=oldest, 10=newest)
--   health_spo2:      percent, timestamp (unix ms)
--   thirdParty: slots[1..10]: {data (String), key (String), timestamp (unix ms)}
--   sensor_accelerometer: x, y, z  (m/s²)
--   sensor_compass:       azimuth, pitch, roll  (degrees)
--   sensor_barometer:     pressure  (hPa)

-- ── Global live-data table ────────────────────────────────────────────────────
-- Pre-initialised with safe defaults so watchface code never reads nil.
-- Java pushes a full snapshot per category; WF[category] is replaced atomically.

WF = {
    device = {
        apiLevel = 0, language = "", deviceLabel = "", deviceModel = "",
        manufacturer = "", productName = "CloX Launcher", osType = "Full Android", appVersion = "",
    },
    settings = {
        watchfacePath = false,  -- false = not yet received; engine must gate loading on this
        zoom = 1.0, xOffset = 0, yOffset = 0,
        fullscreen = 0, frameRate = 60, aodEnabled = false,
    },
    location = { names = { "", "", "", "", "" }, timezones = { "", "", "", "" } },
    battery  = { level = 0, charging = false, temperature = 0.0 },
    network  = {
        wifiEnabled = false, wifiConnected = false, wifiSsid = "", wifiFrequency = "", wifiSignal = 0, wifiIp = "",
        dataEnabled = false, dataConnected = false, dataType = "", dataSignal = 0, dataOperator = "",
    },
    connectivity = { bluetoothEnabled = false, bluetoothDevice = "", gpsEnabled = false },
    notification = { count = 0, last = { appName = "", title = "", content = "", time = 0 } },
    alarm        = { hasAlarm = false, alarmTime = "" },
    storage      = { totalStorage = 0, usedStorage = 0, totalRam = 0, usedRam = 0 },
    calendar     = { hasEvents = false, events = {} },
    weather = {
        currentTemp = 0, highTemp = 0, lowTemp = 0, conditionText = "", conditionIcon = 0,
        humidity = 0, pressure = 0, windSpeed = 0, windDirection = 0, cloudiness = 0, rainVolume = 0,
        isDaytime = true, sunrise = "", sunset = "", moonPhase = 0.0, lastUpdate = 0,
        hourly = {}, daily = {},
    },
    health_steps     = { steps = 0, calories = 0.0, distance = 0.0 },
    health_heartRate = { bpm = 0, timestamp = 0, history = (function()
        local h = {}
        for i = 1, 10 do h[i] = { bpm = 0, timestamp = 0 } end
        return h
    end)() },
    health_spo2      = { percent = 0, timestamp = 0 },
    -- 10 persistent slots written by third-party apps via broadcast API
    -- Access: WF.thirdParty.slots[1].data, .key, .timestamp
    thirdParty = { slots = (function()
        local s = {}
        for i = 1, 10 do s[i] = { data = "", key = "", timestamp = 0 } end
        return s
    end)() },
    sensor_accelerometer = { x = 0.0, y = 0.0, z = 0.0 },
    sensor_compass       = { azimuth = 0.0, pitch = 0.0, roll = 0.0 },
    sensor_barometer     = { pressure = 0.0 },
}

-- ── Internal bridge state ─────────────────────────────────────────────────────

WatchfaceBridge = {}
WatchfaceBridge._handlers = {}

-- ── Transport entry point ─────────────────────────────────────────────────────
-- Called by Kotlin: LuaBridge.callLuaFunction("WatchfaceBridgeDispatch", jsonStr)
-- jsonStr = { "category": "battery", "data": { "level": 85, ... } }

function WatchfaceBridgeDispatch(jsonStr)
    local ok, payload = pcall(json.decode, jsonStr)
    if not ok or type(payload) ~= "table" then
        print("[WatchfaceBridge] decode error: " .. tostring(jsonStr))
        return
    end
    local category = payload.category
    local data     = payload.data
    if not category or type(data) ~= "table" then return end

    WF[category] = data  -- replace snapshot; always read WF.x.y, never cache WF.x

    local handlers = WatchfaceBridge._handlers[category]
    if handlers then
        for _, h in ipairs(handlers) do
            local ok2, err = pcall(h, data)
            if not ok2 then
                print("[WatchfaceBridge] handler error [" .. category .. "]: " .. tostring(err))
            end
        end
    end
end

-- ── Reactive subscriptions ────────────────────────────────────────────────────

function WatchfaceBridge.onChange(category, handler)
    if not WatchfaceBridge._handlers[category] then
        WatchfaceBridge._handlers[category] = {}
    end
    table.insert(WatchfaceBridge._handlers[category], handler)
end

function WatchfaceBridge.offChange(category, handler)
    local list = WatchfaceBridge._handlers[category]
    if not list then return end
    for i = #list, 1, -1 do
        if list[i] == handler then table.remove(list, i) end
    end
end

-- ── Enable / disable optional async data sources ─────────────────────────────

function WatchfaceBridge.setWeatherEnabled(enabled)
    Bridge.sendToAndroid("wf_action", json.encode({ action = "setWeatherEnabled", enabled = enabled }))
end

-- metric: "steps" | "heartRate" | "spo2"
function WatchfaceBridge.setHealthEnabled(metric, enabled)
    Bridge.sendToAndroid("wf_action", json.encode({ action = "setHealthEnabled", metric = metric, enabled = enabled }))
end

-- sensor: "accelerometer" | "compass" | "barometer"
function WatchfaceBridge.setSensorEnabled(sensor, enabled)
    Bridge.sendToAndroid("wf_action", json.encode({ action = "setSensorEnabled", sensor = sensor, enabled = enabled }))
end

-- ── Lua → Android: watchface shortcut ────────────────────────────────────────

function WatchfaceBridge.tapAction(shortcut)
    Bridge.sendToAndroid("wf_action", json.encode({ action = "tapAction", shortcut = shortcut }))
end

-- ── Android → Lua: watchface lifecycle commands ───────────────────────────────
-- Java calls these globals via callLuaFunction on the GL thread.
-- Watchface engines should listen with WatchfaceBridge.onChange("cmd_*", fn).

function WatchfaceOnSetActive(path)
    WF.cmd_setActive = { path = path }
    local h = WatchfaceBridge._handlers["cmd_setActive"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_setActive) end end
end

function WatchfaceOnReload()
    WF.cmd_reload = {}
    local h = WatchfaceBridge._handlers["cmd_reload"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_reload) end end
end

function WatchfaceOnReset()
    WF.cmd_reset = {}
    local h = WatchfaceBridge._handlers["cmd_reset"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_reset) end end
end
