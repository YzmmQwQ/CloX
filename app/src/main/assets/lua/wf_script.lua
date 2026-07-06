-- wf_script.lua — WFScript: hosts the face's global script
-- (scripts/script.txt) inside the sandbox and wires it into the tag engine.
--
-- Contract (from watchface_format_old.md §12 and the UL reference renderer):
--   • the top-level chunk runs ONCE at face load (defines variables/functions)
--   • on_millisecond(dt)          — every rendered frame
--   • on_second(minute, second)   — on the second boundary
--   • on_minute() / on_hour()     — on their boundaries
--   • every {tag} readable as a bare global (dh, blp, shr, …)
--   • helpers: wm_tag(t) · wm_action(a) · wm_sfx(s) · wm_schedule{...}
--   • tweens via wm_schedule are referenced as {tweens.NAME}
--   • script variables are referenced from attributes as {var} tags AND as
--     bare identifiers inside expressions
--
-- Variable propagation — the load-bearing part: the script runs against a
-- PROXY environment whose __newindex always fires (variables live in a shadow
-- store, never raw keys of the env). Every write
--   1. stores the value,
--   2. registers a tag def for the name (so later-compiled attributes can
--      activate it), and
--   3. engine:publish()es it — which immediately runs the update bucket of
--      every layer property whose expression uses that variable.
-- Reads fall through: shadow store → sandbox libraries/helpers → live tag
-- values (engine:resolve). No pattern matching anywhere on this path.

local WFTagDefs = require "wf_tagdefs"

local WFScript = {}
WFScript.__index = WFScript

-- ── {tag} splicing in script SOURCE (load time — patterns are fine here) ─────
-- Real faces write tags straight into their scripts:
--     if var_chime == 1 and {dm} == 30 then …
--     local condition = "{wct}"
-- In plain Lua `{dm}` is a table constructor and "{wct}" a literal string, so
-- neither ever works. Rewrite KNOWN catalogue tags to live bare reads (the env
-- proxy resolves them through the engine at call time):
--     {dm}     → (dm)            outside strings
--     "{wct}"  → "" .. wct .. "" inside strings (same splice as wf_expr)
-- Unknown names are left untouched — `{ 'DOM', 'LUN' }` table constructors and
-- author-local names keep their Lua meaning. Comments are skipped so an
-- apostrophe in "-- it's" can't derail quote tracking.
local function knownName(name)
    if WFTagDefs.lookup(name) then return true end
    return name:sub(1, 7) == "tweens."
end

