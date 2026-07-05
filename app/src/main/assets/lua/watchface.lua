-- CloX Watchface — real Watchmaker (.watch) renderer.
--
-- Loads the .watch package pointed to by WF.settings.watchfacePath, extracts it,
-- parses watch.xml and renders it live through wf_render. Reacts to the Android
-- bridge: setting a new watchface, reload, reset, and theme/dim changes. When no
-- watchface is configured it shows a built-in vector face so the screen is never
-- blank.
--
-- Update model (see wf_render / wf_engine): the scene owns the clock and, each
-- tick, fires the engine events that ticked (ms every frame, second/minute/
-- hour/day on their boundaries). Each event recomputes only its active tags;
-- changed tags run the update buckets of exactly the layer properties indexed
-- under them at import. If a face has no sub-second work (no ms tags, no
-- on_millisecond, no tweens) the per-frame loop is suspended and a 1s poll
-- drives the boundaries instead. Async data (weather/health/sensors/battery/…)
-- updates via bridge listeners the renderer registers per face; the scene
-- enables only the data sources a face actually uses (§ feature toggles) and
-- caps FPS (normal max, 1 FPS in AOD).
--
--   bridge.lua            — JNI ↔ Lua event bus
--   watchface_bridge.lua  — structured WF.* live data + WatchfaceBridge actions
--   wf_engine.lua         — WFTag/WFEvent/WFEngine: tag buckets + event firing
--   wf_tagdefs.lua        — per-tag compute catalogue (value + owning event)
--   wf_expr.lua           — import-time attribute → compiled-function compiler
--   wf_binding.lua        — (layer, property) bucket entries
--   wf_sandbox.lua        — restricted env for face scripts/expressions
--   wf_script.lua         — global script host (on_*, vars, wm_*, tweens)
--   wf_render.lua         — watch.xml parsing + cocos layer rendering

require "bridge"
require "watchface_bridge"
local WFRender  = require "wf_render"
local WFProtect = require "wf_protect"

local WatchfaceScene = class("WatchfaceScene", cc.Scene)

-- Reference canvas of the Watchmaker format (see WM_FORMAT_1.0.md §4).
local CANVAS = 512

-- Built-in fallback face — a minimal theme over the app's launcher-background
-- asset: subtle hour ticks, rounded-tip hands, and small digital time / date /
-- battery. Accent #82abd1 on the date, second hand and centre cap. Authored in
-- the format's screen-down Y convention (positive Y = toward the bottom), which
-- the renderer flips into cocos Y-up — so it stays consistent with real faces.
local DEFAULT_FACE = [[
<Watch bg_color="0d0d0d" ucolor_default="82abd1">
  <Layer type="image" path="wf_default/bg.png" x="0" y="0" width="512" height="512"/>
  <Layer type="text" x="0" y="-120" alignment="cc" text="{dh23z}:{dmz}" text_size="34" color="ffffff"/>
  <Layer type="text" x="0" y="-84" alignment="cc" text="{dnnn} {dd}" transform="uc" text_size="20" color="{ucolor}"/>
  <Layer type="text" x="0" y="150" alignment="cc" text="{blp}" text_size="20" color="aaaaaa"/>
  <Layer type="rounded" alignment="bc" x="0" y="0" width="12" height="130" radius="6" corner_type="1" color="ffffff" rotation="{drh}"/>
  <Layer type="rounded" alignment="bc" x="0" y="0" width="8" height="200" radius="4" corner_type="1" color="ffffff" rotation="{drm}"/>
  <Layer type="rounded" alignment="bc" x="0" y="0" width="4" height="210" radius="2" corner_type="1" color="{ucolor}" rotation="{drss}"/>
  <Layer type="shape" shape="Circle" x="0" y="0" width="16" height="16" color="{ucolor}"/>
</Watch>
]]

-- ── extraction ────────────────────────────────────────────────────────────────

local extractSourcePath = nil    -- last extracted .watch (dedup guard)
local extractDir        = nil

-- Unzip a .watch into the writable dir; returns the base dir (trailing slash).
local function extractWatchface(watchPath)
    if not watchPath or watchPath == false or watchPath == "" then return nil end
    local dest = cc.FileUtils:getInstance():getWritablePath() .. "wf_active/"
    if watchPath == extractSourcePath and extractDir then return extractDir end
    axRemoveDir(dest)
    if not axUnzipToDir(watchPath, dest) then
        print("[Watchface] axUnzipToDir failed: " .. tostring(watchPath))
        return nil
    end
    extractSourcePath = watchPath
    extractDir = dest
    return dest
end

-- ── scene lifecycle ───────────────────────────────────────────────────────────

