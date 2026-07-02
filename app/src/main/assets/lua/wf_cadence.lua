-- wf_cadence.lua — classify Watchmaker {tag} names by update cadence + data source.
--
-- The renderer used to recompute every tag and re-apply every dynamic layer 60×/s.
-- This module lets it do work proportional to what actually changed: each tag is
-- assigned a clock cadence bucket (how often its value can change on its own) and,
-- for data that comes from Android, an async category (it only changes when the
-- bridge pushes new data — never on a clock).
--
-- classify(tag) -> bucket, asyncCat
--   bucket   : FRAME | SECOND | MINUTE | HOUR | DAY | STATIC  (nil for pure async)
--   asyncCat : "weather" | "health:steps" | "health:heartRate" | "health:spo2"
--            | "sensor:accelerometer" | "sensor:gyroscope" | "sensor:compass"
--            | "sensor:barometer" | "battery" | "calendar" | "notification"
--            | "network" | "connectivity" | "system" | "alarm" | "location"
--            | "timezone"   (or nil)
--
-- See watchmaker-definition/WM_TAGS.md for the authoritative catalogue.

local WFCadence = {}

-- Cadence ordinals — lower = faster. STATIC = compute once, never refresh.
WFCadence.FRAME  = 1
WFCadence.SECOND = 2
WFCadence.MINUTE = 3
WFCadence.HOUR   = 4
WFCadence.DAY    = 5
WFCadence.STATIC = 99

local FRAME, SECOND, MINUTE, HOUR, DAY, STATIC =
    WFCadence.FRAME, WFCadence.SECOND, WFCadence.MINUTE,
    WFCadence.HOUR, WFCadence.DAY, WFCadence.STATIC

-- ── fixed-name tables ─────────────────────────────────────────────────────────

local BUCKET = {}    -- tag → cadence bucket
local ASYNC  = {}    -- tag → async category

local function setBucket(bucket, list) for _, t in ipairs(list) do BUCKET[t] = bucket end end
local function setAsync(cat, list)     for _, t in ipairs(list) do ASYNC[t]  = cat    end end

-- Clock cadences ---------------------------------------------------------------

setBucket(FRAME, { "dss", "dssz", "dsps", "drss", "drms" })

setBucket(SECOND, { "ds", "dsz", "dst", "dso", "drs", "dtp", "depoch" })

setBucket(MINUTE, { "dm", "dmz", "dmt", "dmo", "drm", "drh", "drh24", "da", "dz" })

setBucket(HOUR, {
    "dh", "dhz", "dh11", "dh11z", "dh24", "dh24z", "dh23", "dh23z", "dh23i",
    "dhtt", "dhto", "dh11tt", "dh11to", "dh24tt", "dh24to", "dh23tt", "dh23to",
    "dhutc12", "dhutc12z", "dhutc24", "dhutc24z", "drh0",
})

setBucket(DAY, {
    "dd", "ddz", "ddy", "ddiy", "ddim", "ddw0", "ddw", "ddww", "ddw2", "ddw1",
    "dn", "dnn", "dnnn", "dnnnn", "dy", "dyy", "dw", "dwm",
})

-- Time zones: the time-of-day tags are MINUTE (clock-driven); the metadata is
-- async (only changes when Android pushes zone data).
for i = 1, 3 do
    local p = "tz" .. i
    setBucket(MINUTE, { p .. "t", p .. "rh", p .. "rh24", p .. "rm" })
    setAsync("timezone", { p .. "l", p .. "ll", p .. "o", p .. "om", p .. "dst" })
end

-- Async / bridge-pushed sources ------------------------------------------------

setAsync("weather", {
    "wl", "wt", "wm", "wtd", "wth", "wthd", "wtl", "wtld", "wct", "wci",
    "wh", "whp", "wp", "wws", "wwd", "wwdb", "wwdbb", "wcl", "wr",
    "wisday", "wsr", "wss", "wmp", "wlu",
})

setAsync("health:steps", {
    "ssc", "stsc", "sscp", "sdst", "stdst", "sdstp", "sdstu",
    "scal", "stcal", "scalp",
})
setAsync("health:heartRate", { "shr", "sthr", "shrp" })
setAsync("health:spo2", { "sbo" })

setAsync("sensor:accelerometer", { "sax", "say", "saz" })
setAsync("sensor:gyroscope",     { "sgx", "sgy", "sgz" })
setAsync("sensor:compass",       { "scr", "sct", "sctd", "scb", "scbb", "sctdb", "sctdbb" })
setAsync("sensor:barometer",     { "sprs" })

setAsync("battery", { "bl", "blp", "br", "btc", "btf", "btcd", "btfd", "bc", "bv", "bi" })

setAsync("network",      { "nc", "ncc", "pwc", "pws", "nwip" })
setAsync("connectivity", { "nbc", "ngc", "abtc" })

setAsync("system", { "avol", "abrt", "alowpw", "adark" })
setAsync("storage", {   -- RAM + storage both arrive in the WF.storage snapshot
    "amu", "amuf", "amt", "amtf", "amf", "amff",
    "adsu", "adsuf", "adst", "adstf", "adsf", "adsff",
})
setAsync("location", { "alat", "alon", "aalt" })
setAsync("alarm",    { "aalo", "aalh23z", "aalmz" })
setAsync("notification", { "cnc" })

-- Static (device identity + theme colours) -------------------------------------

setBucket(STATIC, {
    "aos", "aosv", "aman", "amodel", "aname", "awname", "around", "atyre",
    "abright", "alangcode", "alangfull", "areboot",
    "ucolor", "ucolor2", "ucolor3", "ucolor_b",
})

-- ── parametric families (ordered; first match wins) ───────────────────────────

local PATTERNS = {
    { pat = "^c_",              bucket = FRAME,  async = nil },              -- counters {c_..}
    { pat = "^shr_%d+$",        bucket = nil,    async = "health:heartRate" }, -- HR history
    { pat = "^wf%d+d",          bucket = nil,    async = "weather" },        -- daily forecast
    { pat = "^wf%d+h",          bucket = nil,    async = "weather" },        -- hourly forecast
    { pat = "^cn%d+[atc]$",     bucket = nil,    async = "notification" },   -- cn1a/cn1t/cn1c
    { pat = "^c%d+%a",          bucket = nil,    async = "calendar" },       -- c1ex/c10cal/…
    { pat = "^ddww?[12]?_%d+$", bucket = DAY,    async = nil },              -- future weekday names
}

-- ── public API ────────────────────────────────────────────────────────────────

-- Returns (bucket, asyncCat). Unknown tags default to MINUTE (never slower, so
-- the worst-case error is a 1-minute lag) and are reported once for diagnostics.
local warned = {}
function WFCadence.classify(tag)
    local b, a = BUCKET[tag], ASYNC[tag]
    if b ~= nil or a ~= nil then return b, a end
    for _, r in ipairs(PATTERNS) do
        if tag:find(r.pat) then return r.bucket, r.async end
    end
    if not warned[tag] then
        warned[tag] = true
        print("[WFCadence] unclassified tag '" .. tostring(tag) .. "' → MINUTE")
    end
    return MINUTE, nil
end

return WFCadence