local function spliceTags(src)
    local out, i, n = {}, 1, #src
    local quote = nil
    while i <= n do
        local c = src:sub(i, i)
        if quote then
            if c == "\\" then
                out[#out + 1] = src:sub(i, i + 1); i = i + 2
            elseif c == "{" then
                local _, e, name = src:find("^{([%w_%.]+)}", i)
                if e and knownName(name) then
                    out[#out + 1] = quote .. " .. " .. name .. " .. " .. quote
                    i = e + 1
                else
                    out[#out + 1] = c; i = i + 1
                end
            else
                out[#out + 1] = c
                if c == quote then quote = nil end
                i = i + 1
            end
        elseif c == "-" and src:sub(i, i + 1) == "--" then
            local a, e = src:find("^%-%-%[%[.-%]%]", i)     -- long comment
            if not a then e = (src:find("\n", i) or n + 1) - 1 end
            out[#out + 1] = src:sub(i, e)
            i = e + 1
        elseif c == "'" or c == '"' then
            quote = c; out[#out + 1] = c; i = i + 1
        elseif c == "{" then
            local _, e, name = src:find("^{([%w_%.]+)}", i)
            if e and knownName(name) then
                out[#out + 1] = "(" .. name .. ")"
                i = e + 1
            else
                out[#out + 1] = c; i = i + 1
            end
        else
            out[#out + 1] = c; i = i + 1
        end
    end
    return table.concat(out)
end
WFScript.spliceTags = spliceTags   -- exposed for tap-action snippets + tests

-- ── easing catalogue (normalized t ∈ 0..1 → 0..1), exposed to scripts ─────────

local pow, sin, cos, pi = nil, math.sin, math.cos, math.pi
local EASING = {}
EASING.linearTween    = function(t) return t end
EASING.easeInQuad     = function(t) return t * t end
EASING.easeOutQuad    = function(t) return t * (2 - t) end
EASING.easeInOutQuad  = function(t) return t < 0.5 and 2*t*t or -1 + (4 - 2*t)*t end
EASING.easeInCubic    = function(t) return t^3 end
EASING.easeOutCubic   = function(t) local u = t - 1; return u^3 + 1 end
EASING.easeInOutCubic = function(t) return t < 0.5 and 4*t^3 or (t-1)*(2*t-2)^2 + 1 end
EASING.easeInQuart    = function(t) return t^4 end
EASING.easeOutQuart   = function(t) local u = t - 1; return 1 - u^4 end
EASING.easeInOutQuart = function(t) local u = t - 1; return t < 0.5 and 8*t^4 or 1 - 8*u^4 end
EASING.easeInQuint    = function(t) return t^5 end
EASING.easeOutQuint   = function(t) local u = t - 1; return u^5 + 1 end
EASING.easeInOutQuint = function(t) local u = t - 1; return t < 0.5 and 16*t^5 or 1 + 16*u^5 end
EASING.easeInSine     = function(t) return 1 - cos(t * pi / 2) end
EASING.easeOutSine    = function(t) return sin(t * pi / 2) end
EASING.easeInOutSine  = function(t) return -(cos(pi * t) - 1) / 2 end
EASING.easeInExpo     = function(t) return t == 0 and 0 or 2 ^ (10 * (t - 1)) end
EASING.easeOutExpo    = function(t) return t == 1 and 1 or 1 - 2 ^ (-10 * t) end
EASING.easeInOutExpo  = function(t)
    if t == 0 or t == 1 then return t end
    if t < 0.5 then return 2 ^ (20 * t - 10) / 2 end
    return (2 - 2 ^ (-20 * t + 10)) / 2
end

-- ── construction ──────────────────────────────────────────────────────────────

-- engine: WFEngine · sandbox: WFSandbox · actionHandler: fn(actionString)
-- (renderer's tap-action dispatch, so wm_action supports every tap action).
function WFScript.new(engine, sandbox, actionHandler)
    local self = setmetatable({}, WFScript)
    self.engine   = engine
    self.sandbox  = sandbox
    self.vars     = {}        -- shadow store: script globals / variables
    self.tweens   = {}        -- name → live tween state
    self.hasScript = false
    self._lastMsElapsed = nil
    self._needFrameChanged = nil   -- scene hook: tween scheduled post-load

    local base   = sandbox:env()
    local vars   = self.vars
    local eng    = engine

    -- helpers visible to the script (installed on the raw sandbox env so the
    -- proxy read path finds them after the shadow store)
    base.wm_tag = function(t) return eng:resolve(tostring(t or "")) end
    base.wm_action = function(a)
        if type(a) == "string" and actionHandler then actionHandler(a) end
    end
    -- Sound playback with STRICT audio-device hygiene. Axmol's AudioEngine
    -- lazily opens the OpenAL Soft device on the first play2d and NEVER
    -- closes it — the alsoft-mixer thread then renders 24/7, audioserver
    -- holds an AudioMix wakelock charged to our uid, doze never engages and
    -- the watch cooks (observed: ~25% CPU, 48–59°C at "idle"). So: when the
    -- last sound finishes, tear the engine down (endToLua) and let the next
    -- wm_sfx re-initialise it; the few-ms open cost is nothing next to a
    -- permanently awake SoC.
    base.wm_sfx = function(s)
        local dir = self.base
        if not dir then return end
        local path = dir .. "sfx/" .. tostring(s) .. ".mp3"
        local ok, err = pcall(function()
            if not cc.FileUtils:getInstance():isFileExist(path) then
                print("[WFScript] wm_sfx: no such file " .. path)
                return
            end
            local AE = ax.AudioEngine
            local id = AE:play2d(path, false, 1.0)
            if not id or id < 0 then
                print("[WFScript] wm_sfx: play2d failed for " .. path)
                self:releaseAudio()          -- don't leave a half-open device
                return
            end
            self._activeSfx = (self._activeSfx or 0) + 1
            AE:setFinishCallback(id, function()
                self._activeSfx = math.max(0, (self._activeSfx or 1) - 1)
                if self._activeSfx == 0 then
                    -- Deferred one tick: end() from inside the finish
                    -- callback would destroy the engine while it iterates
                    -- its own callback list.
                    self:_releaseAudioNextTick()
                end
            end)
        end)
        if not ok then print("[WFScript] wm_sfx: " .. tostring(err)) end
    end
    -- UL semantics (WatchSkinUtils.wm_vibrate): duration in ms, repeat ignored
    base.wm_vibrate = function(durationMs)
        local ok = pcall(function()
            cc.Device:vibrate((tonumber(durationMs) or 100) / 1000)
        end)
        if not ok then print("[WFScript] wm_vibrate failed") end
    end
    -- present-but-inert in UL too; stubs keep faces that call them alive
    base.wm_transition = function() end
    base.wm_unschedule_all = function() self.tweens = {} end
    base.wm_schedule = function(args) self:schedule(args) end
    for name, fn in pairs(EASING) do base[name] = fn end

    -- `tweens.NAME` readable from script code (attributes use {tweens.NAME})
    base.tweens = setmetatable({}, {
        __index    = function(_, k) return eng.V["tweens." .. tostring(k)] end,
        __newindex = function(_, k, v) eng:publish("tweens." .. tostring(k), v) end,
    })
    -- {tweens.NAME} attributes may compile before the script ever schedules
    -- that tween — give them a 0-valued def instead of the unknown-tag path
    eng:definePrefix("tweens.", function(name)
        return { ev = "static", fn = function() return eng.V[name] or 0 end }
    end)

    -- the var-tracking proxy the script (and every attribute expression) runs in
    self.envProxy = setmetatable({}, {
        __index = function(_, k)
            local v = vars[k]
            if v ~= nil then return v end
            v = base[k]
            if v ~= nil then return v end
            return eng:resolve(k)      -- {tag} values as bare globals
        end,
        __newindex = function(_, k, v)
            vars[k] = v
            -- first write registers the def so later activations resolve it;
            -- publish() runs the update bucket when the tag is already live
            if not eng.extraDefs[k] then
                eng:define(k, "static", function() return vars[k] end)
            end
            eng:publish(k, v)
        end,
    })
    sandbox:setEnv(self.envProxy)
    return self
end

-- ── loading ───────────────────────────────────────────────────────────────────

-- Read and run scripts/script.txt from the face package. Runs the top-level
-- chunk once; variables it sets flow through the proxy into the engine BEFORE
-- the renderer compiles any layer expression (call order in wf_render:load).
function WFScript:load(baseDir)
    self.base = baseDir            -- wm_sfx resolves sfx/ against this
    local path = baseDir .. "scripts/script.txt"
    local fu = cc.FileUtils:getInstance()
    if not fu:isFileExist(path) then return false end
    local src = fu:getStringFromFile(path)
    if not src or #src == 0 then return false end
    print("[WFScript] loading scripts/script.txt (" .. #src .. " bytes)")
    if src:find("{", 1, true) then
        local spliced = spliceTags(src)
        if spliced ~= src then
            print("[WFScript] spliced {tag} references in script source")
            src = spliced
        end
    end
    local ok, err = self.sandbox:run(src, "=wf_script")
    if not ok then
        print("[WFScript] global script failed: " .. tostring(err))
        if WatchfaceBridge and WatchfaceBridge.reportError then
            WatchfaceBridge.reportError("Global script failed", err)
        end
        return false
    end
    self.hasScript = true
    return true
end

-- Run a `script:` tap-action body in the same environment (variables persist
-- and propagate exactly like global-script writes). Tag references get the
-- same splice as the global script.
function WFScript:runSnippet(src, label)
    if src:find("{", 1, true) then src = spliceTags(src) end
    local ok, err = self.sandbox:run(src, label or "=wf_tap")
    if not ok then print("[WFScript] snippet failed: " .. tostring(err)) end
    return ok
end

-- ── engine wiring ─────────────────────────────────────────────────────────────

-- Register the on_* callbacks and the tween driver as event listeners. Called
-- by the renderer after the script ran (callbacks exist in the shadow store).
function WFScript:attach()
    local eng, vars = self.engine, self.vars

    eng:listen("ms", function(clock)
        -- tween driver first so on_millisecond sees this frame's tween values
        if next(self.tweens) ~= nil then self:stepTweens(clock) end
        local cb = vars.on_millisecond
        if cb then
            local last = self._lastMsElapsed
            self._lastMsElapsed = clock.elapsed
            cb(last and (clock.elapsed - last) or 0)
        end
    end)
    eng:listen("second", function(clock)
        local cb = vars.on_second
        if cb then cb(clock.t.min, clock.t.sec) end
    end)
    eng:listen("minute", function()
        local cb = vars.on_minute
        if cb then cb() end
    end)
    eng:listen("hour", function()
        local cb = vars.on_hour
        if cb then cb() end
    end)
end

-- The frame loop must run when the script animates: an on_millisecond
-- callback or any live tween.
function WFScript:needsFrame()
    return self.vars.on_millisecond ~= nil or next(self.tweens) ~= nil
end

-- Scene hook: called when needsFrame() may have flipped after load (a tap
-- script scheduling the first tween while the frame loop is suspended).
function WFScript:onNeedFrameChanged(fn)
    self._needFrameChanged = fn
end

-- ── audio-device teardown ─────────────────────────────────────────────────────

-- Close the OpenAL device so its mixer thread and the audioserver wakelock go
-- away. Safe to call when audio was never initialised. Called when the last
-- sound finishes and on face switch (renderer clear()).
function WFScript:releaseAudio()
    self._activeSfx = 0
    local ok, err = pcall(function()
        ax.AudioEngine:stopAll()
        ax.AudioEngine:uncacheAll()
        ax.AudioEngine:endToLua()
    end)
    if ok then
        print("[WFScript] audio engine released")
    else
        print("[WFScript] releaseAudio: " .. tostring(err))
    end
end

function WFScript:_releaseAudioNextTick()
    if self._releasePending then return end
    self._releasePending = true
    local sched = cc.Director:getInstance():getScheduler()
    local entry
    entry = sched:scheduleScriptFunc(function()
        sched:unscheduleScriptEntry(entry)
        self._releasePending = false
        -- a new sound may have started in the meantime — keep the device then
        if (self._activeSfx or 0) == 0 then self:releaseAudio() end
    end, 0, false)
end

-- ── tweens (wm_schedule) ──────────────────────────────────────────────────────

-- wm_schedule{ tween = "name", from, to, duration, easing?, delay?, loop? }
function WFScript:schedule(args)
    if type(args) ~= "table" or not args.tween then return end
    local name = tostring(args.tween)
    local from, to = tonumber(args.from) or 0, tonumber(args.to) or 0
    local dur = tonumber(args.duration) or 1
    if dur <= 0 then dur = 0.001 end
    local wasIdle = next(self.tweens) == nil
    self.tweens[name] = {
        from = from, to = to, dur = dur,
        delay  = tonumber(args.delay) or 0,
        loop   = args.loop and true or false,
        easing = type(args.easing) == "function" and args.easing or EASING.linearTween,
        start  = self.engine.clock.elapsed,
    }
    self.engine:publish("tweens." .. name, from)
    if wasIdle and self._needFrameChanged then self._needFrameChanged() end
end

function WFScript:stepTweens(clock)
    local done = nil
    for name, tw in pairs(self.tweens) do
        local e = clock.elapsed - tw.start - tw.delay
        if e >= 0 then
            local p = e / tw.dur
            if tw.loop then
                p = p % 1
            elseif p >= 1 then
                p = 1
                done = done or {}
                done[#done + 1] = name
            end
            self.engine:publish("tweens." .. name, tw.from + (tw.to - tw.from) * tw.easing(p))
        end
    end
    if done then
        for _, name in ipairs(done) do self.tweens[name] = nil end
    end
end

return WFScript
