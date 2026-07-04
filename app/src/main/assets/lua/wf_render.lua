-- wf_render.lua — Watchmaker (.watch / JBwatch) renderer for Axmol/Cocos2d-x.
--
-- Parses a watch.xml face definition (format 1.0 flat layers and 2.0 Studio
-- groups/themes) and builds live cocos nodes for it. See the format reference in
-- watchmaker-definition/ (WM_FORMAT_1.0.md, WM_FORMAT_2.0.md, WM_TAGS.md).
--
-- Coordinate model: the watch canvas is 512×512, origin at the centre. Authored
-- layer positions are screen-down (positive Y = toward the bottom); the renderer
-- flips them into cocos Y-up at the single `Y_SIGN` site below. The caller
-- supplies a `stage` cc.Node already positioned at screen centre and scaled so
-- 512 units fill the watch — so this module works purely in canvas units and
-- never touches screen pixels.
--
-- Layout per layer:  holder (carries x/y, rotation, anim_scale, opacity, skew)
--                    └─ visual (the sprite/label/draw-node, base-scaled to fit)
-- Groups (2.0) are holders with no visual; children attach to them, so nested
-- transforms fall out of the cocos scene graph for free.

local WFTags    = require "wf_tags"
local WFProtect = require "wf_protect"
local WFCadence = require "wf_cadence"

local WFRender = {}
WFRender.__index = WFRender

local floor, rad, cos, sin = math.floor, math.rad, math.cos, math.sin
local fu = cc.FileUtils:getInstance()
local function fileExists(p) return fu:isFileExist(p) end

