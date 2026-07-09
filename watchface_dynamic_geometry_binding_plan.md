# Watchface Renderer — Dynamic Geometry Attribute Binding (plan for a fresh session)

## Origin

Reported bug: the active watchface's heart-rate icon rendered nearly full-screen
(~540×540, its native `.ppng` texture size) instead of its authored ~32×28 icon size.
Root cause and full investigation trail are below; **the reported bug is already
fixed and confirmed on-device** (see "Status" below). This document is the plan for
the larger, still-open issue that investigation surfaced.

## Root cause (confirmed)

`app/src/main/assets/lua/wf_render.lua` compiles every WatchMaker attribute string
through `wf_expr.lua`'s `WFExpr.compile`, which correctly detects `{tag}` references
(including multiple tags in one expression, e.g.
`width="32+ (.05* {shr} * math.sin({shr} / 60 * {drss}))"`) and returns a descriptor
with `deps = {"shr", "drss"}`. The engine (`wf_engine.lua`, `WFEngine:bind`) correctly
indexes a binding into **every** dep's tag bucket — the compile/bind machinery itself
is sound and not in question.

The bug: most `Builders.*` functions never routed their geometry attributes (`width`,
`height`, `radius`, `margin`, `corner_type`, etc.) through that machinery at all. They
read the raw string off `L.attr` and ran it through a local helper:

```lua
local function num(v, d) return tonumber(v) or d or 0 end
```

`tonumber()` on an expression string returns `nil`, so `num()` silently fell back to
`d` — for image layers, `d` was the source texture's native pixel size
(`cs.width`/`cs.height`), which is how the heart icon rendered at ~540×540 instead of
~32×28.

## Status

### Tier 1 — done, verified, NOT YET COMMITTED (working tree)

Every raw `num(L.xxx, default)` / `num(rec.L.xxx, default)` call site in
`wf_render.lua` (74 occurrences across ~17 functions — `image`, `image_tap`,
`image_gif`, `image_cond`, `shape`, `rounded`, `ring`, `progress`, `markers`,
`markers_hm`, `video`, `map`, `series`, `calendar`, `chart`, `text_ring`,
`text_curved`, `bindTransform`'s `gyro`, and the main build loop's `skew_x`/`skew_y`)
was mechanically swapped to `rec:evalNum("xxx", default)`.

`rec:evalNum` is a strict superset of `num()`: identical result for a plain numeric
constant, but for an expression string it actually **evaluates it once, now, using
current tag values** instead of silently falling back to the wrong default.

**This alone fixes the reported bug** — confirmed by pulling the active `.watch`
package (`wf_334_jb.watch`), XOR-decoding its images with `wf_protect.lua`'s scheme,
decoding `watch.pxml` (swapped-Base64) to find the exact layer definition, and
comparing an on-device screenshot before/after: the heart icon now renders at its
correct small size, matching the packaged `preview.jpg`.

**What Tier 1 does NOT do**: live updates. `evalNum` evaluates once, at layer build
time, using whatever the dependent tags' values are *right then*, and never again.
A genuinely dynamic geometry expression (the heart's heartbeat-pulse formula, tied to
`{shr}`/`{drss}`) will be sized correctly on load but will not visibly animate
afterward — confirmed by testing with an emulated heart-rate sensor value: the icon
stayed static at its build-time size regardless of later sensor changes.

### Tier 2 — NOT started. This is the scope of this plan.

The user's ask, restated precisely: **every dynamic attribute anywhere in the file
must be treated uniformly** — correctly detect all tags it depends on, subscribe to
all of them, and re-apply on every relevant change — the same guarantee opacity,
color, angle, and text attributes already have via `rec:bindNum`/`rec:composite`/
`rec:dimComposite` elsewhere in this same file. Leaving geometry attributes
one-time-only while other attribute kinds are live is exactly the kind of
inconsistent/special-cased handling to eliminate, not preserve.

