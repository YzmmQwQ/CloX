-- CloX Watchface — classic watch face on page 1, debug data pages 2–9.
-- All sizes auto-scale to screen width (reference: 480 px).

require "bridge"
require "watchface_bridge"

local WatchfaceScene = class("WatchfaceScene", cc.Scene)

function WatchfaceScene:ctor()
    cc.Scene.ctor(self)
    self._page = 1
end

-- ── Page registry ─────────────────────────────────────────────────────────────

local PAGE_NAMES = { "Watch", "Core", "Device", "Network", "Health", "Sensors", "Weather", "Calendar", "Perf", "WFParser" }
local NUM_PAGES  = #PAGE_NAMES

-- ── Sensor enable state ───────────────────────────────────────────────────────

local enabled = {
    steps = false, heartRate = false, spo2 = false,
    accelerometer = false, compass = false, barometer = false,
    weather = false,
}

-- ── Helpers ───────────────────────────────────────────────────────────────────

local function fmtBytes(b)
    b = b or 0
    if     b >= 1073741824 then return string.format("%.1fG", b/1073741824)
    elseif b >= 1048576    then return string.format("%.0fM", b/1048576)
    elseif b >= 1024       then return string.format("%.0fK", b/1024)
    else                        return b.."B" end
end
local function B(v) return v and "Y" or "N" end
local function S(s) return (s ~= nil and s ~= "") and tostring(s) or "-" end
local function trunc(s, n) s = tostring(s); return #s > n and ("…"..s:sub(-n+1)) or s end
local function iN(v) return math.floor(tonumber(v) or 0) end

-- ── Data page builders (pages 2–8, index shifted +1 from old pg1–pg7) ─────────

local function pg2()   -- Core (was pg1)
    local t   = os.date("*t")
    local wfp = WF.settings.watchfacePath
    if wfp == false then wfp = "(not set)" end
    return {
        string.format("%02d:%02d:%02d  %s %02d %s", t.hour, t.min, t.sec, os.date("%a"), t.day, os.date("%b")),
        "WF: "..trunc(tostring(wfp), 36),
        string.format("BAT %d%%  chg:%s  %.1fC", WF.battery.level, B(WF.battery.charging), WF.battery.temperature),
        string.format("BT:%s  GPS:%s  dev:%s", B(WF.connectivity.bluetoothEnabled), B(WF.connectivity.gpsEnabled), S(WF.connectivity.bluetoothDevice)),
        string.format("NOTIF: %d", WF.notification.count),
        (WF.notification.last.appName ~= "") and ("  "..S(WF.notification.last.appName)..": "..trunc(S(WF.notification.last.title),24)) or "  (none)",
        "ALARM: "..(WF.alarm.hasAlarm and WF.alarm.alarmTime or "none"),
    }
end

local function pg3()   -- Device (was pg2)
    local d, s, st = WF.device, WF.settings, WF.storage
    local wfp = s.watchfacePath; if wfp == false then wfp = "(not set)" end
    return {
        "Model: "..S(d.deviceModel).."  "..S(d.manufacturer),
        "API:"..tostring(d.apiLevel).."  Lang:"..S(d.language).."  "..S(d.osType),
        "App: "..S(d.appVersion),
        "WF: "..trunc(tostring(wfp), 36),
        string.format("Zoom:%.2f  Offs:%d,%d", s.zoom, s.xOffset, s.yOffset),
        string.format("FPS:%d  FS:%s  AOD:%s", s.frameRate, tostring(s.fullscreen), B(s.aodEnabled)),
        string.format("ROM %s / %s", fmtBytes(st.usedStorage), fmtBytes(st.totalStorage)),
        string.format("RAM %s / %s", fmtBytes(st.usedRam), fmtBytes(st.totalRam)),
    }
end

