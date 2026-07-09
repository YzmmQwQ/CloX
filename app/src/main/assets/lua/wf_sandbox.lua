-- wf_sandbox.lua — WFSandbox: restricted execution environment for face code.
--
-- Everything that comes out of a .watch package — the global script
-- (scripts/script.txt), compiled attribute expressions, and `script:` tap
-- actions — runs inside ONE WFSandbox instance per loaded face. The sandbox is
-- a whitelist: face code sees private copies of the safe libraries and nothing
-- else. In particular it can never reach
--   • luaj / luaoc            (JNI — the escape hatch out of Lua into Java)
--   • cc / ax / ccui          (the cocos scene graph / engine internals)
--   • Bridge / WatchfaceBridge / WF   (launcher event bus + live-data tables)
--   • io, os.execute/remove/…, load, require, package, debug, _G
--   • setmetatable/getmetatable/raw* (would allow mutating the shared string
--     metatable or breaking out of proxy tables layered on the env)
--
-- The library tables are SHALLOW COPIES made per face: a script that does
-- `math.floor = nil` only breaks itself, never the launcher's math table.
--
-- Writes to the environment (script globals / var_* variables) land in the
-- env table itself; wf_script.lua layers a __newindex proxy on top to detect
-- variable changes and propagate them through the tag engine.

local WFSandbox = {}
WFSandbox.__index = WFSandbox

-- Copy only the whitelisted keys of a library table (nil list = every key).
local function copyLib(src, keys)
    local dst = {}
    if keys then
        for _, k in ipairs(keys) do dst[k] = src[k] end
    else
        for k, v in pairs(src) do dst[k] = v end
    end
    return dst
end

-- os is the only library where the default is DENY: just the clock/date
-- functions faces legitimately need. Everything else (execute, remove,
-- rename, exit, getenv, tmpname) stays out.
local OS_KEYS = { "time", "date", "clock", "difftime" }

-- string.format compat: faces target WatchMaker's LuaJIT/Lua 5.1, where an
-- integer conversion accepts any number ("%02d" with 6.8 prints "06" via the
-- C cast). Lua 5.4 raises "number has no integer representation" instead,
-- which silently kicks the whole attribute back to its template fallback —
-- the face renders the raw expression source. Truncate the numeric argument
-- of every integer conversion toward zero (the cast behaviour) first.
--
-- format() runs inside per-frame attribute expressions, so the hot path must
-- stay pattern-free: the format string is scanned ONCE with a plain byte walk
-- and the integer-conversion argument positions are memoised per face (the
-- cache lives in the wrapper closure and dies with the sandbox).
local sformat, sbyte = string.format, string.byte
local floor, ceil, huge = math.floor, math.ceil, math.huge
local FMT_FLAG = { [45]=true, [43]=true, [32]=true, [35]=true, [48]=true } -- - + space # 0
local INT_CONV = { [100]=true, [105]=true, [117]=true, [111]=true,        -- d i u o
                   [120]=true, [88]=true,  [99]=true }                    -- x X c
-- Argument positions of the integer conversions in fmt, or false when none.
local function scanIntArgs(fmt)
    local out, ai = nil, 0
    local i, n = 1, #fmt
    while i <= n do
        if sbyte(fmt, i) ~= 37 then                    -- '%'
            i = i + 1
        elseif sbyte(fmt, i + 1) == 37 then            -- literal %%
            i = i + 2
        else
            local j = i + 1
            while FMT_FLAG[sbyte(fmt, j)] do j = j + 1 end
            local b = sbyte(fmt, j)
            while b and b >= 48 and b <= 57 do j = j + 1; b = sbyte(fmt, j) end
            if b == 46 then                            -- '.' precision
                j = j + 1; b = sbyte(fmt, j)
                while b and b >= 48 and b <= 57 do j = j + 1; b = sbyte(fmt, j) end
            end
            if not b then break end                    -- malformed: sformat reports it
            ai = ai + 1
            if INT_CONV[b] then
                out = out or {}
                out[#out + 1] = ai
            end
            i = j + 1
        end
    end
    return out or false
end
local function newWmFormat()
    local cache = {}
    return function(fmt, ...)
        if type(fmt) ~= "string" then return sformat(fmt, ...) end
        local idx = cache[fmt]
        if idx == nil then idx = scanIntArgs(fmt); cache[fmt] = idx end
        if not idx then return sformat(fmt, ...) end
        local n = select("#", ...)
        local args = { ... }
        for k = 1, #idx do
            local v = tonumber(args[idx[k]])
            if v then
                if v ~= v or v == huge or v == -huge then
                    -- ±inf / nan (e.g. math.log(x/0) when a sensor tag reads 0
                    -- on hardware without that sensor): 5.4 "%d" throws, which
                    -- would dump the whole template as literal text. Render 0
                    -- instead — WM's C cast prints LONG_MIN garbage here, so
                    -- there is no meaningful value to be faithful to.
                    args[idx[k]] = 0
                elseif v % 1 ~= 0 then
                    args[idx[k]] = v >= 0 and floor(v) or ceil(v)
                end
            end
        end
        return sformat(fmt, table.unpack(args, 1, n))
    end
end

function WFSandbox.new()
    local self = setmetatable({}, WFSandbox)

    local env = {
        -- basics
        tonumber = tonumber, tostring = tostring, type = type,
        pairs = pairs, ipairs = ipairs, next = next, select = select,
        pcall = pcall, xpcall = xpcall, error = error, assert = assert,
        unpack = table.unpack,
        -- print goes to logcat like everything else; invaluable for face authors
        print = print,
        -- private library copies (see header)
        math   = copyLib(math),
        string = copyLib(string),
        table  = copyLib(table),
        os     = copyLib(os, OS_KEYS),
    }
    env.string.format = newWmFormat()   -- 5.1-style integer conversions (see above)
    env._G = env          -- self-reference stays inside the box
    self._env = env
    return self
end

-- The raw environment table. wf_script layers its var-tracking proxy over
-- this; wf_expr compiles chunks against it (or the proxy) directly.
function WFSandbox:env()
    return self._env
end

-- Swap the default compile environment. wf_script installs its var-tracking
-- proxy here so that BOTH the global script and every compiled attribute
-- expression see script variables as bare identifiers, with writes observed.
function WFSandbox:setEnv(env)
    self._env = env
end

-- Expose an extra symbol to face code (wm_action, wm_tag, easing fns, …).
function WFSandbox:expose(name, value)
    self._env[name] = value
end

-- Compile source text against an environment (defaults to the sandbox env).
-- Text-only chunks ("t"): precompiled bytecode from a face package is refused.
-- Returns fn or nil+err.
function WFSandbox:compile(src, chunkname, envOverride)
    return load(src, chunkname or "=wf_sandbox", "t", envOverride or self._env)
end

-- Compile + run protected. Returns ok, err-or-result.
function WFSandbox:run(src, chunkname, envOverride)
    local fn, err = self:compile(src, chunkname, envOverride)
    if not fn then return false, err end
    return pcall(fn)
end

return WFSandbox