## Why this is a real, broad rewrite (not a small patch)

For every builder below, the attributes converted to `evalNum` in Tier 1 are used to
compute **derived local state** (`w, h`, `r0`, `cor`, `cw, chh`, etc.) that in turn
feeds a **one-time** node setup: `setBaseScale(...)`, a single `DrawNode:draw*` call,
a `CORNERS[...]` lookup, a `MediaPlayer:setContentSize(...)`, etc. Making the
attribute live means that derived computation has to move **inside** a
`rec:composite(redraw, attrs)` (or `rec:dimComposite`) closure bound to every
attribute involved, so it re-runs and re-applies to the actual node whenever any
dependency tag fires. That is a structural change to each function, not a
find-and-replace.

Some of these closures already exist (for other attributes) and can likely be
extended to also read the geometry attrs and add them to the `attrs` dep list. Others
(the plain one-shot builders: `image`, `image_tap`, `image_gif`, `image_cond`,
`shape`'s image-mask branch, `rounded`'s non-shader branch, `video`, `map`) have no
redraw closure today and need one added from scratch.

### Per-builder task list

For each, "today" = current Tier-1-only state; "needed" = what Tier 2 must add.

1. **`Builders.image` / `Builders.gif` (alias) / `Builders.image_tap` /
   `Builders.image_gif`** (lines ~932–989)
   Today: `setBaseScale(rec, cs.width, cs.height, rec:evalNum("width",...),
   rec:evalNum("height",...))`, one-shot.
   Needed: wrap in a redraw closure that recomputes `setBaseScale` AND re-applies
   `rec.visual:setScaleX(rec.bsx); rec.visual:setScaleY(rec.bsy)` (mirrors the
   post-builder application at the main build loop, line ~3042), bound to
   `{"width","num"}, {"height","num"}`. NOTE: `rec:composite()` only calls the
   redraw immediately if the composite ends up *static* (no dynamic deps) — for a
   genuinely dynamic case it does NOT fire until the first dependency tag changes,
   so an explicit initial call before registering the composite is required too, or
   `rec.bsx/bsy` sit at their `WFLayerRec.new` default of `1,1` (i.e. the ORIGINAL
   bug) until the first tag event. (This exact detail caused a false start earlier —
   see "Design notes" below.)

2. **`Builders.image_cond`** (lines ~1159–1183)
   Today: base cell size (`cw, chh` from `width`/`height`) computed once; only
   `cond_value`/`color`/`color_dim` are live via `dimComposite`.
   Needed: fold `width`/`height` into the same `dimComposite` deps list; `cw, chh`
   must be recomputed inside the redraw closure (they currently feed
   `sp:setTextureRect(col*cw, row*chh, cw, chh)` — this needs the fresh values each
   fire, not the captured-once locals).

3. **`Builders.shape`** (lines ~1185–1276) — two sub-paths:
   - Image-mask branch (~1188–1216): same pattern as #1 (one-shot
     `setBaseScale` + effect-copy scale in `bindEffects.makeCopy`, which currently
     captures `rec.bsx`/`rec.bsy` read at copy-creation time — verify copies made
     *after* a live resize pick up the new scale, or re-scale existing copies too).
   - DrawNode primitive branch (~1218–1276): `w, h` (from `width`/`height`) are
     captured by `drawPrim`, the shader white-quad path, AND `bindEffects` (`baseX`,
     `baseY`, `makeCopy`). The existing `dimComposite` (line ~1258) already redraws
     `drawPrim` on `color`/`color_dim` — extend its deps to include `width`/`height`
     and read them via `evalNum` *inside* the closure instead of the outer `local w,
     h`. Effect-copy anchoring (`baseX`/`baseY` in `bindEffects`, line ~1267) is
     computed from `w, h` once at `bindEffects` call time — check whether that also
     needs to move into a redraw path.

