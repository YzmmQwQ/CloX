-- wf_render.lua — Watchmaker (.watch / JBwatch) renderer for Axmol/Cocos2d-x.
--
-- Parses a watch.xml face definition (format 1.0 flat layers and 2.0 Studio
-- groups/themes) and builds live cocos nodes for it. See the format reference
-- in watchmaker-definition/ (WM_FORMAT_1.0.md, WM_FORMAT_2.0.md, WM_TAGS.md).
--
-- Update model (the import-time indexing the engine is built around):
--   • at LOAD, every dynamic attribute is compiled to a Lua function
--     (wf_expr) and wrapped in a binding (wf_binding) that is indexed into
--     the update bucket of every {tag} it reads (wf_engine);
--   • at RUNTIME, the scene fires events (ms / second / minute / hour / day /
--     bridge pushes); each event recomputes only its active tags and changed
--     tags run their buckets. No XML, no gsub, no re-classification — just
--     compiled chunks and table lookups.
-- The face's global script (scripts/script.txt) loads BEFORE the layers so
-- its variables resolve inside attribute expressions (wf_script, wf_sandbox).
--
-- Coordinate model: the watch canvas is 512×512, origin at the centre.
-- Authored layer positions are screen-down (positive Y = toward the bottom);
-- the renderer flips them into cocos Y-up at the single `Y_SIGN` site below.
-- The caller supplies a `stage` cc.Node already positioned at screen centre
-- and scaled so 512 units fill the watch — this module works purely in canvas
-- units and never touches screen pixels.
--
-- Layout per layer:  holder (carries x/y, rotation, anim_scale, opacity, skew)
--                    └─ visual (the sprite/label/draw-node, base-scaled to fit)
-- Groups (2.0) are holders with no visual; children attach to them, so nested
-- transforms fall out of the cocos scene graph for free.

local WFExpr    = require "wf_expr"
local WFEngine  = require "wf_engine"
local WFSandbox = require "wf_sandbox"
local WFScript  = require "wf_script"
local WFProtect = require "wf_protect"
local B         = require "wf_binding"
local WFBinding, WFSetterBinding, WFCompositeBinding, WFColor =
    B.WFBinding, B.WFSetterBinding, B.WFCompositeBinding, B.WFColor

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

-- Engine event names that are NOT bridge push categories (everything else
-- active in the engine gets a WatchfaceBridge.onChange listener).
local CLOCK_EVENTS = {
    ms = true, second = true, minute = true, hour = true, day = true,
    static = true, theme = true,
}

local c3b, c4b, c4f = WFColor.c3b, WFColor.c4b, WFColor.c4f

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
    for i = 0, n do
        local a = rot0 + i / n * 2 * math.pi
        local p = cc.p(rx * cos(a), ry * sin(a))
        if prev then dn:drawTriangle(cc.p(0, 0), prev, p, col) end
        prev = p
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

-- ── WFLayerRec — one layer's record: node handles + compiled attributes ──────
-- The rec owns the per-attribute compiled descriptors and offers the eval/bind
-- helpers builders use. All compilation happens through here, once per attr.

local WFLayerRec = {}
WFLayerRec.__index = WFLayerRec

function WFLayerRec.new(renderer, L, holder, key, index)
    return setmetatable({
        renderer = renderer,
        engine   = renderer.engine,
        sandbox  = renderer.sandbox,
        L        = L,
        holder   = holder,
        key      = key,
        index    = index,
        type     = L.type,
        bsx = 1, bsy = 1,
        _descs   = {},
    }, WFLayerRec)
end

-- Compiled descriptor for an attribute (memoised; kind fixed per attr).
function WFLayerRec:desc(attr, kind)
    local d = self._descs[attr]
    if not d then
        d = WFExpr.compile(self.L[attr], kind, self.engine, self.sandbox)
        self._descs[attr] = d
    end
    return d
end

-- Pre-compile an attribute (composite builders declare their reads up front so
-- every dependency is indexed before the face starts running).
function WFLayerRec:use(attr, kind)
    self:desc(attr, kind)
end

-- Evaluate NOW (build time or inside a composite redraw).
function WFLayerRec:evalNum(attr, default)
    local d = self:desc(attr, "num")
    if d.const then
        if d.value == nil then return default end
        return d.value
    end
    return tonumber(d.fn(self.engine.V)) or default
