-- wf_tags.lua — Watchmaker {tag} resolution + Lua expression evaluation.
--
-- The Watchmaker (.watch) format lets almost any attribute carry dynamic
-- `{tag}` substitutions and Lua expressions, evaluated at render time.
-- See watchmaker-definition/WM_TAGS.md for the full catalogue.
--
-- Pipeline (matches the reference renderer):
--   1. WFTags.build(clock)   → snapshot table of all tag values for this frame
--   2. WFTags.sub(str, ctx)  → replace every {tag} with its value
--   3. WFTags.eval*(...)     → if the result looks like Lua, run it in a sandbox
--
-- All live data is read straight from the global WF table (see
-- watchface_bridge.lua); we never cache WF.<category> because Java replaces
-- each category wholesale when it pushes a new snapshot.

local WFTags = {}

-- ── small helpers ─────────────────────────────────────────────────────────────

local floor, fmt = math.floor, string.format
local function z2(n)  return fmt("%02d", n) end
local function num(v) return tonumber(v) or 0 end
local function boolStr(b) return b and "true" or "false" end

local MONTHS_FULL = { "January","February","March","April","May","June",
                      "July","August","September","October","November","December" }
local MONTHS_3    = { "Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec" }
local DOW_FULL    = { "Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday" }
local DOW_3       = { "Sun","Mon","Tue","Wed","Thu","Fri","Sat" }
local DOW_2       = { "Su","Mo","Tu","We","Th","Fr","Sa" }
local DOW_1       = { "S","M","T","W","T","F","S" }

local BEAR_8  = { "N","NE","E","SE","S","SW","W","NW" }
local BEAR_16 = { "N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW" }
local function bearing(deg, list)
    local n = #list
    local idx = floor((deg % 360) / (360 / n) + 0.5) % n
    return list[idx + 1]
end

-- ── per-frame value snapshot ──────────────────────────────────────────────────
-- clock = { t = os.date("*t"), subsec = 0..1, elapsed = seconds since load }