4. **`Builders.rounded`** (lines ~1278+) — has a shader branch and a non-shader
   branch (need to read the full function; not yet inspected past line ~1284 as of
   this writing). `w, h, r0, cor` need to become live-recomputed, likely via a new
   or extended composite covering `width`, `height`, `radius`, `corner_type`.

5. **`Builders.ring`** (~1318+) — `radius_outer`, `radius_inner`, `outside_opacity`.
   Ring geometry is already procedurally redrawn (it has an `angle` composite for the
   sweep animation) — check whether that existing redraw closure can simply also
   read these three via `evalNum` and get them added to its deps list.

6. **`Builders.progress`** (~1364+) — `width`, `height`, `angle`, `margin`,
   `radius`, `corner_type`, `outside_opacity`. Similar procedural-redraw shape to
   `ring`.

7. **`Builders.markers`** (~1433+) and **`Builders.markers_hm`** (~1470+) —
   `m_count`, `radius`, `m_width`, `m_height`, `squarify`. These draw N tick marks
   per redraw; check existing composite wiring (likely tied to a rotating/second
   hand tag already) and extend.

8. **`Builders.video`** (~1518+) — `width`, `height` feed
   `mp:setContentSize(cc.size(w, h))` once. Live-resizing a `MediaPlayer` mid-play is
   an unusual, higher-risk operation — confirm axmol's `MediaPlayer` supports
   `setContentSize` after `play()` before wiring this one live; may be reasonable to
   explicitly scope out with a comment rather than guess.

9. **`Builders.map`** (~1570+) — `width`, `height` feed the tile-scale application
   inside `onMapPush` (already a callback, fired per bridge tile push, so `w, h` just
   need to be read live from `evalNum` *inside* `onMapPush` instead of the outer
   capture — low risk, no new composite needed here, just move the read).

10. **`Builders.series`** (~1660+) — `text_size`, `text_size2`, `spacing` drive a
    stepped-digit layout. Check how/whether this already redraws on a tag change.

11. **`Builders.calendar`** (~1737+) — `margin_x`, `margin_y`, `text_size`,
    `outside_opacity`, `dow_start`, `no_letters_header`. Large, complex grid-layout
    function — likely already has significant composite/redraw machinery for the
    calendar grid itself (date changes); extend deps rather than build new.

12. **`Builders.chart`** (~1954+) — `width`, `height`, `line_width`,
    `marker_size`, `bar_thickness`, `top_opacity`, `bottom_opacity`, `radius`,
    `corner_type`. Procedural chart redraw — same pattern as ring/progress.

13. **`Builders.text_ring`** (~2195+) and **`Builders.text_curved`** (~2309+) —
    `radius`, `text_size`, `squarify`, `show_every`, `angle_start`, `angle_end`.
    Curved-text layout redraw.

14. **`bindTransform`'s `gyro`** (line ~2598) — currently `rec.gyro =
    rec:evalNum("gyro", 0)` decides ONCE whether to activate the gyro-parallax
    composite at all (`if rec.gyro ~= 0 then ... end`, line ~2599). A dynamic `gyro`
    expression that starts at 0 would never activate parallax even if it later
    becomes nonzero. Lowest priority / most obscure case — flag for a decision on
    whether it's worth handling (the "activate a sensor stream" decision doesn't fit
    the redraw-closure pattern used elsewhere) rather than silently deferring it
    again.

15. **Main build loop's `skew_x`/`skew_y`** (lines ~3046–3047) — currently one-shot
    `holder:setSkewX/Y`. Should become `rec:bindNum("skew_x", 0, function(r, v)
    r.holder:setSkewX(v) end)` etc., mirroring the adjacent `rotation`/`anim_scale_x`
    bindings in `bindTransform` exactly (line ~2607-2609) — this one's a clean,
    low-risk conversion, probably do it alongside `bindTransform` (#14) since it's
    the same function's neighborhood conceptually (holder-level transform).

## Design notes / pitfalls hit during Tier 1 investigation

- **`rec:composite(redraw, attrs)` does not call `redraw` immediately unless the
  composite is fully static** (`#deps == 0`, see `wf_render.lua` line ~404-410). For
  every builder converted to Tier 2, if you rely solely on `composite()`'s own
  immediate-call behavior, the FIRST frame for any genuinely dynamic case will use
  whatever the record's field defaults are (`rec.bsx/bsy = 1,1` from
  `WFLayerRec.new`, i.e. **the original bug**), not a correct value — until the
  first dependency tag actually fires an event. Either accept that (matches how
  `image_cond`'s `cond_value` already behaves — a brief default-state flash until
  first tag fire) or explicitly call the redraw function once immediately in
  addition to registering the composite. Pick ONE convention and apply it
  consistently across all 15 sites — don't mix.