local function pg4()   -- Network (was pg3)
    local n   = WF.network
    local loc = WF.location
    local lines = {
        string.format("WIFI En:%s Conn:%s", B(n.wifiEnabled), B(n.wifiConnected)),
        n.wifiConnected and ("  SSID:"..trunc(S(n.wifiSsid),22).." Sig:"..n.wifiSignal.."/4") or "  disconnected",
        n.wifiConnected and ("  IP:"..S(n.wifiIp)) or "",
        string.format("DATA En:%s Conn:%s %s Sig:%d", B(n.dataEnabled), B(n.dataConnected), S(n.dataType), n.dataSignal),
        n.dataConnected and ("  Op:"..S(n.dataOperator)) or "",
        "",
    }
    for i = 1, 3 do
        local nm = loc.names[i] or ""
        local tz = loc.timezones[i] or ""
        if nm ~= "" then table.insert(lines, string.format("L%d: %s  %s", i, nm, tz)) end
    end
    return lines
end

local function pg5()   -- Health (was pg4)
    local hs = WF.health_steps; local hr = WF.health_heartRate; local sp = WF.health_spo2
    local hist = {}
    for i = 10, 6, -1 do
        if hr.history[i] and hr.history[i].bpm > 0 then
            table.insert(hist, tostring(hr.history[i].bpm))
        end
    end
    return {
        "── STEPS ──",
        string.format("Steps: %d", iN(hs.steps)),
        string.format("Cal: %.1f kcal  Dist: %.2f km", hs.calories or 0, hs.distance or 0),
        "── HEART RATE ──",
        string.format("BPM: %d", iN(hr.bpm)),
        "Last5: "..(#hist > 0 and table.concat(hist, " ") or "-"),
        "── SPO2 ──",
        string.format("SpO2: %d%%", iN(sp.percent)),
    }
end

local function pg6()   -- Sensors (was pg5)
    local a = WF.sensor_accelerometer; local c = WF.sensor_compass; local b = WF.sensor_barometer
    return {
        "── ACCELEROMETER ──",
        string.format("X: %.3f  Y: %.3f", a.x or 0, a.y or 0),
        string.format("Z: %.3f  m/s²", a.z or 0),
        "── COMPASS ──",
        string.format("Azimuth: %.1f°", c.azimuth or 0),
        string.format("Pitch: %.1f°  Roll: %.1f°", c.pitch or 0, c.roll or 0),
        "── BAROMETER ──",
        string.format("Pressure: %.2f hPa", b.pressure or 0),
    }
end

local function pg7()   -- Weather (was pg6)
    local w = WF.weather
    local upd = (w.lastUpdate and w.lastUpdate > 0)
        and os.date("%H:%M", math.floor(w.lastUpdate/1000)) or "-"
    local hourlyStr = "Hourly: -"
    if w.hourly and w.hourly[1] then
        hourlyStr = string.format("H: %d  %d  %d C",
            iN(w.hourly[1].temp), iN(w.hourly[2] and w.hourly[2].temp or 0),
            iN(w.hourly[3] and w.hourly[3].temp or 0))
    end
    local dailyStr = "Daily: -"
    if w.daily and w.daily[1] then
        dailyStr = string.format("D: %d  %d  %d C",
            iN(w.daily[1].avgTemp), iN(w.daily[2] and w.daily[2].avgTemp or 0),
            iN(w.daily[3] and w.daily[3].avgTemp or 0))
    end
    return {
        string.format("Temp: %dC  (%d~%d)", iN(w.currentTemp), iN(w.lowTemp), iN(w.highTemp)),
        "Cond: "..S(w.conditionText),
        string.format("Hum:%d%%  P:%.0f hPa", iN(w.humidity), w.pressure or 0),
        string.format("Wind:%d km/h  Cloud:%d%%", iN(w.windSpeed), iN(w.cloudiness)),
        string.format("Day:%s  Moon:%.2f  Upd:%s", B(w.isDaytime), w.moonPhase or 0, upd),
        hourlyStr,
        dailyStr,
    }
end

local function pg8()   -- Calendar (was pg7)
    local lines = { "Events: "..B(WF.calendar.hasEvents) }
    local shown = 0
    for _, ev in ipairs(WF.calendar.events or {}) do
        if ev.exists and shown < 3 then
            shown = shown + 1
            table.insert(lines, S(ev.title))
            table.insert(lines, "  "..S(ev.beginDate).." ~ "..S(ev.endDate))
        end
    end
    if shown == 0 then table.insert(lines, "  (no events)") end
    table.insert(lines, "3rd Party:")
    local any = false
    for i = 1, 10 do
        local sl = WF.thirdParty.slots[i]
        if sl and sl.data ~= "" then
            any = true
            table.insert(lines, string.format("S%d[%s]: %s", i, S(sl.key), trunc(sl.data, 28)))
        end
    end
    if not any then table.insert(lines, "  (no data)") end
    return lines
end

local function pg9() return { "" } end  -- Perf: visuals handled by animation layer

-- ── Watchface extraction state (page 10) ─────────────────────────────────────

local wfSourcePath      = nil   -- last extracted .watch file path (dedup guard)
local wfExtractDir      = nil   -- writable path where .watch was extracted
local wfXml             = nil   -- raw watch.xml content string
local wfFirstImagePath  = nil   -- absolute path to first image layer asset

local function extractWatchface(watchPath)
    if not watchPath or watchPath == false or watchPath == "" then return end
    if watchPath == wfSourcePath then return end  -- already extracted this file
    wfSourcePath = watchPath
    local writableDir = cc.FileUtils:getInstance():getWritablePath()
    local destDir = writableDir .. "wf_preview/"
    axRemoveDir(destDir)
    local ok = axUnzipToDir(watchPath, destDir)
    if not ok then
        print("[WFParser] axUnzipToDir failed for: " .. tostring(watchPath))
        return
    end
    wfExtractDir = destDir
    wfXml = cc.FileUtils:getInstance():getStringFromFile(destDir .. "watch.xml")
    -- find path of first image-type layer (bottom-most)
    wfFirstImagePath = nil
    if wfXml and #wfXml > 0 then
        -- match type="image" ... path="..." or path="..." ... type="image" in any order
        local p = wfXml:match('<Layer[^>]+type%s*=%s*"image"[^>]+path%s*=%s*"([^"]+)"')
               or wfXml:match('<Layer[^>]+path%s*=%s*"([^"]+)"[^>]+type%s*=%s*"image"')
        if p then
            wfFirstImagePath = destDir .. "images/" .. p
        end
    end
    print(string.format("[WFParser] extracted to %s  xml=%d bytes  firstImg=%s",
        destDir, wfXml and #wfXml or 0, tostring(wfFirstImagePath)))
end

local function pg10()
    if not wfXml then return { "(no watchface loaded yet)", "Navigate to page 1 and set a watchface" } end
    local lines = {}
    -- show watch.xml attribute summary from <Watch ...> tag
    local attrs = wfXml:match("<Watch(.-)>") or ""
    for k, v in attrs:gmatch('%s(%w+)%s*=%s*"([^"]*)"') do
        table.insert(lines, k .. ": " .. v)
    end
    local layerCount = 0
    for _ in wfXml:gmatch("<Layer") do layerCount = layerCount + 1 end
    table.insert(lines, "")
    table.insert(lines, "Layers: " .. layerCount)
    table.insert(lines, "FirstImg: " .. (wfFirstImagePath and wfFirstImagePath:match("[^/]+$") or "none"))
    return lines
end

-- page 1 has no data builder; it renders its own dedicated nodes
local pageBuilders = { nil, pg2, pg3, pg4, pg5, pg6, pg7, pg8, pg9, pg10 }

-- ── HSV → RGB ─────────────────────────────────────────────────────────────────

local function hsv(h, s, v)
    h = h % 1
    local i = math.floor(h * 6)
    local f = h * 6 - i
    local p = v * (1 - s)
    local q = v * (1 - f * s)
    local t = v * (1 - (1 - f) * s)
    local r, g, b
    local m = i % 6
    if     m == 0 then r,g,b = v,t,p
    elseif m == 1 then r,g,b = q,v,p
    elseif m == 2 then r,g,b = p,v,t
    elseif m == 3 then r,g,b = p,q,v
    elseif m == 4 then r,g,b = t,p,v
    else               r,g,b = v,p,q end
    return cc.c4f(r, g, b, 1)
end

-- ── Toggle specs (pages 5/6/7 = Health/Sensors/Weather) ──────────────────────

local pageToggles = {
    [5] = {
        { label="Steps",   key="steps",     apply=function(v) WatchfaceBridge.setHealthEnabled("steps",v) end },
        { label="Heart",   key="heartRate", apply=function(v) WatchfaceBridge.setHealthEnabled("heartRate",v) end },
        { label="SpO2",    key="spo2",      apply=function(v) WatchfaceBridge.setHealthEnabled("spo2",v) end },
    },
    [6] = {
        { label="Accel",   key="accelerometer", apply=function(v) WatchfaceBridge.setSensorEnabled("accelerometer",v) end },
        { label="Compass", key="compass",       apply=function(v) WatchfaceBridge.setSensorEnabled("compass",v) end },
        { label="Baro",    key="barometer",     apply=function(v) WatchfaceBridge.setSensorEnabled("barometer",v) end },
    },
    [7] = {
        { label="Weather", key="weather", apply=function(v) WatchfaceBridge.setWeatherEnabled(v) end },
    },
}

-- ── Scene lifecycle ───────────────────────────────────────────────────────────

function WatchfaceScene:onEnter()
    local director = cc.Director:getInstance()
    local sz       = director:getWinSize()
    local W, H     = sz.width, sz.height
    local cx, cy   = W/2, H/2

    self._W, self._H   = W, H
    self._cx, self._cy = cx, cy

    -- Auto-scale factor relative to 480 px reference width
    local sc = W / 480.0

    self._sc         = sc
    self._FONT_CLOCK = math.max(20, math.floor(52 * sc))
    self._FONT_DATE  = math.max(10, math.floor(18 * sc))
    self._FONT_BODY  = math.max(16, math.floor(40 * sc))
    self._FONT_TITLE = math.max(12, math.floor(22 * sc))
    self._FONT_BTN   = math.max(16, math.floor(40 * sc))
    self._FONT_TOG   = math.max(12, math.floor(32 * sc))
    self._LINE_H     = math.max(12, math.floor(48 * sc))
    self._SQ_HALF    = math.max(8,  math.floor(16 * sc))

    self:addChild(cc.LayerColor:create(cc.c4b(0,0,0,255)), -1)

    -- ── Page 1: classic watchface nodes ──────────────────────────────────────
    self._watchNode = cc.Node:create()
    self:addChild(self._watchNode, 2)

    self._clockLabel = cc.Label:createWithSystemFont("00:00:00", "sans-serif", self._FONT_CLOCK)
    self._clockLabel:setPosition(cx, cy + math.floor(20 * sc))
    self._clockLabel:setTextColor(cc.c4b(255, 255, 255, 255))
    self._watchNode:addChild(self._clockLabel)

    self._dateLabel = cc.Label:createWithSystemFont("", "sans-serif", self._FONT_DATE)
    self._dateLabel:setPosition(cx, cy - math.floor(20 * sc))
    self._dateLabel:setTextColor(cc.c4b(180, 180, 180, 255))
    self._watchNode:addChild(self._dateLabel)

    local sqH = self._SQ_HALF
    local square = cc.DrawNode:create()
    square:drawSolidRect(cc.p(-sqH, -sqH), cc.p(sqH, sqH), cc.c4f(0.12, 0.53, 0.9, 0.85))
    square:setPosition(cx, cy - math.floor(80 * sc))
    square:runAction(cc.RepeatForever:create(cc.RotateBy:create(3.0, 360)))
    self._watchNode:addChild(square)

    self._touchFeedback = cc.Label:createWithSystemFont("", "sans-serif", math.floor(20 * sc))
    self._touchFeedback:setPosition(cx, cy - math.floor(150 * sc))
    self._touchFeedback:setTextColor(cc.c4b(80, 220, 80, 255))
    self._watchNode:addChild(self._touchFeedback, 1)

    local tf = self._touchFeedback
    local touchBtn = ccui.Button:create()
    touchBtn:setTitleText("Touch Test")
    touchBtn:setTitleFontSize(math.floor(18 * sc))
    touchBtn:setTitleColor(cc.c3b(255, 255, 255))
    touchBtn:setPosition(cx, cy - math.floor(200 * sc))
    touchBtn:addTouchEventListener(function(_, eventType)
        if eventType == ccui.TouchEventType.ended then
            tf:setString("Touch works!")
            tf:stopAllActions()
            tf:runAction(cc.Sequence:create(
                cc.DelayTime:create(1.5),
                cc.FadeOut:create(0.4),
                cc.CallFunc:create(function()
                    tf:setString("")
                    tf:setOpacity(255)
                end)
            ))
        end
    end)
    self._watchNode:addChild(touchBtn, 1)

    -- ── Data pages: shared UI nodes ───────────────────────────────────────────
    self._titleLabel = cc.Label:createWithSystemFont("", "sans-serif", self._FONT_TITLE)
    self._titleLabel:setTextColor(cc.c4b(60, 180, 255, 255))
    self:addChild(self._titleLabel, 2)

    self._contentLabel = cc.Label:createWithSystemFont("", "monospace", self._FONT_BODY)
    self._contentLabel:setTextColor(cc.c4b(220, 220, 220, 255))
    self:addChild(self._contentLabel, 2)

    self._prevBtn = ccui.Button:create()
    self._prevBtn:setTitleText("< Prev")
    self._prevBtn:setTitleFontSize(self._FONT_BTN)
    self._prevBtn:addTouchEventListener(function(_, ev)
        if ev == ccui.TouchEventType.ended then self:changePage(-1) end
    end)
    self:addChild(self._prevBtn, 3)

    self._nextBtn = ccui.Button:create()
    self._nextBtn:setTitleText("Next >")
    self._nextBtn:setTitleFontSize(self._FONT_BTN)
    self._nextBtn:addTouchEventListener(function(_, ev)
        if ev == ccui.TouchEventType.ended then self:changePage(1) end
    end)
    self:addChild(self._nextBtn, 3)

    self._pageInd = cc.Label:createWithSystemFont("", "sans-serif", self._FONT_TITLE)
    self._pageInd:setTextColor(cc.c4b(120, 120, 120, 255))
    self:addChild(self._pageInd, 2)

    self._toggleNode = cc.Node:create()
    self:addChild(self._toggleNode, 3)

    -- ── Page 9: animation layer ───────────────────────────────────────────────
    self._animNode = cc.DrawNode:create()
    self:addChild(self._animNode, 4)
    self._animNode:setVisible(false)

    self._balls = {}
    math.randomseed(os.time())
    local NUM_BALLS = 25
    local ballR_min = math.max(8,  math.floor(18 * sc))
    local ballR_max = math.max(12, math.floor(45 * sc))
    for i = 1, NUM_BALLS do
        table.insert(self._balls, {
            x   = math.random(50, W - 50),
            y   = math.random(50, H - 50),
            vx  = (math.random() - 0.5) * 500 * sc,
            vy  = (math.random() - 0.5) * 500 * sc,
            r   = math.random(ballR_min, ballR_max),
            hue = i / NUM_BALLS,
        })
    end
    self._spinAngle  = 0
    self._animTime   = 0
    self._animTimer  = nil
    self._fpsCounter = 0
    self._fpsAccum   = 0
    self._fps        = 0

    self._fpsLabel = cc.Label:createWithSystemFont("FPS: --", "monospace", math.floor(36 * sc))
    self._fpsLabel:setTextColor(cc.c4b(255, 255, 0, 255))
    self._fpsLabel:setVisible(false)
    self:addChild(self._fpsLabel, 5)

    -- ── Page 10: WFParser — image layer + XML scrim ───────────────────────────
    self._wfParserNode = cc.Node:create()
    self:addChild(self._wfParserNode, 1)
    self._wfParserNode:setVisible(false)

    -- Extract on settings change (watchfacePath delivered via settings category)
    WatchfaceBridge.onChange("settings", function(data)
        local path = data and data.watchfacePath
        if path and path ~= "" then
            extractWatchface(path)
            if self._page == 10 then self:renderPage() end
        end
    end)

    -- Also extract immediately if watchface already set before scene loaded
    local initPath = WF.settings and WF.settings.watchfacePath
    if initPath and initPath ~= "" then
        extractWatchface(initPath)
    end

    self:renderPage()

    Bridge.sendToAndroid("wf_lua_ready", "")

    self._clockTimer = director:getScheduler():scheduleScriptFunc(function()
        self:renderPage()
    end, 1.0, false)

    Bridge.onEvent("battery", function(payload) end)
    Bridge.onEvent("steps",   function(payload) end)
end

function WatchfaceScene:onExit()
    local sched = cc.Director:getInstance():getScheduler()
    if self._clockTimer then
        sched:unscheduleScriptEntry(self._clockTimer)
        self._clockTimer = nil
    end
    self:stopAnim()
end

-- ── Perf animation ────────────────────────────────────────────────────────────

function WatchfaceScene:startAnim()
    if self._animTimer then return end
    self._animNode:setVisible(true)
    self._fpsLabel:setVisible(true)
    self._fpsCounter = 0
    self._fpsAccum   = 0
    local sched = cc.Director:getInstance():getScheduler()
    self._animTimer = sched:scheduleScriptFunc(function()
        local dt = cc.Director:getInstance():getDeltaTime()
        self:stepAnim(dt)
    end, 0, false)
end

function WatchfaceScene:stopAnim()
    if not self._animTimer then return end
    cc.Director:getInstance():getScheduler():unscheduleScriptEntry(self._animTimer)
    self._animTimer = nil
    if self._animNode  then self._animNode:setVisible(false)  end
    if self._fpsLabel  then self._fpsLabel:setVisible(false)  end
end

function WatchfaceScene:stepAnim(dt)
    local W, H  = self._W, self._H
    local sc    = self._sc
    local dn    = self._animNode
    self._animTime  = self._animTime + dt
    self._spinAngle = self._spinAngle + dt * 120
    local t = self._animTime

    self._fpsCounter = self._fpsCounter + 1
    self._fpsAccum   = self._fpsAccum   + dt
    if self._fpsAccum >= 0.5 then
        self._fps        = math.floor(self._fpsCounter / self._fpsAccum + 0.5)
        self._fpsCounter = 0
        self._fpsAccum   = 0
        self._fpsLabel:setString(string.format("FPS: %d", self._fps))
        self._fpsLabel:setPosition(W / 2, H - math.floor(60 * sc))
    end

    dn:clear()

    for _, b in ipairs(self._balls) do
        b.x = b.x + b.vx * dt
        b.y = b.y + b.vy * dt
        if b.x - b.r < 0 then b.x =  b.r;     b.vx =  math.abs(b.vx) end
        if b.x + b.r > W then b.x = W - b.r;  b.vx = -math.abs(b.vx) end
        if b.y - b.r < 0 then b.y =  b.r;     b.vy =  math.abs(b.vy) end
        if b.y + b.r > H then b.y = H - b.r;  b.vy = -math.abs(b.vy) end
        local col = hsv((b.hue + t * 0.15) % 1, 0.85, 1.0)
        dn:drawSolidCircle(cc.p(b.x, b.y), b.r, 0, 20, 1, 1, col)
    end

    local cx, cy = W / 2, H / 2
    local RAD = math.rad
    local spokes = 7
    local outer  = math.floor(120 * sc)
    local inner  = math.floor(50  * sc)
    for i = 0, spokes - 1 do
        local a1 = RAD(self._spinAngle + i * 360 / spokes)
        local a2 = RAD(self._spinAngle + i * 360 / spokes + 360 / spokes / 2)
        local x1, y1 = cx + math.cos(a1) * outer, cy + math.sin(a1) * outer
        local x2, y2 = cx + math.cos(a2) * inner, cy + math.sin(a2) * inner
        local x3, y3 = cx + math.cos(a1 + RAD(360/spokes)) * outer,
                        cy + math.sin(a1 + RAD(360/spokes)) * outer
        local col = hsv((i / spokes + t * 0.3) % 1, 1, 1)
        dn:drawSolidTriangle(cc.p(cx, cy), cc.p(x1, y1), cc.p(x2, y2), col)
        dn:drawSolidTriangle(cc.p(cx, cy), cc.p(x2, y2), cc.p(x3, y3), col)
    end

    for ring = 0, 2 do
        local phase  = (t * 0.6 + ring / 3) % 1
        local radius = phase * math.min(W, H) * 0.55
        local alpha  = (1 - phase) * 0.7
        dn:drawCircle(cc.p(cx, cy), radius, 0, 40, false, 1, 1, cc.c4f(1, 1, 1, alpha))
    end
end

-- ── Page navigation ───────────────────────────────────────────────────────────

function WatchfaceScene:changePage(delta)
    self._page = ((self._page - 1 + delta) % NUM_PAGES) + 1
    if self._page == 9 then self:startAnim() else self:stopAnim() end
    self:renderPage()
end

function WatchfaceScene:buildWfParserPage()
    local node = self._wfParserNode
    node:removeAllChildren()
    local W, H   = self._W, self._H
    local cx, cy = self._cx, self._cy
    local sc     = self._sc

    -- Bottom layer: first image from watchface (if available)
    if wfFirstImagePath and cc.FileUtils:getInstance():isFileExist(wfFirstImagePath) then
        local sprite = cc.Sprite:create(wfFirstImagePath)
        if sprite then
            local iW = sprite:getContentSize().width
            local iH = sprite:getContentSize().height
            if iW > 0 and iH > 0 then
                local scale = math.min(W / iW, H / iH)
                sprite:setScale(scale)
            end
            sprite:setPosition(cx, cy)
            node:addChild(sprite, 0)
        end
    end

    -- Scrim: dark semi-transparent overlay to make XML readable
    local scrim = cc.LayerColor:create(cc.c4b(0, 0, 0, 180), W, H)
    scrim:setPosition(0, 0)
    node:addChild(scrim, 1)

    -- XML content: full watch.xml text
    local xmlText = wfXml or "(no watch.xml — set a watchface first)"
    -- truncate for label; very long XML would overflow label buffer
    if #xmlText > 4000 then xmlText = xmlText:sub(1, 4000) .. "\n…" end
    local fontSize = math.max(8, math.floor(14 * sc))
    local xmlLabel = cc.Label:createWithSystemFont(xmlText, "monospace", fontSize)
    xmlLabel:setTextColor(cc.c4b(160, 230, 160, 255))
    xmlLabel:setAnchorPoint(cc.p(0.5, 1.0))
    -- leave room for title (top) and nav (bottom)
    local topY  = H - math.floor(50 * sc)
    xmlLabel:setPosition(cx, topY)
    xmlLabel:setMaxLineWidth(W - math.floor(20 * sc))
    node:addChild(xmlLabel, 2)
end

-- ── Render ────────────────────────────────────────────────────────────────────

function WatchfaceScene:renderPage()
    local p  = self._page
    local cx = self._cx
    local cy = self._cy
    local W  = self._W

    local FONT_TITLE = self._FONT_TITLE
    local FONT_BTN   = self._FONT_BTN
    local FONT_TOG   = self._FONT_TOG
    local LINE_H     = self._LINE_H
    local sc         = self._sc

    -- ── Page 1: classic watchface ─────────────────────────────────────────────
    if p == 1 then
        self._wfParserNode:setVisible(false)
        self._watchNode:setVisible(true)
        self._titleLabel:setVisible(false)
        self._contentLabel:setVisible(false)
        self._prevBtn:setVisible(false)
        self._nextBtn:setVisible(false)
        self._pageInd:setVisible(false)
        self._toggleNode:removeAllChildren()

        local t = os.date("*t")
        self._clockLabel:setString(string.format("%02d:%02d:%02d", t.hour, t.min, t.sec))
        self._dateLabel:setString(os.date("%a, %d %b %Y"))

        -- Nav: small page indicator and arrows at bottom
        local navY  = math.floor(40 * sc)
        local spread = math.min(cx * 0.55, math.floor(200 * sc))
        self._prevBtn:setVisible(true)
        self._nextBtn:setVisible(true)
        self._pageInd:setVisible(true)
        self._prevBtn:setPosition(cx - spread, navY)
        self._nextBtn:setPosition(cx + spread, navY)
        self._pageInd:setString("1/"..NUM_PAGES)
        self._pageInd:setPosition(cx, navY)
        return
    end

    -- ── Page 10: WFParser ────────────────────────────────────────────────────
    if p == 10 then
        self._watchNode:setVisible(false)
        self._wfParserNode:setVisible(true)
        self._titleLabel:setVisible(true)
        self._contentLabel:setVisible(false)
        self._prevBtn:setVisible(true)
        self._nextBtn:setVisible(true)
        self._pageInd:setVisible(true)
        self._titleLabel:setString(string.format("[10/%d]  WFParser", NUM_PAGES))
        self._pageInd:setString("10/"..NUM_PAGES)
        local navY   = math.floor(40 * self._sc)
        local spread = math.min(self._cx * 0.55, math.floor(200 * self._sc))
        self._prevBtn:setPosition(self._cx - spread, navY)
        self._nextBtn:setPosition(self._cx + spread, navY)
        self._pageInd:setPosition(self._cx, navY)
        self._titleLabel:setPosition(self._cx, self._H - math.floor(30 * self._sc))
        self._toggleNode:removeAllChildren()
        self:buildWfParserPage()
        return
    end

    -- ── Pages 2–9: data inspector ─────────────────────────────────────────────
    self._wfParserNode:setVisible(false)
    self._watchNode:setVisible(false)
    self._titleLabel:setVisible(true)
    self._contentLabel:setVisible(true)
    self._prevBtn:setVisible(true)
    self._nextBtn:setVisible(true)
    self._pageInd:setVisible(true)

    local isPerf = (p == 9)
    self._contentLabel:setVisible(not isPerf)
    self._titleLabel:setString(string.format("[%d/%d]  %s", p, NUM_PAGES, PAGE_NAMES[p]))
    self._pageInd:setString(p.."/"..NUM_PAGES)

    local lines = pageBuilders[p]()
    while #lines > 0 and lines[#lines] == "" do table.remove(lines) end
    self._contentLabel:setString(table.concat(lines, "\n"))

    local labelH = self._contentLabel:getContentSize().height
    if labelH <= 0 then labelH = #lines * LINE_H end

    local hasToggles = pageToggles[p] ~= nil
    local GAP        = math.floor(20 * sc)
    local TITLE_ROW  = FONT_TITLE * 1.4
    local NAV_ROW    = FONT_BTN   * 1.4
    local TOG_ROW    = FONT_TOG   * 1.4

    local totalH = TITLE_ROW + GAP + labelH + GAP + NAV_ROW
    if hasToggles then totalH = totalH + GAP + TOG_ROW end

    local blockTop = cy + totalH / 2

    self._titleLabel:setPosition(cx, blockTop - TITLE_ROW / 2)
    local cursor = blockTop - TITLE_ROW - GAP

    self._contentLabel:setPosition(cx, cursor - labelH / 2)
    cursor = cursor - labelH - GAP

    local navCY  = cursor - NAV_ROW / 2
    local spread = math.min(cx * 0.55, math.floor(200 * sc))
    self._prevBtn:setPosition(cx - spread, navCY)
    self._nextBtn:setPosition(cx + spread, navCY)
    self._pageInd:setPosition(cx, navCY)
    cursor = cursor - NAV_ROW - GAP

    self._toggleNode:removeAllChildren()
    local specs = pageToggles[p]
    if specs then
        local togCY   = cursor - TOG_ROW / 2
        local count   = #specs
        local spacing = W / (count + 1)
        for i, spec in ipairs(specs) do
            local btn = ccui.Button:create()
            local st  = enabled[spec.key]
            btn:setTitleText(spec.label..": "..(st and "ON" or "OFF"))
            btn:setTitleFontSize(FONT_TOG)
            btn:setTitleColor(st and cc.c3b(60,220,60) or cc.c3b(160,160,160))
            btn:setPosition(spacing * i, togCY)
            local mySpec, myBtn = spec, btn
            btn:addTouchEventListener(function(_, ev)
                if ev == ccui.TouchEventType.ended then
                    enabled[mySpec.key] = not enabled[mySpec.key]
                    local ns = enabled[mySpec.key]
                    mySpec.apply(ns)
                    myBtn:setTitleText(mySpec.label..": "..(ns and "ON" or "OFF"))
                    myBtn:setTitleColor(ns and cc.c3b(60,220,60) or cc.c3b(160,160,160))
                end
            end)
            self._toggleNode:addChild(btn)
        end
    end
end

return WatchfaceScene