end

function WFLayerRec:evalText(attr, default)
    local d = self:desc(attr, "text")
    if d.const then return d.value or default end
    local v = d.fn(self.engine.V)
    return v == nil and (default or "") or tostring(v)
end

function WFLayerRec:evalSub(attr, default)
    local d = self:desc(attr, "sub")
    if d.const then return d.value or default end
    local v = d.fn(self.engine.V)
    return v == nil and (default or "") or tostring(v)
end

-- Bind one numeric property: constants apply once at build; dynamic values
-- become a setter binding indexed into every dep tag's bucket.
function WFLayerRec:bindNum(attr, default, setter)
    local d = self:desc(attr, "num")
    if d.const then
        if d.value ~= nil then setter(self, d.value) end
        return
    end
    self.engine:bind(WFSetterBinding.new(self.engine, self, d, "num", default, setter), d.deps)
end

function WFLayerRec:bindText(attr, setter)
    local d = self:desc(attr, "text")
    if d.const then
        if d.value ~= nil then setter(self, d.value) end
        return
    end
    self.engine:bind(WFSetterBinding.new(self.engine, self, d, "text", "", setter), d.deps)
end

function WFLayerRec:bindSub(attr, setter)
    local d = self:desc(attr, "sub")
    if d.const then
        if d.value ~= nil then setter(self, d.value) end
        return
    end
    self.engine:bind(WFSetterBinding.new(self.engine, self, d, "text", "", setter), d.deps)
end