function WFTags.build(clock)
    local t      = clock.t
    local subsec = clock.subsec or 0
    local elapsed = clock.elapsed or 0
    local epoch  = os.time(t)

    local h23 = t.hour                 -- 0..23
    local h12 = (h23 % 12)             -- 0..11
    if h12 == 0 then h12 = 12 end      -- 1..12 form
    local m, s = t.min, t.sec
    local ms   = floor(subsec * 1000)

    local T = {}

    -- ── Date & Time: components ──
    T.dh    = h12;          T.dhz   = z2(h12)
    T.dh11  = h23 % 12;     T.dh11z = z2(h23 % 12)
    T.dh24  = (h23 == 0) and 24 or h23;  T.dh24z = z2(T.dh24)
    T.dh23  = h23;          T.dh23z = z2(h23);  T.dh23i = h23
    T.dm    = m;            T.dmz   = z2(m)
    T.ds    = s;            T.dsz   = z2(s)
    T.dss   = ms;           T.dssz  = fmt("%03d", ms)
    T.dsps  = s * 1000 + ms
    T.da    = (h23 < 12) and "AM" or "PM"
    T.dz    = os.date("%Z", epoch)
    T.dtp   = (h23 * 3600 + m * 60 + s) / 86400
    T.depoch = epoch

    local utc = os.date("!*t", epoch)
    T.dhutc24 = utc.hour;  T.dhutc24z = z2(utc.hour)
    local uh12 = utc.hour % 12; if uh12 == 0 then uh12 = 12 end
    T.dhutc12 = uh12;      T.dhutc12z = z2(uh12)

    -- digit extraction
    T.dh23tt = floor(h23 / 10);  T.dh23to = h23 % 10
    T.dhtt   = floor(h12 / 10);  T.dhto   = h12 % 10
    T.dh11tt = floor((h23 % 12) / 10); T.dh11to = (h23 % 12) % 10
    T.dh24tt = floor(T.dh24 / 10); T.dh24to = T.dh24 % 10
    T.dmt    = floor(m / 10);    T.dmo    = m % 10
    T.dst    = floor(s / 10);    T.dso    = s % 10

    -- ── Rotation helpers (analog hands), degrees clockwise from 12 ──
    local h12f = (h23 % 12) + m / 60 + s / 3600
    T.drh   = h12f / 12 * 360
    T.drh24 = (h23 + m / 60) / 24 * 360
    T.drh0  = (h23 % 12) / 12 * 360
    T.drm   = (m + s / 60) / 60 * 360
    T.drs   = s / 60 * 360
    T.drss  = (s + subsec) / 60 * 360
    T.drms  = subsec * 360

    -- ── Date components ──
    T.dd   = t.day;        T.ddz  = z2(t.day)
    T.ddy  = t.yday
    local wd = t.wday       -- 1=Sun .. 7=Sat
    T.ddw0 = wd - 1
    T.ddw  = DOW_3[wd];    T.ddww = DOW_FULL[wd]
    T.ddw2 = DOW_2[wd];    T.ddw1 = DOW_1[wd]
    T.dn   = t.month;      T.dnn  = z2(t.month)
    T.dnnn = MONTHS_3[t.month];  T.dnnnn = MONTHS_FULL[t.month]
    T.dy   = fmt("%02d", t.year % 100);  T.dyy = t.year
    T.dw   = tonumber(os.date("%W", epoch)) + 1
    T.dwm  = floor((t.day - 1) / 7) + 1
    local function daysInMonth(yr, mo)
        local nd = { 31,28,31,30,31,30,31,31,30,31,30,31 }
        if mo == 2 and (yr % 4 == 0 and (yr % 100 ~= 0 or yr % 400 == 0)) then return 29 end
        return nd[mo]
    end
    T.ddim = daysInMonth(t.year, t.month)
    T.ddiy = (t.year % 4 == 0 and (t.year % 100 ~= 0 or t.year % 400 == 0)) and 366 or 365

    -- future day names (1..6 days ahead)
    for n = 1, 6 do
        local ft = os.date("*t", epoch + n * 86400)
        T["ddw_"  .. n] = DOW_3[ft.wday]
        T["ddw1_" .. n] = DOW_1[ft.wday]
        T["ddw2_" .. n] = DOW_2[ft.wday]
        T["ddww_" .. n] = DOW_FULL[ft.wday]
    end

    -- ── Battery (watch) ──
    local b = WF.battery
    T.bl  = floor(num(b.level)); T.blp = T.bl .. "%"
    T.br  = num(b.level) * 3.6
    T.btc = floor(num(b.temperature)); T.btf = floor(num(b.temperature) * 9 / 5 + 32)
    T.btcd = T.btc .. "°C"; T.btfd = T.btf .. "°F"
    T.bc  = b.charging and "Charging" or "Discharging"
    T.bv  = b.voltage or 0; T.bi = b.current or 0

    -- ── Weather ──
    local w = WF.weather
    T.wl = w.locationName or ""
    T.wt = floor(num(w.currentTemp)); T.wm = w.tempUnit or "C"
    T.wtd = T.wt .. "°" .. T.wm
    T.wth = floor(num(w.highTemp)); T.wthd = T.wth .. "°" .. T.wm
    T.wtl = floor(num(w.lowTemp));  T.wtld = T.wtl .. "°" .. T.wm
    T.wct = w.conditionText or ""; T.wci = tostring(w.conditionIcon or "")
    T.wh = floor(num(w.humidity)); T.whp = T.wh .. "%"
    T.wp = floor(num(w.pressure))
    T.wws = num(w.windSpeed); T.wwd = num(w.windDirection)
    T.wwdb = bearing(T.wwd, BEAR_8); T.wwdbb = bearing(T.wwd, BEAR_16)
    T.wcl = floor(num(w.cloudiness)); T.wr = num(w.rainVolume)
    T.wisday = boolStr(w.isDaytime)
    T.wsr = w.sunrise or ""; T.wss = w.sunset or ""
    T.wmp = w.moonPhase or 0; T.wlu = w.lastUpdate or 0

    -- forecast days 0..5 (daily[1]=today)
    local daily = w.daily or {}
    for n = 0, 5 do
        local d = daily[n + 1]
        local pre = "wf" .. n .. "d"
        if d then
            T[pre .. "t"]  = floor(num(d.avgTemp))
            T[pre .. "th"] = floor(num(d.high))
            T[pre .. "tl"] = floor(num(d.low))
            T[pre .. "ct"] = d.conditionText or ""
            T[pre .. "ci"] = tostring(d.conditionIcon or "")
        end
    end
    -- forecast hours 1..12 (hourly[1]=next hour)
    local hourly = w.hourly or {}
    for n = 1, 12 do
        local hh = hourly[n]
        local pre = "wf" .. n .. "h"
        if hh then
            T[pre .. "t"]  = floor(num(hh.temp))
            T[pre .. "h"]  = floor(num(hh.hourOfDay))
            T[pre .. "ct"] = hh.conditionText or ""
            T[pre .. "ci"] = tostring(hh.conditionIcon or "")
        end
    end

    -- ── Health & activity ──
    local hs, hr, sp = WF.health_steps, WF.health_heartRate, WF.health_spo2
    T.ssc = floor(num(hs.steps)); T.stsc = floor(num(hs.stepGoal))
    T.sscp = T.stsc > 0 and floor(T.ssc / T.stsc * 100) or 0
    T.sdst = num(hs.distance); T.stdst = 8.0
    T.sdstp = T.stdst > 0 and floor(T.sdst / T.stdst * 100) or 0
    T.sdstu = "km"
    T.scal = num(hs.calories); T.stcal = 350
    T.scalp = floor(T.scal / T.stcal * 100)
    T.shr = floor(num(hr.bpm)); T.sthr = floor(num(hr.maxHeartRate))
    T.shrp = T.sthr > 0 and floor(T.shr / T.sthr * 100) or 0
    local hist = hr.history or {}
    for n = 1, 9 do
        local entry = hist[10 - n]      -- history[10]=newest, _1 = previous
        T["shr_" .. n] = entry and floor(num(entry.bpm)) or 0
    end
    T.sbo = floor(num(sp.percent))

    -- ── Sensors / compass ──
    local acc, gyr, cmp, bar = WF.sensor_accelerometer, WF.sensor_gyroscope,
                               WF.sensor_compass, WF.sensor_barometer
    T.sprs = num(bar.pressure)
    T.sax, T.say, T.saz = num(acc.x), num(acc.y), num(acc.z)
    T.sgx, T.sgy, T.sgz = num(gyr.x), num(gyr.y), num(gyr.z)
    local az = num(cmp.azimuth)
    local heading = (az + 360) % 360
    T.scr = -heading; T.sct = heading; T.sctd = fmt("%d°", floor(heading))
    T.scb = bearing(heading, BEAR_8); T.scbb = bearing(heading, BEAR_16)
    T.sctdb  = T.sctd .. T.scb; T.sctdbb = T.sctd .. T.scbb

    -- ── System & device ──
    local d = WF.device
    T.aos = d.osType or "Android"; T.aosv = d.apiLevel or 0
    T.aman = d.manufacturer or ""; T.amodel = d.deviceModel or ""
    T.aname = d.productName or ""; T.awname = d.deviceLabel or d.deviceModel or ""
    T.around = boolStr(d.isRound); T.atyre = "false"
    T.abright = boolStr(not (WF.settings and WF.settings.aodEnabled))
    T.alangcode = d.language or ""; T.alangfull = d.language or ""
    T.areboot = d.lastReboot or ""
    local sys = WF.system or {}
    T.avol = sys.volume or 0; T.abrt = sys.brightness or 0
    T.alowpw = boolStr(sys.lowPowerMode); T.adark = boolStr(sys.darkMode)

    -- location
    local loc = WF.location or {}
    T.alat = 0; T.alon = 0; T.aalt = 0

    -- memory & storage (bytes → GB)
    local st = WF.storage or {}
    local GB = 1073741824
    T.amu = fmt("%.1f", num(st.usedRam) / GB); T.amuf = T.amu .. " GB"
    T.amt = fmt("%.1f", num(st.totalRam) / GB); T.amtf = T.amt .. " GB"
    T.amf = fmt("%.1f", (num(st.totalRam) - num(st.usedRam)) / GB); T.amff = T.amf .. " GB"
    T.adsu = fmt("%.1f", num(st.usedStorage) / GB); T.adsuf = T.adsu .. " GB"
    T.adst = fmt("%.1f", num(st.totalStorage) / GB); T.adstf = T.adst .. " GB"
    T.adsf = fmt("%.1f", (num(st.totalStorage) - num(st.usedStorage)) / GB); T.adsff = T.adsf .. " GB"

    -- ── Network ──
    local n = WF.network
    T.nc  = boolStr(n.wifiConnected or n.dataConnected)
    T.ncc = boolStr(n.dataConnected)
    T.pwc = boolStr(n.wifiConnected)
    T.pws = floor(num(n.wifiSignal) / 4 * 100)
    T.nwip = n.wifiIp or ""
    local conn = WF.connectivity or {}
    T.nbc = boolStr(conn.bluetoothEnabled); T.ngc = boolStr(conn.gpsEnabled)
    T.abtc = boolStr(conn.bluetoothEnabled)

    -- ── Notifications ──
    local notif = WF.notification or {}
    T.cnc = notif.count or 0
    for i = 1, 3 do
        local it = (notif.list or {})[i] or {}
        T["cn" .. i .. "a"] = it.appName or ""
        T["cn" .. i .. "t"] = it.title or ""
        T["cn" .. i .. "c"] = it.content or ""
    end

    -- ── Alarm ──
    local al = WF.alarm or {}
    T.aalo = al.hasAlarm and 1 or 0
    if al.hasAlarm and al.alarmTime and al.alarmTime:find(":") then
        local ah, am = al.alarmTime:match("(%d+):(%d+)")
        T.aalh23z = z2(tonumber(ah) or 0); T.aalmz = z2(tonumber(am) or 0)
    else
        T.aalh23z = "--"; T.aalmz = "--"
    end

    -- ── Time zones (tz1 = device local, tz2/tz3 = configured) ──
    local tzs = WF.timezones or {}
    for i = 1, 3 do
        local tz = tzs["tz" .. i]
        local p  = "tz" .. i
        if tz then
            T[p .. "l"]  = tz.locationShort or ""
            T[p .. "ll"] = tz.locationLong or ""
            T[p .. "o"]  = tz.utcOffset or ""
            T[p .. "om"] = tz.utcOffsetMinutes or 0
            T[p .. "dst"] = boolStr(tz.dstActive)
            local off = num(tz.utcOffsetMinutes)
            local zt  = os.date("!*t", epoch + off * 60)
            T[p .. "t"]    = z2(zt.hour) .. ":" .. z2(zt.min)
            local z12 = (zt.hour % 12) + zt.min / 60
            T[p .. "rh"]   = z12 / 12 * 360
            T[p .. "rh24"] = (zt.hour + zt.min / 60) / 24 * 360
            T[p .. "rm"]   = zt.min / 60 * 360
        end
    end
    -- tz1 falls back to device local time when no zone data supplied
    if not T.tz1t then
        T.tz1t = T.dh23z .. ":" .. T.dmz
        T.tz1rh, T.tz1rh24, T.tz1rm = T.drh, T.drh24, T.drm
    end

    -- ── Accent colours (theme) ──
    -- ctx.theme is injected by the renderer from <ThemeSets>/ucolor_default
    T.ucolor   = "ffffff"; T.ucolor2 = "ffffff"; T.ucolor3 = "ffffff"
    T.ucolor_b = "ffffff"

    -- ── Calendar ──
    local cal = WF.calendar or {}
    T.cex = boolStr(cal.hasEvents)
    local events = cal.events or {}
    for i = 1, 10 do
        local ev = events[i] or {}
        local p = "c" .. i
        T[p .. "ex"]  = boolStr(ev.exists)
        T[p .. "t"]   = ev.title or ""
        T[p .. "b"]   = ev.beginTime or ""
        T[p .. "e"]   = ev.endTime or ""
        T[p .. "bd"]  = ev.beginDate or ""
        T[p .. "ed"]  = ev.endDate or ""
        T[p .. "l"]   = ev.location or ""
        T[p .. "c"]   = ev.color or "ffffff"
        T[p .. "ad"]  = boolStr(ev.allDay)
        T[p .. "cal"] = ev.calendar or ""
        T[p .. "i"]   = ev.id or ""
    end

    -- ── Counters ──
    T.c_elapsed = elapsed
    T._elapsed  = elapsed     -- used by dynamic counter resolution

    return T