- `image_cond` (item 2 above) is the closest existing precedent for "sprite geometry
  driven by a composite" — read it closely before implementing items 1 and 3.
- `ring`/`progress`/`chart`/`markers`/`calendar`/`text_ring`/`text_curved` almost
  certainly already have SOME composite/redraw wiring (for their animated sweep/tick/
  date attributes) — the right move for those is very likely "add attrs to an
  existing deps list and read them via evalNum inside the existing closure," not
  "build a new composite from scratch." Read each function in full before editing.
- Effect-copy paths (`bindEffects`, used by `image`'s shape-mask branch and the
  DrawNode shape branch) capture geometry into copy-creation closures
  (`makeCopy`) — verify whether copies created before vs. after a live geometry
  change need to be reconciled, or whether that's acceptable to leave as a known
  minor limitation (document the decision either way).

## Verification plan

1. `luac -p wf_render.lua` after every edit (catches syntax errors immediately,
   cheap).
2. `./gradlew :app:installDebug` + reload the currently-active watchface
   (`adb shell am force-stop com.ailife.clox && adb shell am start -a
   android.intent.action.MAIN -c android.intent.category.HOME
   com.ailife.clox/.MainActivity`) — check logcat for `[WFRender] build ...` error
   lines (each builder's `pcall` prints one per-layer build failure) and
   `[WFBinding] apply ...` lines (per-binding runtime failure).
3. **Regression risk is broad**: because this touches ~15 builders used by
   essentially every watchface on the device, re-test a representative sample of the
   already-installed `.watch` files
   (`/storage/emulated/0/Android/data/com.ailife.clox/files/watchfaces/*.watch`,
   switchable live via the `com.android.watchengine.changeface` broadcast — see
   `ChangeFaceReceiver.kt`, extra `faceName` = base filename without extension) —
   not just the one heart-icon face — since a mistake in, say, `Builders.ring` would
   silently affect every watchface using rings, not just this one.
4. To confirm a genuinely dynamic case actually animates live (not just "is
   correctly sized once"), you need a tag whose value visibly changes without an app
   restart — heart rate needs a live/emulated sensor feed (confirmed `{shr}` sits at
   a static 0 with no HR data pushed via the bridge in this dev environment); `{drss}`
   (seconds) changes every frame on its own and is a safer, always-available
   dependency to verify live-redraw wiring against.

## Files involved

- `app/src/main/assets/lua/wf_render.lua` — all builder functions, `setBaseScale`,
  `WFLayerRec:composite`/`dimComposite`/`evalNum`/`bindNum`.
- `app/src/main/assets/lua/wf_binding.lua` — `WFBinding`/`WFSetterBinding`/
  `WFCompositeBinding` (the tag-bucket dispatch machinery; should not need changes).
- `app/src/main/assets/lua/wf_expr.lua` — `WFExpr.compile` (dependency extraction;
  already confirmed correct for multi-tag expressions, should not need changes).
- `app/src/main/assets/lua/wf_engine.lua` — `WFEngine:bind`/`:activate` (tag-bucket
  registration; already confirmed correct, should not need changes).
