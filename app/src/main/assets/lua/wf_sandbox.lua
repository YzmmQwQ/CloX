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
