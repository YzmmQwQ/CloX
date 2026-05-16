-- CloX Debug Watchface — data inspector
-- Font 40px. Nav buttons float just below content.

require "bridge"
require "watchface_bridge"

local WatchfaceScene = class("WatchfaceScene", cc.Scene)

function WatchfaceScene:ctor()
    cc.Scene.ctor(self)
    self._page = 1
end

-- ── Layout constants ──────────────────────────────────────────────────────────

local PAGE_NAMES = { "Core", "Device", "Network", "Health", "Sensors", "Weather", "Calendar" }
local NUM_PAGES  = #PAGE_NAMES
local FONT_BODY  = 40
local FONT_TITLE = 22
local FONT_BTN   = 40
local FONT_TOG   = 32
local LINE_H     = 48

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

-- ── Page builders ─────────────────────────────────────────────────────────────

local function pg1()
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

local function pg2()
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

local function pg3()
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

local function pg4()
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

local function pg5()
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

local function pg6()
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

local function pg7()
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

local pageBuilders = { pg1, pg2, pg3, pg4, pg5, pg6, pg7 }

-- ── Toggle specs ──────────────────────────────────────────────────────────────

local pageToggles = {
    [4] = {
        { label="Steps",   key="steps",     apply=function(v) WatchfaceBridge.setHealthEnabled("steps",v) end },
        { label="Heart",   key="heartRate", apply=function(v) WatchfaceBridge.setHealthEnabled("heartRate",v) end },
        { label="SpO2",    key="spo2",      apply=function(v) WatchfaceBridge.setHealthEnabled("spo2",v) end },
    },
    [5] = {
        { label="Accel",   key="accelerometer", apply=function(v) WatchfaceBridge.setSensorEnabled("accelerometer",v) end },
        { label="Compass", key="compass",       apply=function(v) WatchfaceBridge.setSensorEnabled("compass",v) end },
        { label="Baro",    key="barometer",     apply=function(v) WatchfaceBridge.setSensorEnabled("barometer",v) end },
    },
    [6] = {
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

    self:addChild(cc.LayerColor:create(cc.c4b(0,0,0,255)), -1)

    self._titleLabel = cc.Label:createWithSystemFont("", "sans-serif", FONT_TITLE)
    self._titleLabel:setTextColor(cc.c4b(60, 180, 255, 255))
    self:addChild(self._titleLabel, 2)

    self._contentLabel = cc.Label:createWithSystemFont("", "monospace", FONT_BODY)
    self._contentLabel:setTextColor(cc.c4b(220, 220, 220, 255))
    self:addChild(self._contentLabel, 2)

    self._prevBtn = ccui.Button:create()
    self._prevBtn:setTitleText("< Prev")
    self._prevBtn:setTitleFontSize(FONT_BTN)
    self._prevBtn:addTouchEventListener(function(_, ev)
        if ev == ccui.TouchEventType.ended then self:changePage(-1) end
    end)
    self:addChild(self._prevBtn, 3)

    self._nextBtn = ccui.Button:create()
    self._nextBtn:setTitleText("Next >")
    self._nextBtn:setTitleFontSize(FONT_BTN)
    self._nextBtn:addTouchEventListener(function(_, ev)
        if ev == ccui.TouchEventType.ended then self:changePage(1) end
    end)
    self:addChild(self._nextBtn, 3)

    self._pageInd = cc.Label:createWithSystemFont("", "sans-serif", FONT_TITLE)
    self._pageInd:setTextColor(cc.c4b(120, 120, 120, 255))
    self:addChild(self._pageInd, 2)

    self._toggleNode = cc.Node:create()
    self:addChild(self._toggleNode, 3)

    self:renderPage()

    Bridge.sendToAndroid("wf_lua_ready", "")

    self._clockTimer = director:getScheduler():scheduleScriptFunc(function()
        self:renderPage()
    end, 1.0, false)
end

function WatchfaceScene:onExit()
    local sched = cc.Director:getInstance():getScheduler()
    if self._clockTimer then
        sched:unscheduleScriptEntry(self._clockTimer)
        self._clockTimer = nil
    end
end

function WatchfaceScene:changePage(delta)
    self._page = ((self._page - 1 + delta) % NUM_PAGES) + 1
    self:renderPage()
end

function WatchfaceScene:renderPage()
    local p  = self._page
    local cx = self._cx
    local cy = self._cy
    local W  = self._W

    self._titleLabel:setString(string.format("[%d/%d]  %s", p, NUM_PAGES, PAGE_NAMES[p]))
    self._pageInd:setString(p.."/"..NUM_PAGES)

    local lines = pageBuilders[p]()
    while #lines > 0 and lines[#lines] == "" do table.remove(lines) end
    self._contentLabel:setString(table.concat(lines, "\n"))

    local labelH = self._contentLabel:getContentSize().height
    if labelH <= 0 then labelH = #lines * LINE_H end

    local hasToggles = pageToggles[p] ~= nil
    local GAP        = 20
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
    local spread = math.min(cx * 0.55, 200)
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