function WatchfaceScene:ctor()
    cc.Scene.ctor(self)
    self._elapsed = 0
    self._subsec  = 0
    self._lastSec, self._lastMin, self._lastHour, self._lastDay = -1, -1, -1, -1
    self._currentPath = nil   -- path of the face on screen (false = built-in default)
    self._failedPath  = nil   -- a real path we tried and bounced off (avoid retry thrash)
    self._activeFeatures = { weather = false, health = {}, sensor = {} }
    self._pendingLoad = nil   -- a load request deferred to the GL-safe scheduler pump
    -- reused per-tick event flags — the 60fps path must not allocate
    self._ticks = { frame = false, second = false, minute = false, hour = false, day = false }
end

function WatchfaceScene:onEnter()
    local director = cc.Director:getInstance()
    local sz = director:getWinSize()
    self._W, self._H = sz.width, sz.height

    -- background fill (recoloured per-face from bg_color)
    self._bg = cc.LayerColor:create(cc.c4b(0, 0, 0, 255))
    self:addChild(self._bg, -1)

    -- stage node maps the 512 canvas (origin centre) to the screen
    self._stage = cc.Node:create()
    self:addChild(self._stage, 0)

    -- status overlay (screen space) for load messages
    self._status = cc.Label:createWithSystemFont("", "sans-serif",
        math.max(14, math.floor(self._W / 28)))
    self._status:setTextColor(cc.c4b(200, 200, 200, 255))
    self._status:setAnchorPoint(cc.p(0.5, 0))
    self._status:setPosition(self._W / 2, math.floor(self._H * 0.04))
    self:addChild(self._status, 10)

    self._renderer = WFRender.new(self._stage)

    -- load the configured face, or the built-in fallback
    local initPath = WF.settings and WF.settings.watchfacePath
    if not self:loadWatchface(initPath) then
        self:loadDefault("No watchface set — showing default")
    end

    self:wireBridge()

    -- Permanent load pump. All face loading (which creates TTF Labels → compiles
    -- GL shaders) must run from a Director scheduler callback, where the GL
    -- context is guaranteed current. Bridge dispatches (which run on the GL thread
    -- via queueEvent, possibly before onSurfaceCreated or during context churn)
    -- only *request* a load; this pump performs it on the next frame. One boolean
    -- check per frame when idle — negligible, and it ticks at the FPS cap (1 in AOD).
    self._loadTimer = cc.Director:getInstance():getScheduler()
        :scheduleScriptFunc(function() self:serviceLoad() end, 0, false)

    Bridge.sendToAndroid("wf_lua_ready", "")
end

function WatchfaceScene:onExit()
    local sched = cc.Director:getInstance():getScheduler()
    if self._frameTimer then sched:unscheduleScriptEntry(self._frameTimer); self._frameTimer = nil end
    if self._clockTimer then sched:unscheduleScriptEntry(self._clockTimer); self._clockTimer = nil end
    if self._loadTimer then sched:unscheduleScriptEntry(self._loadTimer); self._loadTimer = nil end
    -- release any async sensor streams the face had running
    self:applyFeatures({ weather = false, health = {}, sensor = {} })
end

-- Apply zoom / pan settings and the 512→screen scale to the stage. Scale fits
-- the smaller screen dimension (never overflows), then the per-face zoom/offset
-- nudge for pixel-perfect placement. Positive yOffset moves the face down.
function WatchfaceScene:applyStageTransform()
    local s = WF.settings or {}
    local base = math.min(self._W, self._H) / CANVAS
    self._stage:setScale(base * (tonumber(s.zoom) or 1.0))
    self._stage:setPosition(self._W / 2 + (tonumber(s.xOffset) or 0),
                            self._H / 2 - (tonumber(s.yOffset) or 0))
end

-- Cap the render rate: 1 FPS in AOD, otherwise the per-face max (default 25,
-- unlockable to 60). The heavy per-frame Lua work is already gated by the
-- cadence scheduler; this bounds the GL draw / smooth-hand rate.
function WatchfaceScene:applyFrameRate()
    local s = WF.settings or {}
    local fps
    if s.aodEnabled then
        fps = 1
    elseif s.maxFpsUnlimited then
        fps = 60
    else
        fps = tonumber(s.maxFps) or tonumber(s.frameRate) or 25
    end
    fps = math.max(1, math.min(fps, 60))
    cc.Director:getInstance():setAnimationInterval(1.0 / fps)
end