-- Composite: one redraw closure fired when ANY of the listed attributes' tags
-- change (attrs = { {name, kind}, ... }). A fully static composite draws once
-- and is never revisited.
function WFLayerRec:composite(redraw, attrs)
    local deps, seen = {}, {}
    for _, spec in ipairs(attrs) do
        local d = self:desc(spec[1], spec[2])
        if not d.const then
            for _, t in ipairs(d.deps) do
                if not seen[t] then seen[t] = true; deps[#deps + 1] = t end
            end
        end
    end
    if #deps == 0 then
        local ok, err = pcall(redraw, self)
        if not ok then print("[WFRender] static draw '" .. tostring(self.type) .. "': " .. tostring(err)) end
        return
    end
    self.engine:bind(WFCompositeBinding.new(self, redraw), deps)
end

-- ── module ────────────────────────────────────────────────────────────────────

function WFRender.new(stage)
    local self = setmetatable({}, WFRender)
    self.stage  = stage
    self.recs   = {}          -- every layer record
    self.taps   = {}          -- tappable recs (tap_action / image_tap frames)
    self.dim    = false
    self.theme  = { ucolor = "ffffff", ucolor2 = "ffffff", ucolor3 = "ffffff" }
    self.bgColor = "000000"
    self.onNeedFrame = nil    -- scene hook: frame-loop need changed after load
    return self
end

function WFRender:clear()
    -- Drop bridge listeners from the previous face, or stale closures would keep
    -- firing into a dead engine after a switch.
    if self._asyncHandlers and WatchfaceBridge and WatchfaceBridge.offChange then
        for bridgeCat, fn in pairs(self._asyncHandlers) do
            WatchfaceBridge.offChange(bridgeCat, fn)
        end
    end
    self._asyncHandlers = {}
    if self.stage then self.stage:removeAllChildren() end
    self.recs, self.taps = {}, {}
    self._colorIdx = 0
    self.engine, self.sandbox, self.script = nil, nil, nil
    self.needFrame, self.needSecond = false, false
    WFBinding.errorReported = false
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
-- (intrinsic base scale to fit width/height) and registers its dynamic-content
-- bindings through the rec helpers. Holder transform (x/y/rotation/scale/
-- opacity) is bound generically afterwards.

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
    rec:bindSub("color", function(_, v) sp:setColor(c3b(v)) end)
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
    -- frame stepping is elapsed-time-driven, not tag-driven: a raw ms listener
    rec.lastFrame = -1
    self.engine:listen("ms", function(clock)
        local fi = floor(clock.elapsed / rec.delay) % #frames + 1
        if fi ~= rec.lastFrame then
            rec.lastFrame = fi
            sp:setTexture(self:imageFile(frames[fi]))
        end
    end)
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
    local lab = makeLabel(caseOf(rec:evalText("text", "")))
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
    rec:bindText("text", function(_, v) lab:setString(caseOf(v)) end)
    -- colour follows dim mode (color_dim), tint alpha follows opacity
    rec:use("color", "sub"); rec:use("color_dim", "sub"); rec:use("opacity", "num")
    rec:composite(function(r)
        local attr = (self.dim and L.color_dim) and "color_dim" or "color"
        lab:setTextColor(c4b(r:evalSub(attr, "ffffff"), r:evalNum("opacity", 100)))
    end, { { "color", "sub" }, { "color_dim", "sub" }, { "opacity", "num" } })
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
    rec:use("cond_value", "num"); rec:use("color", "sub")
    rec:composite(function(r)
        local idx = clamp(floor(r:evalNum("cond_value", 1)) - 1, 0, cols * rows - 1)
        if idx ~= rec.lastIdx then
            rec.lastIdx = idx
            local col, row = idx % cols, floor(idx / cols)   -- row-major, top-left
            sp:setTextureRect(cc.rect(col * cw, row * chh, cw, chh))
        end
        sp:setColor(c3b(r:evalSub("color", "ffffff")))
    end, { { "cond_value", "num" }, { "color", "sub" } })
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
            rec:bindSub("color", function(_, v) sp:setColor(c3b(v)) end)
            return
        end
    end
    -- Otherwise draw the primitive with a DrawNode.
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local w, h = num(L.width, 50), num(L.height, 50)
    rec.shapeW, rec.shapeH = w, h
    local shp = (L.shape or "Square"):lower()
    rec:composite(function(r)
        dn:clear()
        local col = c4f(r:evalSub("color", "ffffff"), r:evalNum("opacity", 100))
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
    end, { { "color", "sub" }, { "opacity", "num" } })
end

function Builders.rounded(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local w, h = num(L.width, 100), num(L.height, 60)
    rec.shapeW, rec.shapeH = w, h
    local r0 = math.min(num(L.radius, 0), w / 2, h / 2)
    local ct = floor(num(L.corner_type, 1))
    -- corner_type → which corners round (see WM_FORMAT_1.0 §7.12)
    local CORNERS = {
        [0]={0,0,0,0},[1]={1,1,1,1},[2]={1,1,1,0},[3]={1,1,0,1},[4]={1,0,1,1},
        [5]={0,1,1,1},[6]={1,1,0,0},[7]={0,0,1,1},[8]={1,0,1,0},[9]={0,1,0,1},
        [10]={1,0,0,1},[11]={0,1,1,0},[12]={1,0,0,0},[13]={0,1,0,0},
        [14]={0,0,1,0},[15]={0,0,0,1},[16]={0,0,0,0},
    }
    local cor = CORNERS[ct] or CORNERS[1]   -- {TL,TR,BL,BR}
    rec:composite(function(rr)
        dn:clear()
        local col = c4f(rr:evalSub("color", "ffffff"), rr:evalNum("opacity", 100))
        local hw, hh = w / 2, h / 2
        if r0 <= 0 then dn:drawSolidRect(cc.p(-hw, -hh), cc.p(hw, hh), col); return end
        -- centre cross fills everything except the four corner squares
        dn:drawSolidRect(cc.p(-hw + r0, -hh), cc.p(hw - r0, hh), col)
        dn:drawSolidRect(cc.p(-hw, -hh + r0), cc.p(hw, hh - r0), col)
        local function corner(cx, cy, rounded, q)
            if rounded == 1 then
                dn:drawSolidCircle(cc.p(cx, cy), r0, 0, 24, 1, 1, col)
            else
                local sx = (q == 2 or q == 4) and 1 or -1   -- toward outer x
                local sy = (q >= 3) and -1 or 1
                dn:drawSolidRect(cc.p(cx, cy), cc.p(cx + sx * r0, cy + sy * r0), col)
            end
        end
        corner(-hw + r0,  hh - r0, cor[1], 1)   -- TL
        corner( hw - r0,  hh - r0, cor[2], 2)   -- TR
        corner(-hw + r0, -hh + r0, cor[3], 3)   -- BL
        corner( hw - r0, -hh + r0, cor[4], 4)   -- BR
    end, { { "color", "sub" }, { "opacity", "num" } })
end

function Builders.ring(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local ro = num(L.radius_outer, 100)
    local ri = num(L.radius_inner, ro * 0.8)
    rec.hitW, rec.hitH = ro * 2, ro * 2      -- tap target (DrawNode has no size)
    local cw = (L.is_clockwise or "Y") ~= "N"
    local outOp = num(L.outside_opacity, 100)
    rec:composite(function(r)
        dn:clear()
        local total = r:evalNum("angle_total", 360)
        local ang = clamp(r:evalNum("angle", 0), 0, total)
        local opacity = r:evalNum("opacity", 100)
        -- outside band (beyond angle_total) with color3
        if total < 360 and L.color3 then
            fillArc(dn, ri, ro, total, 360, cw, c4f(r:evalSub("color3", "ffffff"), outOp * opacity / 100))
        end
        -- unfilled track [ang..total] with color2
        if L.color2 then
            fillArc(dn, ri, ro, ang, total, cw, c4f(r:evalSub("color2", "ffffff"), outOp * opacity / 100))
        end
        -- filled [0..ang] with color
        fillArc(dn, ri, ro, 0, ang, cw, c4f(r:evalSub("color", "ffffff"), opacity))
    end, { { "angle", "num" }, { "angle_total", "num" }, { "opacity", "num" },
           { "color", "sub" }, { "color2", "sub" }, { "color3", "sub" } })
end

function Builders.progress(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local w, h = num(L.width, 120), num(L.height, 24)
    rec.shapeW, rec.shapeH = w, h
    rec.rotOffset = -num(L.angle, 0)   -- angle 0=horizontal,90=vertical-up
    local margin = num(L.margin, 0)
    local outOp = num(L.outside_opacity, 100)
    rec:composite(function(r)
        dn:clear()
        local pct = clamp(r:evalNum("pct_complete", 0), 0, 100)
        local opacity = r:evalNum("opacity", 100)
        local hw, hh = w / 2, h / 2
        -- track
        dn:drawSolidRect(cc.p(-hw, -hh), cc.p(hw, hh),
            c4f(r:evalSub("color3", "444444"), outOp * opacity / 100))
        -- fill
        local innerW = w - margin * 2
        local fillW = innerW * pct / 100
        if fillW > 0 then
            dn:drawSolidRect(cc.p(-hw + margin, -hh + margin),
                cc.p(-hw + margin + fillW, hh - margin),
                c4f(r:evalSub("color", "ffffff"), opacity))
        end
    end, { { "pct_complete", "num" }, { "opacity", "num" },
           { "color", "sub" }, { "color3", "sub" } })
end

function Builders.markers(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local count = math.max(1, floor(num(L.m_count, 12)))
    local radius = num(L.radius, 200)
    rec.hitW, rec.hitH = radius * 2, radius * 2
    local mw, mh = num(L.m_width, 10), num(L.m_height, 30)
    local shp = (L.shape or "Square"):lower()
    local sq = num(L.squarify, 0)
    rec:composite(function(r)
        dn:clear()
        local col = c4f(r:evalSub("color", "ffffff"), r:evalNum("opacity", 100))
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
    end, { { "color", "sub" }, { "opacity", "num" } })
end

function Builders.markers_hm(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local radius = num(L.radius, 240)
    rec.hitW, rec.hitH = radius * 2, radius * 2
    local SZ = { None = 0, Tiny = 1, Small = 2, Medium = 3, Large = 4 }
    local hsz = SZ[L.m_hour or "Medium"] or 3
    local msz = SZ[L.m_minute or "Medium"] or 3
    local sq = num(L.squarify, 0)
    local function tick(center, th, len, wdt, col)
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
    rec:composite(function(r)
        dn:clear()
        local opacity = r:evalNum("opacity", 100)
        local colM = c4f(r:evalSub(L.color2 and "color2" or "color", "ffffff"), opacity)
        local colH = c4f(r:evalSub("color", "ffffff"), opacity)
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
    end, { { "color", "sub" }, { "color2", "sub" }, { "opacity", "num" } })
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
    local str = rec:evalText("text", "")
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
-- Touches arrive from Android (GestureInterceptorLayout → LuaBridge.passTouch
-- → WatchfaceOnTouch → scene → tapAt), NOT from cocos touch listeners — the
-- Java gesture layer consumes raw events before the GL surface sees them.
-- Dispatch mirrors the UL reference renderer (WatchSkin:touch): test every
-- tappable, visible layer against its bounds with a generous 50-canvas-unit
-- radius fallback, and fire only the layer whose centre is closest.

-- UL's colour-switch palette (WatchSkinUtils.lua ucolor_map) for the
-- color_switch_* tap actions on faces without <ThemeSets>. Index 0 = the
-- face's own default colour.
local UCOLOR_MAP = {
    "5987b2","20b2aa","a10d09","0bf9ab","ffa419","e6f0fa","f0e6fa","faecf3",
    "f6f5f0","e6effa","e6e6fa","ffcccc","306a9f","fef65b","ffcccc","e6e6fa",
    "a1090d","a10900","12e6a0","660066","cce5cc","004c64","20b2aa","40e0d0",
    "7fffd4","fff8e7","f04b23","d8b2d8","008aa0","3b444b","ac8141","1e90ff",
    "838da6","8b0000","ffcccc","e5f2e5","cce5cc","fbe6fb","ffffcc","000000",
    "ffffff",
}

-- Run a tap-action string. `script:` bodies execute in the face sandbox — the
-- SAME environment as the global script, so functions/variables defined there
-- are callable and their writes propagate through the tag buckets. The
-- colour-switch family is renderer-level (WM_TAP_ACTIONS.md §6: no Java
-- handler); everything else routes to Android as a shortcut.
function WFRender:runAction(action)
    if not action or action == "" then return end
    if action:sub(1, 7) == "script:" then
        if self.script then
            self.script:runSnippet(WFExpr.unescape(action:sub(8)))
        end
    elseif action == "color_switch_next" or action == "color_switch_select" then
        self:colorSwitch(1)
    elseif action == "color_switch_prev" then
        self:colorSwitch(-1)
    elseif WatchfaceBridge and WatchfaceBridge.tapAction then
        WatchfaceBridge.tapAction(action)
    end
end

-- Cycle the accent colour: through <ThemeSets> when the face defines them,
-- otherwise through UL's palette over {ucolor} (index 0 = face default).
function WFRender:colorSwitch(dir)
    if self.themes and #self.themes > 1 then
        self._themeIdx = ((self._themeIdx or 1) - 1 + dir) % #self.themes + 1
        self:setTheme(self._themeIdx)
        return
    end
    local n = #UCOLOR_MAP
    self._colorIdx = ((self._colorIdx or 0) + dir) % (n + 1)   -- 0..n, 0 = default
    local u = (self._colorIdx == 0)
        and ((self.watch and self.watch.ucolor_default) or "ffffff")
        or UCOLOR_MAP[self._colorIdx]
    self.theme = { ucolor = u, ucolor2 = self.theme.ucolor2, ucolor3 = self.theme.ucolor3 }
    self.engine:publish("ucolor", u)
    self.engine:publish("ucolor_b", u)
    print("[WFRender] color_switch → " .. self._colorIdx .. " (" .. u .. ")")
end

function WFRender:dispatchTap(rec)
    print("[WFRender] tap layer '" .. tostring(rec.type) .. "' action=" ..
          tostring(rec.L.tap_action))
    if rec.tap then rec.tap() end          -- image_tap frame advance
    self:runAction(rec.L.tap_action)
end

-- A layer is tappable when its whole holder chain is display-visible.
-- Opacity NEVER affects the touch zone (UL semantics): opacity-0 shapes are
-- the standard WM invisible-hotspot pattern, so transparent targets must stay
-- tappable — even ones a script faded out.
local function chainVisible(node, stop)
    while node and node ~= stop do
        if not node:isVisible() then return false end
        node = node:getParent()
    end
    return true
end

-- Half-extents of a layer's tap target, in holder-local (canvas) units.
function WFRender:hitExtent(rec)
    if rec.hitW then return rec.hitW / 2, (rec.hitH or rec.hitW) / 2 end
    if rec.shapeW then return rec.shapeW / 2, rec.shapeH / 2 end
    local v = rec.visual
    if v then
        local cs = v:getContentSize()
        if cs.width > 0 then
            return cs.width * rec.bsx / 2, cs.height * rec.bsy / 2
        end
    end
    local w, h = rec:evalNum("width", 0), rec:evalNum("height", 0)
    return w / 2, h / 2      -- 0 extents still get the 50-unit radius below
end

-- Canvas-space tap dispatch. (cx, cy) are 512-canvas coordinates (origin
-- centre, cocos Y-up) — the scene converts from screen pixels. Returns true
-- when a layer consumed the tap.
function WFRender:tapAt(cx, cy)
    local world = self.stage:convertToWorldSpace(cc.p(cx, cy))
    local best, bestDist
    for _, rec in ipairs(self.taps) do
        if chainVisible(rec.holder, self.stage) then
            local ok, lp = pcall(function() return rec.holder:convertToNodeSpace(world) end)
            if ok and lp then
                local hw, hh = self:hitExtent(rec)
                local dist = math.sqrt(lp.x * lp.x + lp.y * lp.y)
                -- inside the bounds, or within UL's 50-unit grace radius
                if (math.abs(lp.x) <= hw and math.abs(lp.y) <= hh) or dist < 50 then
                    if not best or dist < bestDist then best, bestDist = rec, dist end
                end
            end
        end
    end
    if best then
        local ok, err = pcall(function() self:dispatchTap(best) end)
        if not ok then print("[WFRender] tap dispatch: " .. tostring(err)) end
        return true
    end
    return false
end

-- ── generic holder transform bindings ─────────────────────────────────────────
-- One setter binding per dynamic transform attribute. Static values apply once
-- here and are never revisited; absent values match the cocos node defaults.

local function bindTransform(rec)
    rec:bindNum("x", 0, function(r, v) r.holder:setPositionX(v) end)
    -- authored y is screen-down; Y_SIGN flips into cocos Y-up (see top)
    rec:bindNum("y", 0, function(r, v) r.holder:setPositionY(Y_SIGN * v) end)
    rec:bindNum("rotation", 0, function(r, v)
        r.holder:setRotation(v + (r.rotOffset or 0))
    end)
    rec:bindNum("anim_scale_x", 100, function(r, v) r.holder:setScaleX(v / 100) end)
    rec:bindNum("anim_scale_y", 100, function(r, v) r.holder:setScaleY(v / 100) end)
    rec:bindNum("opacity", 100, function(r, v)
        r.holder:setOpacity(floor(clamp(v, 0, 100) * 2.55))
    end)
    -- progress bars carry a static rotOffset; make sure it lands even when
    -- `rotation` is absent (the loop above only fires for present attrs)
    if rec.rotOffset and rec.rotOffset ~= 0 and not rec.L.rotation then
        rec.holder:setRotation(rec.rotOffset)
    end
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

    -- engine + sandbox + global script — BEFORE any layer compiles, so script
    -- variables resolve inside attribute expressions and {var} tags activate
    -- with real initial values.
    self.engine  = WFEngine.new()
    self.sandbox = WFSandbox.new()
    self.script  = WFScript.new(self.engine, self.sandbox,
                                function(a) self:runAction(a) end)
    WFBinding.onError = function(context, err)
        if WatchfaceBridge and WatchfaceBridge.reportError then
            WatchfaceBridge.reportError(context, err)
        end
    end
    self:publishTheme()
    self.script:load(baseDir)
    self.script:attach()
    self.script:onNeedFrameChanged(function()
        local was = self.needFrame
        self.needFrame = self.engine:eventActive("ms") or self.script:needsFrame()
        if self.needFrame ~= was and self.onNeedFrame then self.onNeedFrame() end
    end)

    -- Pass 1: create a holder per layer, build its visual + content bindings.
    local holders = {}            -- key → holder node
    for i, L in ipairs(layers) do
        local key = (L.id and L.id ~= "" and L.id) or ("__" .. i)
        local holder = cc.Node:create()
        holder:setCascadeOpacityEnabled(true)
        local rec = WFLayerRec.new(self, L, holder, key, i)
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

        -- transform attributes → setter bindings (constants apply immediately)
        local ok, err = pcall(bindTransform, rec)
        if not ok then print("[WFRender] transform " .. tostring(L.type) .. ": " .. tostring(err)) end

        holders[key] = holder
        self.recs[#self.recs + 1] = rec
    end

    -- Pass 2: parent holders (handles arbitrary 2.0 ordering) in document order.
    for _, rec in ipairs(self.recs) do
        local pid = rec.L.parentId
        local parent = (pid and pid ~= "" and holders[pid]) or self.stage
        parent:addChild(rec.holder)
    end

    -- Pass 3: visibility + tap-target index, now that holders are parented.
    for _, rec in ipairs(self.recs) do
        rec.holder:setVisible(visibleIn(rec.L.display, self.dim))
        if (rec.L.tap_action and rec.L.tap_action ~= "") or rec.tap then
            self.taps[#self.taps + 1] = rec
        end
    end

    -- Initial paint: run every binding once against the freshly-computed tag
    -- values, then wire the world.
    self.engine:setClock(os.date("*t"), 0, 0)
    self.engine:repaint()

    self.needFrame  = self.engine:eventActive("ms") or self.script:needsFrame()
    self.needSecond = self.engine:eventActive("second")
    self:buildFeatures()
    self:registerAsyncListeners()

    local nBind = #self.engine.bindings
    print(string.format("[WFRender] loaded %d layers (%d bindings, frame=%s sec=%s), theme=%s",
        #self.recs, nBind, tostring(self.needFrame), tostring(self.needSecond),
        tostring(self.theme.ucolor)))
    return true
end

-- Push the active theme colours through the engine — buckets of every layer
-- referencing {ucolor}/{ucolor2}/{ucolor3} run immediately.
function WFRender:publishTheme()
    local e = self.engine
    e:publish("ucolor",   self.theme.ucolor or "ffffff")
    e:publish("ucolor2",  self.theme.ucolor2 or "ffffff")
    e:publish("ucolor3",  self.theme.ucolor3 or "ffffff")
    e:publish("ucolor_b", self.theme.ucolor or "ffffff")
end

-- Which async data sources this face uses (drives the Java feature toggles).
function WFRender:buildFeatures()
    local e = self.engine
    self.features = {
        weather = e:eventActive("weather"),
        health = {
            steps     = e:eventActive("health_steps"),
            heartRate = e:eventActive("health_heartRate"),
            spo2      = e:eventActive("health_spo2"),
        },
        sensor = {
            accelerometer = e:eventActive("sensor_accelerometer"),
            gyroscope     = e:eventActive("sensor_gyroscope"),
            compass       = e:eventActive("sensor_compass"),
            barometer     = e:eventActive("sensor_barometer"),
        },
    }
end

-- Subscribe to the bridge categories this face reads: engine event names that
-- aren't clock events ARE bridge categories (wf_tagdefs uses the WF.* names).
-- A push fires that category's event — its tags recompute and only the layers
-- indexed under the changed tags re-apply. Torn down in clear() on switch.
function WFRender:registerAsyncListeners()
    self._asyncHandlers = self._asyncHandlers or {}
    if not (WatchfaceBridge and WatchfaceBridge.onChange) then return end
    local engine = self.engine
    for _, name in ipairs(engine:activeEventNames()) do
        if not CLOCK_EVENTS[name] and not self._asyncHandlers[name] then
            local handler = function() engine:fire(name) end
            WatchfaceBridge.onChange(name, handler)
            self._asyncHandlers[name] = handler
        end
    end
end

-- ── per-tick driving (called by the scene) ────────────────────────────────────

-- ticks = {frame,second,minute,hour,day} booleans. Slow events fire first so
-- the ms pass sees fresh minute/second values on boundary frames.
function WFRender:tick(t, subsec, elapsed, ticks)
    local e = self.engine
    if not e then return end
    e:setClock(t, subsec, elapsed)
    if ticks.day    then e:fire("day")    end
    if ticks.hour   then e:fire("hour")   end
    if ticks.minute then e:fire("minute") end
    if ticks.second then e:fire("second") end
    if ticks.frame  then e:fire("ms")     end
end

function WFRender:setDim(dim)
    if self.dim == dim then return end
    self.dim = dim
    for _, rec in ipairs(self.recs) do
        rec.holder:setVisible(visibleIn(rec.L.display, dim))
    end
    -- dim-aware content (color_dim texts) repaints from live values
    if self.engine then self.engine:repaint() end
end

-- Switch to the Nth theme (1-based); {ucolor*} buckets re-apply immediately.
function WFRender:setTheme(idx)
    local th = self.themes and self.themes[idx]
    if not th then return end
    self._themeIdx = idx
    self.theme = { ucolor = th.ucolor, ucolor2 = th.ucolor2 or th.ucolor,
                   ucolor3 = th.ucolor3 or th.ucolor }
    if self.engine then self:publishTheme() end
end

return WFRender