end

-- ── per-frame refresh (FRAME-cadence tags only) ───────────────────────────────
-- Mutates the persistent snapshot T in place with just the sub-second tags, so
-- the 60fps hot path allocates nothing and skips the ~150-tag full rebuild. The
-- slower buckets (seconds and up) are refreshed by a full WFTags.build on their
-- own boundary ticks. Kept in sync with the FRAME set in wf_cadence.lua.
function WFTags.buildFrame(T, clock)
    local subsec = clock.subsec or 0
    local s  = clock.t and clock.t.sec or 0
    local ms = floor(subsec * 1000)
    T.dss  = ms
    T.dssz = fmt("%03d", ms)
    T.dsps = s * 1000 + ms
    T.drss = (s + subsec) / 60 * 360
    T.drms = subsec * 360
    T.c_elapsed = clock.elapsed or 0
    T._elapsed  = clock.elapsed or 0
    return T
end

-- ── dynamic tags resolved on demand (counters depend on elapsed time) ─────────

-- {c_FROM_TO_DURATION_MODE_DELAY}
local function resolveCounter(tag, T)
    local from, to, dur, mode, delay =
        tag:match("^c_([%-%d%.]+)_([%-%d%.]+)_([%d%.]+)_(%a+)_?([%d%.]*)$")
    if not from then return nil end
    from, to, dur = tonumber(from), tonumber(to), tonumber(dur)
    delay = tonumber(delay) or 0
    if not (from and to and dur and dur > 0) then return nil end
    local e = (T._elapsed or 0) - delay
    if e < 0 then return from end
    local phase = e / dur
    local frac
    if mode == "st" then          -- run once then stop
        frac = math.min(phase, 1)
    elseif mode == "rp" then      -- repeat
        frac = phase % 1
    elseif mode == "rv" then      -- reverse / ping-pong
        local p = phase % 2
        frac = p <= 1 and p or (2 - p)
    else
        frac = math.min(phase, 1)
    end
    return from + (to - from) * frac