-- Enable exactly the async data sources this face uses; disable ones the
-- previous face used but this one doesn't. Diffed against _activeFeatures so a
-- settings push (which arrives often) never re-sends unchanged toggles.
function WatchfaceScene:applyFeatures(f)
    f = f or { weather = false, health = {}, sensor = {} }
    f.health = f.health or {}
    f.sensor = f.sensor or {}
    local prev = self._activeFeatures
    if (f.weather or false) ~= (prev.weather or false) then
        WatchfaceBridge.setWeatherEnabled(f.weather or false)
    end
    for _, m in ipairs({ "steps", "heartRate", "spo2" }) do
        if (f.health[m] or false) ~= (prev.health[m] or false) then
            WatchfaceBridge.setHealthEnabled(m, f.health[m] or false)
        end
    end
    for _, sn in ipairs({ "accelerometer", "compass", "barometer", "gyroscope" }) do
        if (f.sensor[sn] or false) ~= (prev.sensor[sn] or false) then
            WatchfaceBridge.setSensorEnabled(sn, f.sensor[sn] or false)
        end
    end
    self._activeFeatures = f
end

-- Start (or restart) the clock driver for the loaded face. A face with any
-- sub-second work (ms tags, on_millisecond, live tweens) runs the per-frame
-- scheduler for smooth motion; otherwise a 1s poll drives the boundaries and
-- the per-frame loop stays suspended.
function WatchfaceScene:restartClock()
    local sched = cc.Director:getInstance():getScheduler()
    if self._frameTimer then sched:unscheduleScriptEntry(self._frameTimer); self._frameTimer = nil end
    if self._clockTimer then sched:unscheduleScriptEntry(self._clockTimer); self._clockTimer = nil end
    self._lastSec, self._lastMin, self._lastHour, self._lastDay = -1, -1, -1, -1
    self._subsec = 0
    if self._renderer.needFrame then
        self._frameTimer = sched:scheduleScriptFunc(function()
            self:tick(cc.Director:getInstance():getDeltaTime())
        end, 0, false)
    else
        self._clockTimer = sched:scheduleScriptFunc(function() self:tick(1.0) end, 1.0, false)
    end
end

-- Common post-load wiring: stage transform, feature toggles, clock driver, FPS.
function WatchfaceScene:afterLoad()
    self:applyStageTransform()
    self:applyFeatures(self._renderer.features)
    self:restartClock()
    self:applyFrameRate()
    -- a tap script scheduling the first tween can flip the frame-loop need
    -- while the per-frame scheduler is suspended — restart the driver then
    self._renderer.onNeedFrame = function() self:restartClock() end
end

-- ── loading ───────────────────────────────────────────────────────────────────