-- On-screen Y sign for authored layer POSITIONS. WM_FORMAT_1.0 §4 documents
-- Y-up (+1), but real faces render vertically inverted on device — content the
-- designer placed at the bottom lands at the top — i.e. the format's authored Y
-- is screen-down. So a layer's authored (x, y) becomes cocos (x, Y_SIGN*y). This
-- is the ONE place format coordinates enter the cocos scene; flip to +1 to revert.
--
-- Only holder positions are negated. Per-layer geometry (markers, rings, hands,
-- text arcs) is procedural and already authored in cocos Y-up (12 o'clock = +Y),
-- so it is NOT touched — negating it would send 12 o'clock to the bottom and make
-- hands point down. Visuals are never mirrored, so glyphs/sprites stay upright.
local Y_SIGN = -1
local function num(v, d) return tonumber(v) or d or 0 end
local function clamp(v, lo, hi) if v < lo then return lo elseif v > hi then return hi else return v end end

-- WatchMaker's `text_size` is authored as a canvas-unit box height, not a native
-- font point size. The reference renderers create labels at text_size * 1.28 (so
-- the visible glyph height lands near 1.6 * text_size in the 512 canvas). Without
-- this factor TTF text renders ~28% too small next to shapes authored in the same
-- canvas. Clamp near the 512 canvas / max font-texture size like the reference.
local TTF_FONT_SCALE = 1.28
local function fontPx(size) return math.min(492, floor(size * TTF_FONT_SCALE)) end

local FRAME, SECOND, MINUTE, HOUR, DAY, STATIC =
    WFCadence.FRAME, WFCadence.SECOND, WFCadence.MINUTE,
    WFCadence.HOUR, WFCadence.DAY, WFCadence.STATIC

-- Attributes scanned for {tags} to decide a layer's update cadence + data needs.
local SCAN_ATTRS = {
    "text", "rotation", "x", "y", "opacity", "angle", "angle_total",
    "pct_complete", "cond_value", "cond_grid", "color", "color2", "color3",
    "color_dim", "anim_scale_x", "anim_scale_y", "radius", "radius_inner",
    "radius_outer", "margin", "path", "paths", "skew_x", "skew_y",
}

-- Map a wf_cadence async category to the WF.* bridge category Android pushes.
local ASYNC_BRIDGE = {
    ["weather"]               = "weather",
    ["health:steps"]          = "health_steps",
    ["health:heartRate"]      = "health_heartRate",
    ["health:spo2"]           = "health_spo2",
    ["sensor:accelerometer"]  = "sensor_accelerometer",
    ["sensor:gyroscope"]      = "sensor_gyroscope",
    ["sensor:compass"]        = "sensor_compass",
    ["sensor:barometer"]      = "sensor_barometer",
    ["battery"]               = "battery",
    ["calendar"]              = "calendar",
    ["notification"]          = "notification",
    ["network"]               = "network",
    ["connectivity"]          = "connectivity",
    ["system"]                = "system",
    ["storage"]               = "storage",
    ["alarm"]                 = "alarm",
    ["location"]              = "location",
    ["timezone"]              = "timezones",
}

-- ── colour helpers ────────────────────────────────────────────────────────────

local function hexParts(hex)
    hex = tostring(hex or "ffffff"):gsub("#", "")
    if #hex < 6 then hex = "ffffff" end
    return tonumber(hex:sub(1, 2), 16) or 255,
           tonumber(hex:sub(3, 4), 16) or 255,
           tonumber(hex:sub(5, 6), 16) or 255
end
local function c3b(hex) local r, g, b = hexParts(hex); return cc.c3b(r, g, b) end
local function c4b(hex, op)
    local r, g, b = hexParts(hex)
    return cc.c4b(r, g, b, floor((op or 100) * 2.55))
end
local function c4f(hex, op)
    local r, g, b = hexParts(hex)
    return cc.c4f(r / 255, g / 255, b / 255, (op or 100) / 100)
end

-- ── DrawNode fill helpers (only proven-available primitives) ──────────────────

local function fillQuad(dn, p1, p2, p3, p4, col)
    dn:drawTriangle(p1, p2, p3, col)
    dn:drawTriangle(p1, p3, p4, col)
end

-- Filled annular arc, swept from 12 o'clock. a0,a1 are sweep degrees (a1>a0).
local function fillArc(dn, ri, ro, a0, a1, cw, col)
    if a1 <= a0 then return end
    local steps = math.max(1, floor((a1 - a0) / 3))
    local function pt(sweep, r)
        -- 12 o'clock = +Y (90°); clockwise sweep decreases the polar angle
        local th = cw and rad(90 - sweep) or rad(90 + sweep)
        return cc.p(r * cos(th), r * sin(th))
    end
    for i = 0, steps - 1 do
        local s0 = a0 + (a1 - a0) * i / steps
        local s1 = a0 + (a1 - a0) * (i + 1) / steps
        fillQuad(dn, pt(s0, ri), pt(s0, ro), pt(s1, ro), pt(s1, ri), col)
    end
end

-- Regular n-gon (optionally rotated), scaled to a w×h box, filled as a fan.
local function fillPolygon(dn, w, h, n, rot0, col)
    local rx, ry = w / 2, h / 2
    local prev
    local first
    for i = 0, n do
        local a = rot0 + i / n * 2 * math.pi
        local p = cc.p(rx * cos(a), ry * sin(a))
        if i == 0 then first = p
        elseif prev then dn:drawTriangle(cc.p(0, 0), prev, p, col) end
        prev = p
    end
end

-- ── module ────────────────────────────────────────────────────────────────────

function WFRender.new(stage)
    local self = setmetatable({}, WFRender)
    self.stage  = stage
    self.recs   = {}          -- every layer record
    self.dyn    = {}          -- subset needing per-frame updates
    self.taps   = {}          -- {visual, action, kind, rec}
    self._tapListeners = {}   -- registered touch listeners, dropped on clear()
    self.dim    = false
    self.theme  = { ucolor = "ffffff", ucolor2 = "ffffff", ucolor3 = "ffffff" }
    self.bgColor = "000000"
    self.scene  = nil         -- for touch dispatch registration
    return self
end

function WFRender:clear()
    -- Drop bridge listeners from the previous face, or stale closures would keep
    -- firing into freed layer records after a switch.
    if self._asyncHandlers and WatchfaceBridge and WatchfaceBridge.offChange then
        for bridgeCat, fn in pairs(self._asyncHandlers) do
            WatchfaceBridge.offChange(bridgeCat, fn)
        end
    end
    self._asyncHandlers = {}
    -- Drop tap listeners before the nodes go away, so no stale scene-graph
    -- listener can fire into a freed layer record after a switch.
    if self._tapListeners then
        local ed = cc.Director:getInstance():getEventDispatcher()
        for _, l in ipairs(self._tapListeners) do ed:removeEventListener(l) end
    end
    self._tapListeners = {}
    if self.stage then self.stage:removeAllChildren() end
    self.recs, self.dyn, self.taps = {}, {}, {}
    self.byCadence, self.byAsync, self.asyncCats = nil, nil, nil
    self.ctx, self._lastClock = nil, nil
    self.needFrame, self.needSecond = false, false
end

-- Resolve an image filename to a loadable absolute path. Protected faces ship
-- images as .ppng / .pjpg (XOR-obfuscated PNG / JPEG); decode them to a sibling
-- .png / .jpg on first use (cached) so cc.Sprite can load them.
function WFRender:imageFile(path)
    if not path or path == "" then return "" end
    local dir = self.base .. "images/"
    local ext = path:sub(-5):lower()
    if ext == ".ppng" then
        local png = dir .. path:sub(1, -6) .. ".png"
        if fileExists(png) or WFProtect.ensureDecoded(dir .. path, png) then return png end
    elseif ext == ".pjpg" then
        local jpg = dir .. path:sub(1, -6) .. ".jpg"
        if fileExists(jpg) or WFProtect.ensureDecoded(dir .. path, jpg) then return jpg end
    end
    local full = dir .. path
    if fileExists(full) then return full end
    -- Fall back to the FileUtils search paths (bundled assets) when the face's
    -- own images/ dir has no such file — lets the built-in default face load its
    -- background straight from assets (e.g. "wf_default/bg.png").
    if fileExists(path) then return path end
    return full
end

-- Build a per-frame tag context with the active theme colours overlaid.
function WFRender:buildContext(clock)
    local T = WFTags.build(clock)
    T.ucolor   = self.theme.ucolor
    T.ucolor2  = self.theme.ucolor2
    T.ucolor3  = self.theme.ucolor3
    T.ucolor_b = self.theme.ucolor
    return T
end

-- ── XML parsing ───────────────────────────────────────────────────────────────

-- Parse key="value" / key='value' attributes. Quote-agnostic: some exporters
-- emit single-quoted attributes, and a double-quote-only parser would drop every
-- attribute (so each layer loses its `type` and gets skipped → 0 layers).
local function parseAttrs(s)
    local a = {}
    for k, _, v in s:gmatch("([%w_]+)%s*=%s*([\"'])(.-)%2") do a[k] = v end
    return a
end

-- Returns watchAttrs, themeSets(list), layers(list of attr tables in doc order).
local function parseXml(xml)
    local watch = parseAttrs(xml:match("<Watch(.-)>") or "")
    local themes = {}
    for body in xml:gmatch("<ThemeSet(.-)>") do
        local a = parseAttrs(body)
        if a.ucolor then themes[#themes + 1] = a end
    end
    local layers = {}
    for body in xml:gmatch("<Layer(.-)>") do
        local a = parseAttrs(body)
        if a.type then layers[#layers + 1] = a end
    end
    return watch, themes, layers
end

-- ── visibility (display = b / d / bd) ─────────────────────────────────────────

local function visibleIn(display, dim)
    if not display or display == "" or display == "bd" then return true end
    if dim then return display:find("d") ~= nil else return display:find("b") ~= nil end
end

-- ── per-layer builders ────────────────────────────────────────────────────────
-- Each builder creates rec.visual (or leaves nil for groups), sets rec.bsx/bsy
-- (intrinsic base scale to fit width/height) and optionally rec.update(ctx) for
-- dynamic content. Transform/opacity are applied generically afterwards.

local Builders = {}

function Builders.group(self, rec) end   -- container only; no visual

function Builders.image(self, rec)
    local L = rec.L
    local sp = cc.Sprite:create(self:imageFile(L.path))
    if not sp then return end
    rec.visual = sp
    local cs = sp:getContentSize()
    if cs.width > 0 then rec.bsx = num(L.width, cs.width) / cs.width end
    if cs.height > 0 then rec.bsy = num(L.height, cs.height) / cs.height end
    rec.update = function(ctx)
        sp:setColor(c3b(WFTags.sub(L.color or "ffffff", ctx)))
    end
end

Builders.gif = Builders.image   -- native gif: render first frame statically

function Builders.image_tap(self, rec)
    local L = rec.L
    local frames = {}
    for f in (L.paths or ""):gmatch("[^`]+") do frames[#frames + 1] = f end
    rec.frames, rec.frameIdx = frames, 1
    local sp = frames[1] and cc.Sprite:create(self:imageFile(frames[1]))
    if not sp then return end
    rec.visual = sp
    local cs = sp:getContentSize()
    if cs.width > 0 then rec.bsx = num(L.width, cs.width) / cs.width end
    if cs.height > 0 then rec.bsy = num(L.height, cs.height) / cs.height end
    rec.tap = function()
        rec.frameIdx = (rec.frameIdx % #frames) + 1
        sp:setTexture(self:imageFile(frames[rec.frameIdx]))
    end
end

function Builders.image_gif(self, rec)
    local L = rec.L
    local frames = {}
    for f in (L.path or ""):gmatch("[^`]+") do frames[#frames + 1] = f end
    if #frames == 0 then return end
    rec.frames = frames
    rec.delay = math.max(0.02, num(L.gif_delay, 50) / 1000)
    local sp = cc.Sprite:create(self:imageFile(frames[1]))
    if not sp then return end
    rec.visual = sp
    local cs = sp:getContentSize()
    if cs.width > 0 then rec.bsx = num(L.width, cs.width) / cs.width end
    if cs.height > 0 then rec.bsy = num(L.height, cs.height) / cs.height end
    rec.lastFrame = -1
    rec.update = function(ctx)
        local fi = floor((ctx._elapsed or 0) / rec.delay) % #frames + 1
        if fi ~= rec.lastFrame then
            rec.lastFrame = fi
            sp:setTexture(self:imageFile(frames[fi]))
        end
    end
end

function Builders.text(self, rec)
    local L = rec.L
    local size = math.max(1, floor(num(L.text_size, 20)))
    local font = L.font or ""
    local function makeLabel(str)
        local lab
        if font:sub(1, 3) == "bm:" then
            local fnt = self.base .. "fonts_bm/" .. font:sub(4) .. ".fnt"
            if fileExists(fnt) then lab = cc.Label:createWithBMFont(fnt, str) end
            if lab then rec.isBM = true end
        end
        if not lab and font ~= "" then
            local ttf = self.base .. "fonts/" .. font .. ".ttf"
            if fileExists(ttf) then lab = cc.Label:createWithTTF(str, ttf, fontPx(size)) end
        end
        if not lab then lab = cc.Label:createWithSystemFont(str, "sans-serif", fontPx(size)) end
        return lab
    end
    local function caseOf(str)
        if L.transform == "uc" then return str:upper()
        elseif L.transform == "lc" then return str:lower() end
        return str
    end
    local lab = makeLabel(caseOf(WFTags.evalText(L.text, rec.ctx0)))
    rec.visual = lab
    lab:setHorizontalAlignment(cc.TEXT_ALIGNMENT_CENTER)
    -- Bitmap fonts render at their atlas size; scale so a line's visible height
    -- lands near 1.6 * text_size, matching the reference renderer's bitmap rule.
    if rec.isBM then
        local ch = lab:getContentSize().height
        local lines = 1; for _ in (L.text or ""):gmatch("\n") do lines = lines + 1 end
        local perLine = (ch > 0 and ch / lines) or size
        rec.bsx = 1.6 * size / perLine; rec.bsy = rec.bsx
    end
    rec.update = function(ctx)
        lab:setString(caseOf(WFTags.evalText(L.text, ctx)))
        local col = self.dim and (L.color_dim or L.color) or L.color
        lab:setTextColor(c4b(WFTags.sub(col or "ffffff", ctx),
                             WFTags.evalNumber(L.opacity, ctx, 100)))
    end
end

function Builders.image_cond(self, rec)
    local L = rec.L
    local sp = cc.Sprite:create(self:imageFile(L.path))
    if not sp then return end
    rec.visual = sp
    local cs = sp:getContentSize()
    local cols, rows = (L.cond_grid or "1x1"):match("(%d+)x(%d+)")
    cols, rows = num(cols, 1), num(rows, 1)
    local cw, chh = cs.width / cols, cs.height / rows
    if cw > 0 then rec.bsx = num(L.width, cw) / cw end
    if chh > 0 then rec.bsy = num(L.height, chh) / chh end
    rec.lastIdx = -1
    rec.update = function(ctx)
        local idx = clamp(floor(WFTags.evalNumber(L.cond_value, ctx, 1)) - 1, 0, cols * rows - 1)
        if idx ~= rec.lastIdx then
            rec.lastIdx = idx
            local col, row = idx % cols, floor(idx / cols)   -- row-major, top-left
            sp:setTextureRect(cc.rect(col * cw, row * chh, cw, chh))
        end
        sp:setColor(c3b(WFTags.sub(L.color or "ffffff", ctx)))
    end
end

function Builders.shape(self, rec)
    local L = rec.L
    -- 2.0 shapes may ship an image mask; prefer it when present.
    local shapeImg = L.path and L.path ~= "" and self:imageFile(L.path)
    if shapeImg and fileExists(shapeImg) then
        local sp = cc.Sprite:create(shapeImg)
        if sp then
            rec.visual = sp
            local cs = sp:getContentSize()
            if cs.width > 0 then rec.bsx = num(L.width, cs.width) / cs.width end
            if cs.height > 0 then rec.bsy = num(L.height, cs.height) / cs.height end
            rec.update = function(ctx) sp:setColor(c3b(WFTags.sub(L.color or "ffffff", ctx))) end
            return
        end
    end
    -- Otherwise draw the primitive with a DrawNode.
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local w, h = num(L.width, 50), num(L.height, 50)
    rec.shapeW, rec.shapeH = w, h
    local shp = (L.shape or "Square"):lower()
    rec.update = function(ctx)
        dn:clear()
        local col = c4f(WFTags.sub(L.color or "ffffff", ctx), WFTags.evalNumber(L.opacity, ctx, 100))
        if shp == "circle" then
            dn:drawSolidCircle(cc.p(0, 0), w / 2, 0, 48, 1, h / w, col)
        elseif shp == "triangle" then
            dn:drawTriangle(cc.p(-w / 2, -h / 2), cc.p(w / 2, -h / 2), cc.p(0, h / 2), col)
        elseif shp == "pentagon" then
            fillPolygon(dn, w, h, 5, math.pi / 2, col)
        elseif shp == "hexagon" then
            fillPolygon(dn, w, h, 6, 0, col)
        elseif shp == "star" then
            -- 10-point star: alternate outer/inner radius
            local prev, rx, ry = nil, w / 2, h / 2
            for i = 0, 10 do
                local a = math.pi / 2 + i / 10 * 2 * math.pi
                local rr = (i % 2 == 0) and 1 or 0.45
                local p = cc.p(rx * rr * cos(a), ry * rr * sin(a))
                if prev then dn:drawTriangle(cc.p(0, 0), prev, p, col) end
                prev = p
            end
        else   -- Square / default / Heart fallback
            dn:drawSolidRect(cc.p(-w / 2, -h / 2), cc.p(w / 2, h / 2), col)
        end
    end
end

function Builders.rounded(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local w, h = num(L.width, 100), num(L.height, 60)
    rec.shapeW, rec.shapeH = w, h
    local r = math.min(num(L.radius, 0), w / 2, h / 2)
    local ct = floor(num(L.corner_type, 1))
    -- corner_type → which corners round (see WM_FORMAT_1.0 §7.12)
    local CORNERS = {
        [0]={0,0,0,0},[1]={1,1,1,1},[2]={1,1,1,0},[3]={1,1,0,1},[4]={1,0,1,1},
        [5]={0,1,1,1},[6]={1,1,0,0},[7]={0,0,1,1},[8]={1,0,1,0},[9]={0,1,0,1},
        [10]={1,0,0,1},[11]={0,1,1,0},[12]={1,0,0,0},[13]={0,1,0,0},
        [14]={0,0,1,0},[15]={0,0,0,1},[16]={0,0,0,0},
    }
    local cor = CORNERS[ct] or CORNERS[1]   -- {TL,TR,BL,BR}
    rec.update = function(ctx)
        dn:clear()
        local col = c4f(WFTags.sub(L.color or "ffffff", ctx), WFTags.evalNumber(L.opacity, ctx, 100))
        local hw, hh = w / 2, h / 2
        if r <= 0 then dn:drawSolidRect(cc.p(-hw, -hh), cc.p(hw, hh), col); return end
        -- centre cross fills everything except the four corner squares
        dn:drawSolidRect(cc.p(-hw + r, -hh), cc.p(hw - r, hh), col)
        dn:drawSolidRect(cc.p(-hw, -hh + r), cc.p(hw, hh - r), col)
        local function corner(cx, cy, rounded, q)
            if rounded == 1 then
                dn:drawSolidCircle(cc.p(cx, cy), r, 0, 24, 1, 1, col)
            else
                local sx = (q == 2 or q == 4) and 1 or -1   -- toward outer x
                local sy = (q >= 3) and -1 or 1
                dn:drawSolidRect(cc.p(cx, cy), cc.p(cx + sx * r, cy + sy * r), col)
            end
        end
        corner(-hw + r,  hh - r, cor[1], 1)   -- TL
        corner( hw - r,  hh - r, cor[2], 2)   -- TR
        corner(-hw + r, -hh + r, cor[3], 3)   -- BL
        corner( hw - r, -hh + r, cor[4], 4)   -- BR
    end
end

function Builders.ring(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local ro = num(L.radius_outer, 100)
    local ri = num(L.radius_inner, ro * 0.8)
    local cw = (L.is_clockwise or "Y") ~= "N"
    rec.update = function(ctx)
        dn:clear()
        local total = num(L.angle_total, 360)
        local ang = clamp(WFTags.evalNumber(L.angle, ctx, 0), 0, total)
        local outOp = num(L.outside_opacity, 100)
        local opacity = WFTags.evalNumber(L.opacity, ctx, 100)
        -- outside band (beyond angle_total) with color3
        if total < 360 and L.color3 then
            fillArc(dn, ri, ro, total, 360, cw, c4f(WFTags.sub(L.color3, ctx), outOp * opacity / 100))
        end
        -- unfilled track [ang..total] with color2
        if L.color2 then
            fillArc(dn, ri, ro, ang, total, cw, c4f(WFTags.sub(L.color2, ctx), outOp * opacity / 100))
        end
        -- filled [0..ang] with color
        fillArc(dn, ri, ro, 0, ang, cw, c4f(WFTags.sub(L.color or "ffffff", ctx), opacity))
    end
end

function Builders.progress(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local w, h = num(L.width, 120), num(L.height, 24)
    rec.shapeW, rec.shapeH = w, h
    rec.rotOffset = -num(L.angle, 0)   -- angle 0=horizontal,90=vertical-up
    local margin = num(L.margin, 0)
    rec.update = function(ctx)
        dn:clear()
        local pct = clamp(WFTags.evalNumber(L.pct_complete, ctx, 0), 0, 100)
        local opacity = WFTags.evalNumber(L.opacity, ctx, 100)
        local outOp = num(L.outside_opacity, 100)
        local hw, hh = w / 2, h / 2
        -- track
        dn:drawSolidRect(cc.p(-hw, -hh), cc.p(hw, hh),
            c4f(WFTags.sub(L.color3 or "444444", ctx), outOp * opacity / 100))
        -- fill
        local innerW = w - margin * 2
        local fillW = innerW * pct / 100
        if fillW > 0 then
            dn:drawSolidRect(cc.p(-hw + margin, -hh + margin),
                cc.p(-hw + margin + fillW, hh - margin),
                c4f(WFTags.sub(L.color or "ffffff", ctx), opacity))
        end
    end
end

-- squarify morph: stretch the placement radius toward a square outline
local function squarifyRadius(r, angle, sq)
    if sq <= 0 then return r end
    local f = sq / 100
    local c, s = math.abs(cos(angle)), math.abs(sin(angle))
    local m = math.max(c, s)
    if m < 1e-4 then return r end
    return r * ((1 - f) + f / m)
end

function Builders.markers(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local count = math.max(1, floor(num(L.m_count, 12)))
    local radius = num(L.radius, 200)
    local mw, mh = num(L.m_width, 10), num(L.m_height, 30)
    local shp = (L.shape or "Square"):lower()
    local sq = num(L.squarify, 0)
    rec.update = function(ctx)
        dn:clear()
        local col = c4f(WFTags.sub(L.color or "ffffff", ctx), WFTags.evalNumber(L.opacity, ctx, 100))
        for i = 0, count - 1 do
            local th = rad(90 - i * 360 / count)     -- clockwise from 12
            local rr = squarifyRadius(radius - mh / 2, th, sq)
            local cx, cy = rr * cos(th), rr * sin(th)
            local ru = cc.p(cos(th), sin(th))        -- radial unit
            local tu = cc.p(-sin(th), cos(th))       -- tangential unit
            if shp == "circle" then
                dn:drawSolidCircle(cc.p(cx, cy), mw / 2, 0, 16, 1, 1, col)
            elseif shp == "triangle" then
                local tip = cc.p(cx + ru.x * mh / 2, cy + ru.y * mh / 2)
                local b1  = cc.p(cx - ru.x * mh / 2 + tu.x * mw / 2, cy - ru.y * mh / 2 + tu.y * mw / 2)
                local b2  = cc.p(cx - ru.x * mh / 2 - tu.x * mw / 2, cy - ru.y * mh / 2 - tu.y * mw / 2)
                dn:drawTriangle(tip, b1, b2, col)
            else   -- square: rect oriented radially
                local p1 = cc.p(cx + ru.x * mh / 2 + tu.x * mw / 2, cy + ru.y * mh / 2 + tu.y * mw / 2)
                local p2 = cc.p(cx + ru.x * mh / 2 - tu.x * mw / 2, cy + ru.y * mh / 2 - tu.y * mw / 2)
                local p3 = cc.p(cx - ru.x * mh / 2 - tu.x * mw / 2, cy - ru.y * mh / 2 - tu.y * mw / 2)
                local p4 = cc.p(cx - ru.x * mh / 2 + tu.x * mw / 2, cy - ru.y * mh / 2 + tu.y * mw / 2)
                fillQuad(dn, p1, p2, p3, p4, col)
            end
        end
    end
end

function Builders.markers_hm(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local radius = num(L.radius, 240)
    local SZ = { None = 0, Tiny = 1, Small = 2, Medium = 3, Large = 4 }
    local hsz = SZ[L.m_hour or "Medium"] or 3
    local msz = SZ[L.m_minute or "Medium"] or 3
    local sq = num(L.squarify, 0)
    local function tick(center, th, len, wdt, col)
        local ru = cc.p(cos(th), sin(th))
        local tu = cc.p(-sin(th), cos(th))
        local outer = squarifyRadius(center, th, sq)
        local inner = outer - len
        local ox, oy = outer * cos(th), outer * sin(th)
        local ix, iy = inner * cos(th), inner * sin(th)
        fillQuad(dn,
            cc.p(ox + tu.x * wdt / 2, oy + tu.y * wdt / 2),
            cc.p(ox - tu.x * wdt / 2, oy - tu.y * wdt / 2),
            cc.p(ix - tu.x * wdt / 2, iy - tu.y * wdt / 2),
            cc.p(ix + tu.x * wdt / 2, iy + tu.y * wdt / 2), col)
    end
    rec.update = function(ctx)
        dn:clear()
        local opacity = WFTags.evalNumber(L.opacity, ctx, 100)
        local colM = c4f(WFTags.sub(L.color2 or L.color or "ffffff", ctx), opacity)
        local colH = c4f(WFTags.sub(L.color or "ffffff", ctx), opacity)
        if msz > 0 then
            for i = 0, 59 do
                if i % 5 ~= 0 then   -- skip where hour ticks sit
                    tick(radius, rad(90 - i * 6), msz * 3, msz * 0.9, colM)
                end
            end
        end
        if hsz > 0 then
            for i = 0, 11 do
                tick(radius, rad(90 - i * 30), hsz * 6, hsz * 1.8, colH)
            end
        end
    end
end

-- Ring of text labels (clock numerals). Built once; numerals are static.
function Builders.text_ring(self, rec)
    local L = rec.L
    local node = cc.Node:create()
    rec.visual = node
    local radius = num(L.radius, 200)
    local size = math.max(1, floor(num(L.text_size, 24)))
    local font = L.font or ""
    local a0, a1 = num(L.angle_start, 0), num(L.angle_end, 360)
    local rotated = (L.rotated_text == "y")
    local sq = num(L.squarify, 0)
    local showEvery = math.max(1, floor(num(L.show_every, 1)))
    local hide = {}
    for h in (L.hide_text or ""):gmatch("%d+") do hide[tonumber(h)] = true end

    -- numeral set
    local labels = {}
    local rt = L.ring_type or "1-12"
    if rt == "0-59" then
        for i = 0, 59 do labels[#labels + 1] = tostring(i) end
    elseif rt == "Mon-Sun" then
        labels = { "Mon","Tue","Wed","Thu","Fri","Sat","Sun" }
    elseif rt == "I-XII" then
        labels = { "I","II","III","IV","V","VI","VII","VIII","IX","X","XI","XII" }
    else   -- default 1-12
        for i = 1, 12 do labels[#labels + 1] = tostring(i) end
    end
    local n = #labels
    local function mkLabel(str)
        local lab
        if font ~= "" then
            local ttf = self.base .. "fonts/" .. font .. ".ttf"
            if fileExists(ttf) then lab = cc.Label:createWithTTF(str, ttf, fontPx(size)) end
        end
        return lab or cc.Label:createWithSystemFont(str, "sans-serif", fontPx(size))
    end
    for i = 1, n do
        local loopVal = (rt == "0-59") and (i - 1) or i
        if (i - 1) % showEvery == 0 and not hide[loopVal] then
            local frac = (n > 1) and ((i - 1) / n) or 0
            local sweep = a0 + (a1 - a0) * frac
            local th = rad(90 - sweep)
            local rr = squarifyRadius(radius, th, sq)
            local lab = mkLabel(labels[i])
            lab:setTextColor(c4b(L.color or "ffffff", 100))
            lab:setPosition(rr * cos(th), rr * sin(th))
            if rotated then lab:setRotation(sweep) end
            node:addChild(lab)
        end
    end
end

-- Text along a circular arc. Static content (no tag re-eval per frame).
function Builders.text_curved(self, rec)
    local L = rec.L
    local node = cc.Node:create()
    rec.visual = node
    local radius = num(L.radius, 200)
    local size = math.max(1, floor(num(L.text_size, 30)))
    local font = L.font or ""
    local up = (L.curve_dir or "Up") ~= "Down"
    local str = WFTags.evalText(L.text, rec.ctx0)
    if L.transform == "uc" then str = str:upper() elseif L.transform == "lc" then str = str:lower() end
    local function mkLabel(s)
        local lab
        if font ~= "" then
            local ttf = self.base .. "fonts/" .. font .. ".ttf"
            if fileExists(ttf) then lab = cc.Label:createWithTTF(s, ttf, fontPx(size)) end
        end
        return lab or cc.Label:createWithSystemFont(s, "sans-serif", fontPx(size))
    end
    local nchar = #str
    if nchar == 0 then return end
    local span = math.min(160, nchar * (size * 0.9 / radius) * 57.3)  -- rough degrees
    local startA = -span / 2
    for i = 1, nchar do
        local ch = str:sub(i, i)
        local lab = mkLabel(ch)
        lab:setTextColor(c4b(L.color or "ffffff", 100))
        local frac = (nchar > 1) and ((i - 1) / (nchar - 1)) or 0.5
        local sweep = startA + span * frac
        local sign = up and 1 or -1
        local th = rad(90 - sweep * sign)
        lab:setPosition(radius * cos(th), radius * sin(th))
        lab:setRotation(sweep * sign)
        node:addChild(lab)
    end
end

-- ── tap handling ──────────────────────────────────────────────────────────────

function WFRender:dispatchTap(rec)
    local action = rec.L.tap_action
    if rec.tap then rec.tap() end          -- image_tap frame advance
    if not action or action == "" then return end
    if action:sub(1, 7) == "script:" then
        local code = WFTags.unescape(action:sub(8))
        local chunk = load(code, "=wf_tap", "t",
            setmetatable({ WF = WF, WatchfaceBridge = WatchfaceBridge, print = print,
                           math = math, string = string }, nil))
        if chunk then pcall(chunk) end
    elseif WatchfaceBridge and WatchfaceBridge.tapAction then
        WatchfaceBridge.tapAction(action)
    end
end

local function attachTap(self, rec)
    local node = rec.visual
    if not node then return end
    local listener = cc.EventListenerTouchOneByOne:create()
    listener:setSwallowTouches(false)
    listener:registerScriptHandler(function(touch)
        local p = node:convertToNodeSpace(touch:getLocation())
        local s = node:getContentSize()
        if s.width <= 0 then return false end
        return cc.rectContainsPoint(cc.rect(0, 0, s.width, s.height), p)
    end, cc.Handler.EVENT_TOUCH_BEGAN)
    listener:registerScriptHandler(function()
        self:dispatchTap(rec)
    end, cc.Handler.EVENT_TOUCH_ENDED)
    cc.Director:getInstance():getEventDispatcher()
        :addEventListenerWithSceneGraphPriority(listener, node)
    -- Tracked so clear() can drop it on the next face switch (see clear()).
    self._tapListeners[#self._tapListeners + 1] = listener
end

-- ── load & build ──────────────────────────────────────────────────────────────

-- xml: watch.xml content. baseDir: extract dir ending in "/" (has images/ etc).
function WFRender:load(xml, baseDir)
    self:clear()
    self.base = baseDir
    local watch, themes, layers = parseXml(xml)
    self.watch = watch
    self.bgColor = watch.bg_color or "000000"

    -- diagnostics: raw tag occurrences vs successfully parsed, plus xml head
    local rawLayers = select(2, xml:gsub("<[Ll]ayer", ""))
    local nWatchAttrs = 0; for _ in pairs(watch) do nWatchAttrs = nWatchAttrs + 1 end
    print(string.format("[WFRender] xml=%d bytes, raw<Layer>=%d, parsed=%d, watchAttrs=%d, head=%s",
        #xml, rawLayers, #layers, nWatchAttrs, (xml:gsub("[\r\n]", " ")):sub(1, 110)))

    -- theme: first ThemeSet, or fall back to ucolor_default
    if themes[1] then
        self.theme = { ucolor = themes[1].ucolor, ucolor2 = themes[1].ucolor2 or themes[1].ucolor,
                       ucolor3 = themes[1].ucolor3 or themes[1].ucolor }
    else
        local u = watch.ucolor_default or "ffffff"
        self.theme = { ucolor = u, ucolor2 = u, ucolor3 = u }
    end
    self.themes = themes

    local ctx0 = self:buildContext({ t = os.date("*t"), subsec = 0, elapsed = 0 })

    -- Pass 1: create a holder per layer, build its visual.
    local holders = {}            -- key → holder node
    local recsByKey = {}
    for i, L in ipairs(layers) do
        local key = (L.id and L.id ~= "" and L.id) or ("__" .. i)
        local holder = cc.Node:create()
        holder:setCascadeOpacityEnabled(true)
        local rec = { L = L, holder = holder, type = L.type, key = key,
                      bsx = 1, bsy = 1, ctx0 = ctx0, index = i }
        local builder = Builders[L.type]
        if builder then
            local ok, err = pcall(builder, self, rec)
            if not ok then print("[WFRender] build " .. tostring(L.type) .. ": " .. tostring(err)) end
        end
        if rec.visual then
            rec.visual:setCascadeOpacityEnabled(true)
            local al = L.alignment
            local AP = { cc=cc.p(.5,.5), tl=cc.p(0,1), tc=cc.p(.5,1), tr=cc.p(1,1),
                         cl=cc.p(0,.5), cr=cc.p(1,.5), bl=cc.p(0,0), bc=cc.p(.5,0), br=cc.p(1,0) }
            local ap = (al and AP[al]) or cc.p(.5, .5)
            if rec.shapeW then
                -- DrawNode shapes draw centred; shift them so `ap` maps to the
                -- anchor point. Lets shape hands pivot at their base (e.g. bc).
                rec.visual:setPosition((0.5 - ap.x) * rec.shapeW, (0.5 - ap.y) * rec.shapeH)
            elseif al and al ~= "" and AP[al] then
                rec.visual:setAnchorPoint(AP[al])
            end
            rec.visual:setScaleX(rec.bsx); rec.visual:setScaleY(rec.bsy)
            holder:addChild(rec.visual)
        end
        -- static skew (rarely dynamic)
        if L.skew_x and num(L.skew_x) ~= 0 then holder:setSkewX(num(L.skew_x)) end
        if L.skew_y and num(L.skew_y) ~= 0 then holder:setSkewY(num(L.skew_y)) end

        holders[key] = holder
        recsByKey[key] = rec
        self.recs[#self.recs + 1] = rec

        -- Classify by the {tags} the layer uses: the fastest clock cadence among
        -- them drives how often it re-evaluates, and any async categories drive
        -- bridge listeners + feature toggles. A layer with no tags is STATIC —
        -- applied once and never revisited (same as the old "not dynamic").
        local fastest, async, tagnames = STATIC, nil, nil
        for _, a in ipairs(SCAN_ATTRS) do
            local v = L[a]
            if type(v) == "string" then
                for name in v:gmatch("{([^{}]-)}") do
                    local bucket, cat = WFCadence.classify(name)
                    if bucket and bucket < fastest then fastest = bucket end
                    if cat then async = async or {}; async[cat] = true end
                    tagnames = tagnames or {}; tagnames[#tagnames + 1] = name
                end
            end
        end
        -- gif/tap frame animations are frame-timed regardless of their tags
        if rec.frames or rec.type == "image_gif" then fastest = FRAME end
        rec.cadence = fastest
        rec.async   = async
        rec.tags    = tagnames
        rec.dynamic = (fastest ~= STATIC) or (async ~= nil)
    end

    -- Pass 2: parent holders (handles arbitrary 2.0 ordering) in document order.
    for _, rec in ipairs(self.recs) do
        local pid = rec.L.parentId
        local parent = (pid and pid ~= "" and holders[pid]) or self.stage
        parent:addChild(rec.holder)
    end

    -- Pass 3: initial transform + content + visibility, then index each layer
    -- into its cadence bucket / async categories. Each layer is isolated so one
    -- bad layer can't blank the whole face.
    self.dyn       = {}
    self.byCadence = { [FRAME] = {}, [SECOND] = {}, [MINUTE] = {}, [HOUR] = {}, [DAY] = {} }
    self.byAsync   = {}
    self.asyncCats = {}
    for _, rec in ipairs(self.recs) do
        local ok, err = pcall(function()
            self:applyTransform(rec, ctx0)
            if rec.update then rec.update(ctx0) end
        end)
        if not ok then print("[WFRender] layer '" .. tostring(rec.type) .. "' apply: " .. tostring(err)) end
        rec.holder:setVisible(visibleIn(rec.L.display, self.dim))
        if rec.dynamic then self.dyn[#self.dyn + 1] = rec end
        if rec.cadence and self.byCadence[rec.cadence] then
            table.insert(self.byCadence[rec.cadence], rec)
        end
        if rec.async then
            for cat in pairs(rec.async) do
                self.byAsync[cat] = self.byAsync[cat] or {}
                table.insert(self.byAsync[cat], rec)
                self.asyncCats[cat] = true
            end
        end
        -- tap targets: attach now that the holder is in the scene graph
        if (rec.L.tap_action and rec.L.tap_action ~= "") or rec.tap then
            attachTap(self, rec)
        end
    end

    self.ctx        = ctx0                        -- persistent snapshot, updated in place
    self._lastClock = { t = os.date("*t"), subsec = 0, elapsed = 0 }
    self.needFrame  = #self.byCadence[FRAME]  > 0
    self.needSecond = #self.byCadence[SECOND] > 0
    self:buildFeatures()
    self:registerAsyncListeners()

    print(string.format("[WFRender] loaded %d layers (%d dyn, frame=%s sec=%s), theme=%s",
        #self.recs, #self.dyn, tostring(self.needFrame), tostring(self.needSecond), self.theme.ucolor))
    return true
end

-- Which async data sources this face uses (drives the Java feature toggles).
function WFRender:buildFeatures()
    local a = self.asyncCats or {}
    self.features = {
        weather = a["weather"] and true or false,
        health = {
            steps     = a["health:steps"]     and true or false,
            heartRate = a["health:heartRate"] and true or false,
            spo2      = a["health:spo2"]      and true or false,
        },
        sensor = {
            accelerometer = a["sensor:accelerometer"] and true or false,
            gyroscope     = a["sensor:gyroscope"]     and true or false,
            compass       = a["sensor:compass"]       and true or false,
            barometer     = a["sensor:barometer"]     and true or false,
        },
    }
end

-- Subscribe to the bridge categories this face reads. A push refreshes the
-- snapshot and re-evaluates only the layers bound to that source. Handlers are
-- torn down in clear() on the next switch.
function WFRender:registerAsyncListeners()
    self._asyncHandlers = self._asyncHandlers or {}
    if not (WatchfaceBridge and WatchfaceBridge.onChange) then return end
    local need = {}
    for cat in pairs(self.asyncCats) do
        local bc = ASYNC_BRIDGE[cat]
        if bc then need[bc] = true end
    end
    for bridgeCat in pairs(need) do
        local handler = function()
            self:refreshFull()
            for cat, list in pairs(self.byAsync) do
                if ASYNC_BRIDGE[cat] == bridgeCat then self:_evalList(list, self.ctx) end
            end
        end
        WatchfaceBridge.onChange(bridgeCat, handler)
        self._asyncHandlers[bridgeCat] = handler
    end
end

function WFRender:applyTransform(rec, ctx)
    local L = rec.L
    local holder = rec.holder
    -- authored (x, y) is screen-down; Y_SIGN flips it into cocos Y-up (see top).
    holder:setPosition(WFTags.evalNumber(L.x, ctx, 0),
                       Y_SIGN * WFTags.evalNumber(L.y, ctx, 0))
    holder:setRotation(WFTags.evalNumber(L.rotation, ctx, 0) + (rec.rotOffset or 0))
    holder:setScaleX(WFTags.evalNumber(L.anim_scale_x, ctx, 100) / 100)
    holder:setScaleY(WFTags.evalNumber(L.anim_scale_y, ctx, 100) / 100)
    holder:setOpacity(floor(clamp(WFTags.evalNumber(L.opacity, ctx, 100), 0, 100) * 2.55))
end

-- Re-evaluate a list of layers. A failing layer is logged once and skipped so
-- it can't take down the whole render loop.
function WFRender:_evalList(list, ctx)
    if not list then return end
    for _, rec in ipairs(list) do
        local ok, err = pcall(function()
            self:applyTransform(rec, ctx)
            if rec.update then rec.update(ctx) end
        end)
        if not ok and not rec._warned then
            rec._warned = true
            print("[WFRender] update '" .. tostring(rec.type) .. "': " .. tostring(err))
            -- Surface only the first layer-update error per face load (still logs
            -- all) so a broken face can't stack a dialog per bad layer.
            if not self._layerErrorReported and WatchfaceBridge and WatchfaceBridge.reportError then
                self._layerErrorReported = true
                WatchfaceBridge.reportError("Layer '" .. tostring(rec.type) .. "' update", err)
            end
        end
    end
end

-- Full rebuild of the persistent snapshot — cheap enough at ≤1×/second (clock
-- boundary) or on an async bridge push. Reuses the last clock when none is given.
function WFRender:refreshFull(clock)
    clock = clock or self._lastClock or { t = os.date("*t"), subsec = 0, elapsed = 0 }
    self._lastClock = clock
    self.ctx = self:buildContext(clock)
end

-- Per-frame refresh: mutate only the sub-second tags in place (no allocation).
function WFRender:refreshFrame(clock)
    self._lastClock = clock
    if self.ctx then WFTags.buildFrame(self.ctx, clock) end
end

-- Keep the last clock current (so async pushes rebuild with a fresh time) even
-- when the per-frame loop is suspended for a minute-only face.
function WFRender:setClock(clock)
    self._lastClock = clock
end

-- Fire the cadence buckets that ticked this frame. ticks = {frame,second,
-- minute,hour,day} booleans. Buckets hold disjoint layer sets, so no layer is
-- evaluated twice even when several buckets tick together (e.g. a minute wrap
-- also ticks second).
function WFRender:tickBuckets(ticks)
    local ctx = self.ctx
    if not ctx or not self.byCadence then return end
    if ticks.frame  then self:_evalList(self.byCadence[FRAME],  ctx) end
    if ticks.second then self:_evalList(self.byCadence[SECOND], ctx) end
    if ticks.minute then self:_evalList(self.byCadence[MINUTE], ctx) end
    if ticks.hour   then self:_evalList(self.byCadence[HOUR],   ctx) end
    if ticks.day    then self:_evalList(self.byCadence[DAY],    ctx) end
end

function WFRender:setDim(dim)
    if self.dim == dim then return end
    self.dim = dim
    for _, rec in ipairs(self.recs) do
        rec.holder:setVisible(visibleIn(rec.L.display, dim))
    end
end

-- Switch to the Nth theme (1-based) and refresh themed colours next frame.
function WFRender:setTheme(idx)
    local th = self.themes and self.themes[idx]
    if not th then return end
    self.theme = { ucolor = th.ucolor, ucolor2 = th.ucolor2 or th.ucolor,
                   ucolor3 = th.ucolor3 or th.ucolor }
end

return WFRender