end

-- Resolve a single bare tag name (no braces) to a value, or nil if unknown.
function WFTags.resolve(tag, T)
    local v = T[tag]
    if v ~= nil then return v end
    if tag:sub(1, 2) == "c_" then return resolveCounter(tag, T) end
    return nil
end

-- ── substitution ──────────────────────────────────────────────────────────────

-- Replace every {tag} in str. Unknown tags become empty string.
function WFTags.sub(str, T)
    if not str or str == "" then return str end
    return (str:gsub("{([^{}]-)}", function(tag)
        local v = WFTags.resolve(tag, T)
        if v == nil then return "" end
        return tostring(v)
    end))
end

-- ── XML entity decode (attributes are stored XML-escaped) ─────────────────────

function WFTags.unescape(str)
    if not str then return str end
    return (str:gsub("&quot;", '"'):gsub("&apos;", "'")
               :gsub("&lt;", "<"):gsub("&gt;", ">"):gsub("&amp;", "&"))
end

-- ── sandboxed Lua evaluation ──────────────────────────────────────────────────

local SANDBOX = {
    string = string, math = math, table = table, os = os,
    tonumber = tonumber, tostring = tostring, type = type,
    pairs = pairs, ipairs = ipairs, select = select,
    pcall = pcall, print = print,
}