function WatchfaceScene:loadWatchface(path)
    local base = extractWatchface(path)
    if not base then return false end
    -- Protected faces (protection="y") keep the real definition in watch.pxml
    -- (obfuscated) and a stub watch.xml; decode the pxml when present.
    local xml
    if WFProtect.isProtected(base) then
        xml = WFProtect.readPxml(base)
        print("[Watchface] protected face — decoded watch.pxml (" .. #xml .. " bytes)")
    else
        xml = cc.FileUtils:getInstance():getStringFromFile(base .. "watch.xml")
    end
    if not xml or #xml == 0 then
        print("[Watchface] watch definition missing/empty in " .. base)
        return false
    end
    local ok, err = xpcall(function() self._renderer:load(xml, base) end, debug.traceback)
    if not ok then
        print("[Watchface] render failed: " .. tostring(err))
        WatchfaceBridge.reportError("Watchface load failed", err)
        return false
    end
    -- A face that parsed to nothing renderable is a failure → fall back, never
    -- leave a black screen.
    if #self._renderer.recs == 0 then
        print("[Watchface] no renderable layers in " .. tostring(path))
        return false
    end
    self:setBg(self._renderer.bgColor)
    self._status:setString("")
    self._currentPath = path
    self:afterLoad()
    Bridge.sendToAndroid("wf_loaded", self._renderer.watch.name or "")
    return true
end

function WatchfaceScene:loadDefault(msg)
    local base = cc.FileUtils:getInstance():getWritablePath()   -- no packaged assets needed
    local ok, err = xpcall(function() self._renderer:load(DEFAULT_FACE, base) end, debug.traceback)
    if not ok then
        print("[Watchface] default render failed: " .. tostring(err))
        WatchfaceBridge.reportError("Default face render failed", err)
    end
    self:setBg(self._renderer.bgColor)
    self._currentPath = false   -- false = built-in default is on screen
    self:afterLoad()
    if msg then self:flashStatus(msg) end
end

function WatchfaceScene:setBg(hex)
    hex = tostring(hex or "000000")
    local r = tonumber(hex:sub(1, 2), 16) or 0
    local g = tonumber(hex:sub(3, 4), 16) or 0
    local b = tonumber(hex:sub(5, 6), 16) or 0
    self._bg:setColor(cc.c3b(r, g, b))
end

function WatchfaceScene:flashStatus(msg)
    self._status:setString(msg)
    self._status:setOpacity(255)
    self._status:stopAllActions()
    self._status:runAction(cc.Sequence:create(
        cc.DelayTime:create(2.5), cc.FadeOut:create(0.6),
        cc.CallFunc:create(function() self._status:setString("") end)))
end

-- ── bridge wiring ─────────────────────────────────────────────────────────────

-- Queue a load request; the GL-safe pump (serviceLoad) performs it on the next
-- frame. `path` is a real path string, or false/"" for the built-in default.
-- Last write wins, so a burst of settings pushes coalesces into a single load.
function WatchfaceScene:requestLoad(path)
    self._pendingLoad = { path = path }
end

-- Perform any queued load. Called only from the scheduler pump (GL context
-- current), so this is the ONLY place face loading — and thus TTF Label / shader
-- creation — runs. Loading from a raw bridge dispatch instead would risk a native
-- shader-compile abort when the GL context isn't current (see onEnter).
function WatchfaceScene:serviceLoad()
    local req = self._pendingLoad
    if not req then return end
    self._pendingLoad = nil
    self:syncWatchface(req.path)
end

-- Drive the display to match the requested path: load a real face, or fall back
-- to the built-in default when the path is empty/false or the face won't load.
-- Tracks _currentPath (what's shown; false = default) and _failedPath (a real
-- path we already tried and bounced off) so repeated settings pushes — which
-- arrive on every zoom/AOD tick — don't reload or thrash the screen.
function WatchfaceScene:syncWatchface(path)
    if type(path) == "string" and path ~= "" then
        if path == self._currentPath or path == self._failedPath then
            self:applyStageTransform()          -- same target, only zoom/pan moved
        elseif self:loadWatchface(path) then
            self._failedPath = nil
        else
            self._failedPath = path
            self:loadDefault("Couldn't load watchface — showing default")
        end
    else
        if self._currentPath ~= false then
            self:loadDefault()                  -- switched to no/default watchface
        else
            self:applyStageTransform()
        end
        self._failedPath = nil
    end
end

function WatchfaceScene:wireBridge()
    -- Java pushes a full settings snapshot whenever the watchface path, zoom,
    -- offsets, FPS or AOD state changes. Resolve the requested face, follow dim
    -- mode, and re-apply the stage transform + frame-rate cap.
    WatchfaceBridge.onChange("settings", function(data)
        if not data then return end
        if data.aodEnabled ~= nil then
            self._renderer:setDim(data.aodEnabled and true or false)
        end
        -- Loading is deferred to the scheduler pump (GL-safe); the transform and
        -- frame-rate touch no GL resources, so apply them immediately for a
        -- responsive zoom/AOD change.
        self:requestLoad(data.watchfacePath)
        self:applyStageTransform()
        self:applyFrameRate()
    end)

    -- explicit lifecycle commands (watchface_bridge.lua §ANDROID → LUA). These
    -- only queue a load; the extract-cache side effects below touch no GL state.
    WatchfaceBridge.onChange("cmd_setActive", function(data)
        self._failedPath = nil   -- explicit command: allow a retry
        self:requestLoad(data and data.path)
    end)
    WatchfaceBridge.onChange("cmd_reload", function()
        extractSourcePath = nil   -- force re-extract
        self._failedPath = nil
        self:requestLoad(WF.settings and WF.settings.watchfacePath)
    end)
    WatchfaceBridge.onChange("cmd_reset", function()
        axRemoveDir(cc.FileUtils:getInstance():getWritablePath() .. "wf_active/")
        extractSourcePath, extractDir = nil, nil
        self._failedPath = nil
        self:requestLoad(WF.settings and WF.settings.watchfacePath)
    end)
end

-- ── per-frame / per-tick update ───────────────────────────────────────────────

function WatchfaceScene:tick(dt)
    local t = os.date("*t")
    -- phase-lock the sub-second clock to real seconds for clean hand ticks
    if t.sec ~= self._lastSec then
        self._subsec = 0
    else
        self._subsec = math.min(1, self._subsec + dt)
    end
    self._elapsed = self._elapsed + dt

    local r = self._renderer
    local ticks = self._ticks
    ticks.frame  = r.needFrame
    ticks.second = t.sec  ~= self._lastSec
    ticks.minute = t.min  ~= self._lastMin
    ticks.hour   = t.hour ~= self._lastHour
    ticks.day    = t.day  ~= self._lastDay

    -- The engine does work proportional to what changed: each fired event
    -- recomputes only its active tags, and only changed tags run their layer
    -- buckets. No snapshot rebuilds, no per-layer re-scans.
    r:tick(t, self._subsec, self._elapsed, ticks)

    self._lastSec, self._lastMin  = t.sec, t.min
    self._lastHour, self._lastDay = t.hour, t.day
end

return WatchfaceScene