-- Numeric attributes (rotation, angle, pct…) are permissive: any arithmetic,
-- comparison or call counts as an expression to evaluate.
local function looksLikeExprNum(s)
    return s:find("string%.") or s:find("math%.") or s:find("tonumber")
        or s:find("tostring") or s:find("[%(%)]")
        or s:find("[%+%*/%%]") or s:find("%d%s*%-") or s:find("%.%.")
        or s:find("==") or s:find("~=") or s:find("<") or s:find(">")
        or s:find("%sand%s") or s:find("%sor%s")
end

-- Text attributes are strict: only treat as code for library refs or function
-- calls, so a plain label like "2024-2026" or "20-5" stays literal text.
local function looksLikeExprText(s)
    return s:find("string%.") or s:find("math%.") or s:find("tonumber")
        or s:find("tostring") or s:find("tween")
        or s:find("[%a_][%w_]*%s*%(") ~= nil
end

local function tryEval(expr)
    local chunk = load("return " .. expr, "=wf_expr", "t", SANDBOX)
    if not chunk then return nil, false end
    local ok, res = pcall(chunk)
    if ok then return res, true end
    return nil, false
end

-- Resolve a text attribute: substitute tags, unescape, then evaluate if it
-- looks like Lua. Falls back to the substituted literal on any failure.
function WFTags.evalText(str, T)
    if not str then return "" end
    local s = WFTags.unescape(str)
    s = WFTags.sub(s, T)
    if looksLikeExprText(s) then
        local res, ok = tryEval(s)
        if ok and res ~= nil then return tostring(res) end
    end
    return s
end

-- Resolve a numeric attribute (rotation, angle, pct, x/y…) to a number.
function WFTags.evalNumber(str, T, default)
    default = default or 0
    if str == nil then return default end
    if type(str) == "number" then return str end
    local s = WFTags.unescape(str)
    s = WFTags.sub(s, T)
    local n = tonumber(s)
    if n then return n end
    if looksLikeExprNum(s) then
        local res, ok = tryEval(s)
        if ok and tonumber(res) then return tonumber(res) end
    end
    return default
end

-- True when a raw attribute carries a {tag}. Values that resolve from tags are
-- the only ones that change between frames; pure-constant expressions are
-- evaluated once at build time, so they need no per-frame refresh.
function WFTags.isDynamic(str)
    if type(str) ~= "string" then return false end
    return str:find("{[^{}]-}") ~= nil
end

return WFTags
