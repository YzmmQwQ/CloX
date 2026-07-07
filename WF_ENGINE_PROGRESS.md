# Watchface Engine Rewrite — Progress Tracker

Goal: tag-indexed, event-driven, OOP update engine for the Watchmaker renderer
(app/src/main/assets/lua/). Performance is the top priority.

Spec (from user, 2026-07-06):
- On import: index all layers by the tags each property uses; put (property,
  compiled expression) into that tag's update bucket.
- Events: `ms` fires every frame; second/minute/hour/day fire on boundary;
  Java bridge pushes fire per-category events. An event first propagates to its
  tags (ms → ms rotation etc.), then each changed tag updates the layer
  properties in its bucket.
- OOP for everything; no simplification.
- Global script: top-level runs once; on_millisecond every frame, on_second /
  on_minute / on_hour on demand; variable writes propagate to layer properties.
- Expressions: compile the attribute string as Lua code once at import
  (load()); NO regex/gsub in the runtime path. Import may be slow.
- Sandbox: global script + expressions run in a restricted env (no luaj, cc,
  Bridge, io, load, require, debug) so faces can't damage the launcher or
  escape into Java.
- No file over 2500 lines.

## Files

| file | status | purpose |
|------|--------|---------|
| wf_sandbox.lua | DONE | WFSandbox class — restricted env |
| wf_expr.lua    | DONE | WFExpr — import-time attr → compiled fn + deps |
| wf_tagdefs.lua | DONE | per-tag {event, compute} catalogue + pattern families |
| wf_engine.lua  | DONE | WFTag / WFEvent / WFEngine classes |
| wf_binding.lua | DONE | WFBinding / SetterBinding / CompositeBinding |
| wf_script.lua  | DONE | WFScript — script.txt host, vars, wm_*, tweens |
| wf_render.lua  | DONE | rewritten onto engine (builders kept) |
| watchface.lua  | DONE | tick loop fires engine events |
| wf_tags.lua    | DELETED | superseded by wf_tagdefs + wf_expr |
| wf_cadence.lua | DELETED | superseded by event field in wf_tagdefs |

## Verification
- [x] luac -p syntax check all lua files (lua5.4 — all pass)
- [x] host smoke test (scratchpad/smoke.lua): 24/24 — templates, num exprs,
      ternaries, bucket firing/dedup, bridge push, script var propagation,
      tweens, sandbox containment, unknown tags
- [x] assembleDebug build (BUILD SUCCESSFUL, foreground)
- [x] on-device run (RFCT31XYHNZ): default face 8 layers/9 bindings + real
      face "AOSP experience" 19 layers loaded, frame+second cadence running,
      zero engine errors in logcat
- [ ] on-device: a face with scripts/script.txt (none installed yet)

## Notes / decisions
- `clamp()` errors on converted faces (2026-07-06): faces converted by an OLD
  wftools build carry raw WFF built-ins (clamp/…). UL reference launcher has
  no such globals either → decision: do NOT add them to CloX. Fix lives in
  wftools; plan written to
  `wftools/docs/10-WFF-EXPR-FUNCTION-COMPAT-PLAN.md`. The new engine merely
  made the long-standing breakage visible (old engine silently defaulted).
- Lua 5.4 (Axmol v2.11.3): use load(src, name, "t", env); no setfenv/loadstring.
- Tag value formats preserved from old wf_tags.build (z2 strings etc.).
- Y_SIGN=-1 position flip stays in the y applier (wf_render).
- Reference script contract from UL WatchSkin.lua: on_millisecond(dt),
  on_second(minute, second), on_minute(), on_hour(), wm_schedule tweens,
  {c_...} counters, vars as bare globals + {var} tags.
- Keep all print/log statements (global rule).

## Rendering alignment pass vs UL / wftools (2026-07-07)

Cross-referenced wf_render builders against the UL ground-truth renderer
(`wmformat/UL lua renderer/WatchSkin.lua` + `shaders/Ring.fsh`,
`shaders/Rounded.fsh`) and the wftools Compose preview
(`wftools .../WmPreview.kt`). User-confirmed decisions:

- **ring** (UL/wftools semantics; the old WM_FORMAT_1.0 §7.3 text was WRONG
  and has been corrected): fill = gradient color→color2 laid over angle_total
  and revealed by angle; color3 = UNFILLED track at outside_opacity;
  beyond angle_total = transparent (no color3 band there); rounded end caps
  (half-thickness discs, flat cap colors) on both fill and track; layer box =
  2×radius_outer (alignment + taps).
- **progress** (wftools fill-axis + UL shader everywhere else): `angle` is the
  fill AXIS, not a rotation — 90/absent = horizontal L→R, 0/180 = vertical
  bottom→top (doc said the reverse; fixed). UL itself ignores angle. radius
  defaults to 50 (pill), clamped to half min dimension. corner_type (§7.12
  table, shared w/ rounded) rounds border+track corners and gates the fill's
  START corners at pill radius; end_style rounds the LEADING edge (default
  flat). Fill gradient color→color2 spans the FILLED portion (color2 always at
  the leading edge — UL, not wftools' fixed-span gradient). color3 track +
  color4 border drawn ONLY when margin>0 (margin=0 → unfilled area
  transparent); track = color3 blended over color4 by outside_opacity, opaque.
- **double-opacity fix**: axmol DrawNode multiplies node displayedOpacity
  (u_alpha) over vertex-color alpha, so opacity must NEVER be baked into
  composite fill colors — the holder's opacity binding is the single
  application point (was squaring: 50% rendered as 25%). Same for Labels
  (setTextColor alpha stays 255).
- **dim mode**: UL swaps color→color_dim for EVERY layer type. Added colAttr()
  pick to shape/rounded/image/image_cond/text tints + WFLayerRec:dimComposite —
  static color_dim composites register in renderer.dimRedraws and re-run on
  setDim (bound ones re-fire via engine:repaint()).
- **UL sizing quirks**: setBaseScale adopted — non-square texture + square
  authored box scales BOTH axes by the width ratio (aspect preserved).
  UL's TEXT rule (scale label to contentHeight == 1.6×text_size) was tried and
  **REVERTED same day**: axmol Label content-height metrics differ from UL's
  cocos2d-x 3.x, so native faces rendered text too LARGE (~25%) and
  metric-heavy fonts (NBP etc.) too small. TTF stays fontPx(1.28×text_size)
  unscaled; bitmap fonts scale per-line to 1.6×text_size. Do not re-apply the
  UL rule without device-measuring axmol label metrics first.
- **shape**: Heart primitive added (two-lobe approximation; UL uses
  shape_heart.png masks). Known cosmetic gap: DrawNode edges are aliased vs
  UL's AA textures.
- Known remaining deltas (deliberate, minor): ring/track alpha multiplies with
  layer opacity via holder (UL's shader draws track at flat outside_opacity
  ignoring node opacity); markers geometry (UL divides m_width/height by 1.5
  and places at radius−m_height) and markers_hm tick sizes differ from UL —
  not yet reconciled; UL's odd "missing opacity attr → hidden" default not
  copied (we default 100).
- Headless smoke harness: scratchpad/wf_harness.lua (9-layer face incl. both
  progress axes, ccw gradient ring, dim flips, aspect/text-scale asserts) — passes.
- 2026-07-07: dotted tween references in attributes ("double time" bug).
  Faces gate layers with opacity="tweens.screen1" (script inits screen1=100
  screen2=0, taps crossfade) — identDeps split the ident at the dot ("tweens"
  unknown, "screen1" no var_ prefix) and looksLikeExprNum doesn't classify a
  single dotted name, so the opacity compiled to NOTHING and both the 12h and
  24h time layers rendered at full opacity. identDeps now scans for
  tweens%.NAME dotted references first and registers the full dotted dep
  (engine's "tweens." definePrefix resolves it). Unit-tested (init state +
  crossfade toggle) and verified on device: single time display.
- 2026-07-07: attribute COMPILE failures now surface in the error dialog
  (WFExpr.onError hook → WatchfaceBridge.reportError, wired per load by
  wf_render next to WFBinding.onError; first error per face load, dedup via
  WFBinding.errorReported; the face still renders through the
  template/literal fallback). Previously compile failures only printed to
  logcat — the NBSP bug shipped invisible. Verified on device with a
  deliberately broken expression: dialog shows the Lua error + generated src.
- 2026-07-07: NBSP in expressions ("moon phase shows raw ternary"). The
  ProTrek face's {wmp} ternary contains a U+00A0 NO-BREAK SPACE (looked like
  a double space) — Lua's lexer rejects the raw 0xC2 byte outside string
  literals ("unexpected symbol near '<\\194>'"), the expression compile
  failed and the SILENT template fallback displayed the raw ternary text.
  exprSrc now normalises NBSP→space OUTSIDE quotes (inside string literals
  it stays verbatim — legal and possibly intentional display text). Also
  {wmp} itself: Kotlin pushes the raw lunation FRACTION (0=new .5=full);
  WM's {wmp} is the integer 0..9 index (1=young 5=full 9=old) faces compare
  with == — the tag now converts (floor(f*10)%10, >1 passes through).
- 2026-07-07: wm_schedule SEQUENCE form + variable tweens (ProTrek tap
  actions "didn't work"). Taps dispatched fine (script:Fade(n) ran) but our
  wm_schedule only accepted the single-table form {tween=..} and silently
  dropped WM's documented sequence form {{action='sleep',sleep=3},
  {action='tween',tween='var_x',from,to,duration,easing}} — Fade() did
  nothing. Rewritten: both forms; sequence steps accumulate start time
  (sleeps + tween durations); tween values land in the script VARIABLE
  itself via publishTween (vars[name] + engine:publish — WM semantics:
  layers read {var_x}) plus the legacy {tweens.NAME} alias; the tween list
  is an ARRAY (name-keyed lost queued tweens on the same variable — Fade
  schedules fade-in now + fade-out at +9s on one var); delayed tweens don't
  write until their window starts. Unit-tested with the exact Fade(9) body
  (75 mid-in, hold, 25 mid-out, drained) + on device: tapping the hotspot
  fades the world-time overlay in/out.
- 2026-07-07: bare script-variable TEXT attributes fixed (ProTrek face showed
  literal "var_s_date"/"var_s_wrpt" names). Two wf_expr gaps: (a) the text
  kind's no-tags branch never considered bare identifiers (the num kind did)
  — now identDeps runs first and a compilable ident-bearing text becomes a
  dynamic binding with a pcall fallback to the literal (prose stays literal:
  multi-word text isn't valid Lua, compile fails); (b) vars assigned ONLY
  inside script functions (var_s_c2t at line 492 of the face's script) were
  unknown at layer-compile time → identDeps now treats any var_* identifier
  as a dep by WatchMaker convention: activation yields neutral 0 and the
  script's first write publishes into the same tag, firing the binding.
  Verified: unit tests (pre-write 0, post-write value, updates, prose
  literal) + ProTrek face now renders like its preview incl. the scripted
  weather graph and screen toggles.
- 2026-07-07: bare-boolean ternaries fixed ("{swr} and 'STOP' or 'START'").
  Two wf_expr gaps: (a) looksLikeExprText required a comparison next to
  and/or, so comparison-less ternaries rendered as literal text — now
  and+or together also classify as expression (false positives still fall
  back to the template); (b) our boolean tags are the STRINGS
  "true"/"false" — both truthy — while UL splices bare boolean literals.
  Unquoted tag splices in expressions now go through __wb() (sandbox
  helper): coerces exactly "true"/"false" back to booleans, passes all
  other values through; quoted splices ('{swr}'=='true') stay strings.
  Unit-tested (text+num ternaries both states, quoted compare, templates,
  numeric passthrough, label classification) + user face renders STOP/START
  correctly with zero luaError.
- 2026-07-07: stopwatch implemented. wf_stopwatch.lua = one process-global
  state (UL parity: survives face switches; wf_render rebases startAt on load
  since the engine clock's elapsed restarts per face). {sw*} tags live on the
  ms event in wf_tagdefs: swr ("true"/"false" string), swh/swm/sws/swss/swsss
  numeric (UL stopped padding them), tswh/tswm/tsws/tswss/tswsss zero-padded
  strings, swsst/tswsst total ms, swrm/swrs/swrss rotations (swrss = second
  hand + 6*ms/1000 smoothing). sw_start_stop / sw_reset handled in
  WFRender:runAction (renderer level, like UL — WM_TAP_ACTIONS.md §6
  updated); each fires "ms" for an instant repaint. NOTE: a face with {sw*}
  tags keeps the frame loop running even when stopped (ms event active) —
  same as UL renders; acceptable, revisit if watch battery shows it.
  Verified: unit tests (run/stop/resume/rebase/reset + h:m:s breakdown) and
  on-device tap cycle (running count + smooth ring arc → frozen across 2s →
  reset to zero).
- 2026-07-07: compass/sensors dying after activity or fragment recreation —
  TWO stacked bugs. (a) A recreated Kotlin bridge starts with blank
  sensor/health/weather state while Lua's applyFeatures differ remembers
  everything as enabled → nothing re-sent, sensors stay off until a face
  reload. Fix: WatchfaceOnBridgeRestart global (scene onEnter) — resets
  _activeFeatures, re-announces from renderer.features, re-emits wf_lua_ready
  (full snapshot); the new manager queues that call in start() (no-op on a
  fresh engine). (b) HomeFragment create-before-destroy interleave: the OLD
  instance's stop() ran AFTER the new start() and NULLED
  LuaBridge.watchfaceActionHandler/luaReadyHandler — every wf_action (sensor
  enables!) silently dropped; SensorHelper never registered despite the
  enables being logged. Fix: identity-guarded stop() (only clear hooks that
  still point to this manager's closures). Verified: fresh start + dark-mode
  recreation round-trip → SensorHelper active with gyro+mag+accel and compass
  pushes flowing (21 in the last 100 log lines).
- 2026-07-07: full Penner easing catalogue (wf_script EASING). Faces call
  WatchMaker's SHORT names with the 4-arg Penner signature in attribute
  expressions — outElastic(t,b,c,d) etc. (gshock face errored "attempt to
  call a nil value (global 'outElastic')"). Rewrote EASING as Penner
  f(t,b,c,d[,s]) with b/c/d defaulting to 0/1/1 so the same functions serve
  wm_schedule's normalized f(p) calls, registered under BOTH schemes (short
  names + easeXxx aliases): linear/linearTween, in/out/inOut ×
  quad,cubic,quart,quint,sine,expo,circ,elastic,back,bounce, plus generic
  outIn× variants (UL defines outInExpo/outInBack). Verified: unit asserts
  (boundaries, midpoints, normalized compat) + the gshock face loads with no
  luaError.
- 2026-07-07: gyro parallax implemented (bindTransform). UL formula in screen
  coords: x += sgy*gyro, y += sgx*(-gyro) → cocos y-up: holder pos =
  (baseX + sgy*g, baseY + sgx*g). x/y bindings now store rec.baseX/baseY and
  place through applyPos; a gyro≠0 layer activates the sgx/sgy tags (which
  flips the gyroscope stream on through the normal feature pipeline — zero
  new Kotlin) and binds a composite on them that re-places the holder per
  sensor push (~UI rate). Verified: harness asserts exact offsets + rest
  restore; on device the gyroscope registers on face load and pushes flow.
- 2026-07-07: video + map layers implemented. VIDEO uses axmol's IN-SCENE
  ui.MediaPlayer (AndroidMediaEngine → MediaCodec decodes straight into a GL
  NV12 texture, composited in z-order) — NO frame pre-extraction like UL's
  cocos2d-x VideoFrameExtractor path. The axui.MediaPlayer Lua binding ships
  with axmol (registered via register_ui_module under AX_ENABLE_MEDIA, which
  is ON for Android) — zero C++/Kotlin needed. setStyle(NONE),
  keepAspectRatio(false) (WM stretches), looping, muted-by-content; videos
  STOP in dim mode and on face clear() (codec/AudioTrack hygiene — no pause
  binding, stop/play restarts the loop which is fine for ambient videos).
  MAP: one OSM tile centred on lat/lon (UL behaviour) — Lua computes the
  slippy tile x/y, requests via wf_action requestMapTile, Kotlin
  MapTileHelper downloads to cacheDir/map_tiles (OSM UA policy header, LRU
  trim at 64 tiles) and pushes a "map" category {file, zoom, tx, ty}; the
  layer matches tx/ty and textures the sprite scaled to width/height ×
  map_scale%. lat/lon may be dynamic tags (tile re-requested only when the
  tile actually changes). No GPS {alat}/{alon} tags yet — authored coords
  work. Verified on device (mv-test.watch): testsrc2 mp4 animating in-scene
  + Eiffel Tower tile at z15 under a live seconds clock.
- 2026-07-07: bitmap-font text must ALWAYS be uppercased (UL rule:
  createWithBMFont(fnt, text:upper()) everywhere) — BM atlases usually carry
  only uppercase glyphs, and lowercase text rendered BLANK with no error
  (only native "LabelTextFormatter: can't find letter definition" logcat
  warnings). Fixed Builders.text (creation + setString via caseOf) and the
  series updater; text_curved/text_ring/calendar BM paths already uppercased.
- 2026-07-07: series + calendar layers implemented (ports of UL WatchSkin).
  series: UL expands to offset text layers at parse; ours is one node with
  2n+1 labels updated on the owning clock event (minute/hour/day) — window 7
  for time series, 3 for ddw/dd_ddw, 5 for dnnn; values computed by offsetting
  real epoch time (wraps correct); current item font/text_size/color,
  neighbours font2/text_size2/color2, offset spacing+text_size (CURRENT size,
  UL rule); vertical: past above/future below; current_pos "m", "f"/"u"/"t"
  (current first), "l"/"b" (current last) — doc's t/b naming mapped onto UL's
  f/l behaviour. calendar: header letters = FIRST no_letters_header letters
  (a count, not a flag — doc wrong); dow_start default 1=Monday; Zeller's
  congruence for the 1st; cells (text_size+margin) boxes; days BEFORE today
  dim to outside_opacity (not "outside month" — doc wrong); today = color2
  square + brightness-inverted numeral; header color, dates color2; grid
  anchored per alignment internally (UL offsets); rebuilt on the day event.
  Verified on device (sercal-test.watch): dmz picker centred on the live
  minute, ddw horizontal with correct today, July 2026 grid correct (1st on
  We, 7 highlighted, 1-6 dimmed).
- 2026-07-07: chart layer implemented (Builders.chart), ported from UL
  createChartLayer/renderChart. Line charts stroke with color3 + color4 dots
  (NOT color/color2!); bar charts fill with `color` faded bottom_opacity→
  top_opacity (color2 parsed by UL but never drawn); min_max is "MAX to MIN"
  (first value max — doc said "min;max", fixed); data_points = semicolon-
  separated expressions compiled via WFExpr at import, chart redraws as a
  composite bound to the union of all data-point + color deps; lines
  (H/V/MAX/MIN/AVG, Solid/Dashed) and labels (P per-point w/ step=ceil(n/max),
  A axis L/R/T/B, S static) ported — label {value0dp}-style templates are
  precompiled to segment lists so the redraw stays pattern-free; bar corner
  rounding via per-slice width insets (CORNERS table); x slots at
  (i-0.5)*w/n, y=(v-min)/range*h, centre-anchored box. Not implemented (UL
  doesn't either): tension smoothing, chart_group sync. Verified live on
  device (chart-test.watch): {ds}-driven point/bar move each second, AVG line
  tracks, gradient + rounded bottoms + axis/point labels correct.
- 2026-07-07 (round 2): watchdog false positives eliminated for real. Two
  root causes the probe version missed: (a) CocosManager.onDestroy guarded by
  view.context — but a RE-PARENTED view keeps the CREATING activity as its
  context, so the old activity's late onDestroy still nulled the live view
  (cascade: false "GL view is gone" dialog on face change mid-selector →
  restartProcess loop). Now guarded by an explicit hostActivity updated on
  every init/reuse. (b) a single queued probe is lost forever if the view
  detaches right then (GLSurfaceView kills its GLThread on detach; queued
  events on the dying thread never run) → false "GL thread did not respond".
  Replaced with startRendererWatch: STRIKE-BASED — each WATCHDOG_MS round
  re-acquires the CURRENT GL view and queues a fresh probe; success (wf_loaded
  when expected / probe ran in liveness mode) ends silently, paused GL resets
  strikes, and only TWO consecutive failed rounds (30s) alarm. Null view
  counts a strike only while it STAYS null (recreation gaps self-heal next
  round). Verified: selector round-trips + face switches + double recreation
  → zero false triggers (and the reuse path now survives recreation, only 1
  process restart instead of cascades); hang-face true positive still fires
  at 30s.
- 2026-07-07 (round 1, superseded by round 2 above): watchdog false-positive fixed. On activity recreation with the
  reused GL view, the face is ALREADY loaded — no wf_loaded ever arrives, so
  the startup watchdog false-alarmed 15s after every recreation (log:
  "Reusing live AxmolGLSurfaceView" at 04:44:45 → "watchdog(startup)" at
  04:45:00). Startup now uses a GL-thread liveness PROBE (queueEvent no-op;
  hung Lua still never executes it → real wedges still caught) instead of
  demanding a face load; and BOTH watchdogs defer (re-poll) while
  CocosManager.isPaused() — a screen-off/backgrounded GL is not a dead
  renderer. Face-change watchdog still demands wf_loaded. Also observed:
  dark-mode toggle recreates the activity in destroy→create order → view
  gone → clean process auto-restart path (by design, ~1s relaunch).
- 2026-07-07: shape vector geometry calibrated against the UL mask textures
  (user chose vector drawing over bundling the PNG masks). UL renders shapes
  by stretching 512×512 mask textures that carry INTERNAL PADDING — heart
  spans y 4..96% of its box, hexagon is a regular hexagon at 86.6% height,
  triangle y 7..93% — so idealized full-bleed polygons were oversized and
  misaligned. Outlines were alpha-traced from
  clock-skin-coco-hack/assets/res/shape_*.png (polar sampling from centroid +
  Douglas-Peucker) and baked into SHAPE_PTS (box units, fan-filled from the
  centroid): triangle 3 pts, pentagon 5, hexagon 6, star 10 (real inner
  radii, point-up), heart 26. Circle/square stay analytic (masks are
  full-bleed). fillPolygon removed. Shape test face shape-test.watch on the
  phone shows all six matching the reference proportions.
- 2026-07-07: text_ring rewritten to UL createTextRing semantics. Label sets:
  named (I-XII uses "IIII"; Mo-Su/Mon-Sun start at SUNDAY; Jan-Dec added) plus
  generic numeric "A-B" and "A to B" ranges (reversible) — previously only 4
  hardcoded sets, so ring_type="1-60" silently fell back to 1-12. Placement:
  item k sits k steps PAST 12 o'clock → the LAST value lands at 12 (classic
  12-at-top clocks, 60-at-top minute rings); we had item 1 at 12 (off by one
  slot). show_every keeps values that are MULTIPLES of it (5,10,…,60), not
  (i-1)%N. rotated_text "ru"/"ri" (flip upright on bottom/top half) added.
  BM-font rings: UL's radius/1.6 quirk kept. NOTE: wftools WmPreview.kt
  text_ring indexes from 0 (label 1 at top) — disagrees with UL and with
  wftools' own generated preview.jpg; UL adopted, wftools preview needs the
  same fix eventually.
- 2026-07-07: black-screen wedge fixed + renderer watchdog added (Kotlin).
  Root cause: MainActivity recreation in a live process — HomeFragment re-ran
  CocosManager.init (axmol native CANNOT re-init per process) and the OLD
  activity's late onDestroy unconditionally nulled the manager's glView, so
  every queueOnGL was "skipped — GL view not ready" forever: black face, dead
  bridge, zero errors. Fixes in CocosManager: reuse (re-parent) a live GL view
  on recreation; owner-guarded onDestroy(activity); if the engine ran but its
  view is gone → restartProcess (inexact alarm relaunch — setExact needs
  SCHEDULE_EXACT_ALARM — plus the system auto-restarts home anyway).
  WatchfaceBridgeManager: wf_loaded doubles as the liveness signal — watchdog
  armed at bridge start, on watchface_path pref change, and on
  setActive/reload/reset; 15s without wf_loaded (or GL view already null) →
  "Watchface renderer not responding" dialog with Restart launcher button;
  dialog auto-dismisses if wf_loaded arrives late. luaError dialog no longer
  DEBUG-gated — errors always show. End-to-end tested with a script that
  hangs the Lua VM (while true do end): change-trigger dialog fired, restart
  worked, startup-trigger dialog fired in the fresh process.
- 2026-07-07: text_curved rewritten to the UL algorithm (WatchSkin:
  createTextCurved): per-glyph widths measured via Label:getLetter off a
  whole-string label, every char advances by the widest glyph's angle (spaces
  half), curve_dir Up = arc over the TOP of the circle at (x,y), Down = arc
  under the BOTTOM (upright, readable), glyphs rotated to the tangent, BM font
  support, rebuilt on text change via bindText (was: static fixed-width guess
  at the top for both directions → scrambled overlap + stale time). Literal
  quotes around converted faces' text ('...') are a wftools CONVERTER issue,
  deliberately NOT worked around in CloX.
- 2026-07-07: unknown tags now resolve to NUMBER 0 (UL parity — evalString
  substitutes '0' for nil globals); "" default type-errored relational
  compares (`{dtp} >= {wsrp}` on wf505jb's sunrise screen, only reachable via
  tap → short-circuit hid it until then). Added missing {wsrp}/{wssp} tags
  (sunrise/sunset as fraction of day, byte-walk "HH:MM" parse, no patterns).

## Effects, stencil & shape shaders (2026-07-07)

- **sub-attr expressions** (wf_expr): tag-less "sub" attributes (colors,
  paths) referencing known bare identifiers now compile as LIVE expressions —
  UL evaluates ANY attribute containing script variables as code, and faces
  write color="var_col_time[var_i_t]" for tap-to-cycle color schemes. Our
  "sub" kind froze those as literal strings, the hex parse failed and the
  layer stuck white forever. Runtime failure still falls back to the literal,
  so plain hex colors/paths are unaffected.
- **text/shape effects** (WM_EFFECTS §1–2, shared bindEffects in wf_render):
  Glow = rings of 8 offset copies of the visual (UL formula: ring r at 2r px,
  opacity (o/rings)·(1−(r−1)/rings)) — rings CAPPED at 8 for the watch GPU
  with the step widened to keep the authored o_size spread. Outline = native
  TTF stroke (enableOutline) for TTF text; 8 copies pushed out o_size for
  shapes and non-TTF text (UL parity). Shadow: Drop = 1 copy at w_distance
  opposite the w_angle light, Flat = floor(w_distance) stacked copies at
  1..n. Copies live in the layer holder (inherit transform/anim_scale/
  opacity/visibility); colors/opacities/offsets stay live via composites;
  copy COUNT is fixed at build (dynamic o_size/w_distance keep initial count).
- **text stencil** (WM_EFFECTS §6): glyphs become see-through cutouts in a
  FULL-CANVAS plate of the layer color. A Label can NOT be an alpha-threshold
  ClippingNode stencil (the alpha-test program doesn't reach its internal
  commands — glyph QUADS punch rectangles); like UL, the glyphs are baked
  into a 512² RenderTexture and that sprite masks an inverted ClippingNode.
  RT + mask label are off-graph: retained explicitly, released in
  WFRender:clear() (renderer.retained list). Plate redraws only on color/dim;
  text changes re-render just the RT.
- **shape shaders** (WM_EFFECTS §3–4): the UL fragment shaders ship VERBATIM —
  Source/shaders/wm_*.frag (GradientLinear, GradientRadial, Rainbow, HSV,
  RGB, Segment, SegmentBetween, Radar, Progress, ProgressBetween), only the
  I/O header adapted to axmol #version 310 es (fs_ub std140 block; UL's
  u_opacity arrives as v_color.a). axslcc auto-compiles Source/shaders →
  assets/axslc/custom/ (existing gradle preBuild copy; NOTE: a brand-new
  shader file needs CMake reconfigure + one extra build for the asset copy).
  Lua side: ONE backend Program per shader per app run (shaderPrograms cache;
  axb.ProgramManager:loadProgram("positionTextureColor_vs",
  "custom/wm_X_fs", VertexLayoutType.Sprite=3)); each layer only carries its
  own ProgramState; u_* uniforms (colors → vec3, numbers → float; packed
  little-endian via string.pack for the byte-table setUniform binding)
  refresh through one dimComposite, so {drms}-driven uniforms animate
  per-frame exactly like UL. Image-mask shapes get the program on their
  sprite (texture alpha = mask, as UL); primitive shapes run it on a
  getWhiteTexture() quad — squares as-is, other primitives + rounded clipped
  through their DrawNode geometry via ClippingNode. #version must be the
  FIRST line of a .frag — axslcc rejects leading comments.
  **Doc erratum**: WM_EFFECTS.md's u_* tables for Progress and Radar are
  WRONG; the .fsh sources (and all-shaders.watch) are authoritative:
  Progress u_1=%, u_2=angle, u_3=opIn, u_4=opOut; Radar u_1=angle, u_2=opIn,
  u_3=opOut.
- Verified on device (all-shaders.watch): all 9 shaders + Segment-on-rounded
  render and animate at the ms cadence; logcat shows each wm_* program loaded
  exactly once (two Segment layers share one program). Effects verified with
  a one-effect-per-label test face; stencil cutouts show true letterforms.
- 2026-07-07 (follow-up): shaders extended to the full image family + text.
  image/gif/image_tap/image_gif: program straight on the sprite (texture
  alpha = mask; survives setTexture frame swaps). image_cond: same, texcoords
  span the CELL's atlas sub-rect (UL parity quirk). text + text_curved:
  labels carry font-ATLAS texcoords, so the glyphs are baked WHITE into a
  retained RenderTexture (bakedSprite helper — RT reallocates only when
  content outgrows it) and the shader runs over THAT sprite; the layer color
  arrives as sprite tint, and text changes re-render just the RT. Curved
  bake box = full circle bounds (2·(radius+2·size)). Stencil takes
  precedence over shader on text.
- 2026-07-07 premultiplied-alpha fix: the rgb-REPLACING shaders
  (GradientLinear/GradientRadial/Rainbow) must output PREMULTIPLIED color
  (rgb·a). PNG/RT textures blend with (ONE, 1−SRC_ALPHA); straight rgb output
  painted transparent texels solid — an image ring rendered as a solid
  gradient square and gradient text as a solid bar. Pass-through shaders
  (Segment/Radar/HSV/RGB/…) already emit premultiplied rgb (texture·v_color)
  and were correct. Verified on device: gradients respect texture alpha on
  images and baked text.

## Multi-screen face fixes (2026-07-07, multi-pantalla-2)

- **trailing `--` comments in expressions** (wf_expr): faces write
  "GetCalX(18)-tweens.anim0--{dd}"; the compiled chunk now puts its closing
  paren on its own line so the Lua comment can't swallow it (UL's loadstring
  survives because the comment ends at the line break).
- **bare identifiers are var_* ONLY** (wf_expr identDeps): matching any
  engine-known name misclassified literal text ("map bruj" — "map" is a tag)
  as an expression and fired the compile-error dialog. Now only
  frontier-anchored var_* idents and dotted tweens.* references count —
  the WatchMaker convention: layer-attribute variables always start var_.
- **tagged text with var_ refs compiles as expression** (wf_expr): UL
  compiles anything containing "var" as code — text="var_date1[{ddw0} +1]"
  (script-table indexing) has no operator/call syntax for the text
  heuristics and rendered as the substituted template. Runtime failure
  still falls back to the template.
- **wm_tag braces + nil** (wf_script): faces call wm_tag('{c1t}') — strip
  braces before resolve; unknown tags return 0 (never nil — scripts feed
  the result into string.len()).
- **non-numeric opacity hides** (wf_render): page gating
  "var_g==1 and 0 or var_l==1 and 100" evaluates FALSE on inactive pages;
  UL coerces tonumber(op) or 0 → hidden. Our 100 default stacked every
  page. Dynamic opacity now falls back to 0; a MISSING opacity attr stays
  opaque (const-nil path never calls the setter).
- **tap eligibility = UL exactly** (wf_render tapAt): eligible iff
  (tonumber(evaluated opacity) or 0) ~= -1. Off-page zones gate with
  "… or -1" (excluded); an ACTIVE page's zones often evaluate FALSE → 0 →
  tappable (open calculator: "var_g==1 and 0 or var_j==0 and -1");
  opacity-0 hot spots stay tappable; missing attr tappable. First attempt
  excluded non-numeric results and broke in-page buttons.
- Verified on device: menu opens correct pages (battery/weather), weather
  day-table indexing renders (miercoles), calculator computes 7+8=15,
  calendar script builds without errors.
- 2026-07-07: tagged "sub" attrs (colors/paths) get an EXPRESSION form —
  wf_297's day-of-week highlight writes color="({ddw0} == 0 or {ddw0} == 6)
  and 'FF0000' or '{ucolor}'", which the template-only sub path rendered as
  literal text → hex parse failed → white box hiding the white day label.
  When the substituted probe passes the strict text heuristic (comparisons +
  and/or — plain paths/colors stay on the template), exprSrc compiles with
  runtime fallback to the substituted template (UL evaluates every attribute
  as code). Verified: TU highlight in theme blue, battery dot colors live.
- 2026-07-07: UL evalText parity for tagged TEXT attrs — every one now
  QUIETLY attempts an expression compile (prose with tags fails silently and
  stays a template; compileChunk grew a `quiet` flag). Runtime keeps UL's
  guard: a NUMBER result is discarded when the substituted text is itself
  purely numeric (zero-padded "07" must not render "7"); false/nil results
  fall back to the template. Fixes "{wws}*1.6" wind-speed arithmetic
  (rendered literally — the heuristics only recognised ternaries/calls).
  Inherits UL's quirks knowingly: "{dd}-{dm}" evaluates to arithmetic.
- 2026-07-07: multi-value attributes — compileChunk retries as an
  assignment (`local __a = <src>`) when the paren form fails: UL's bare
  `return <src>` compiles comma-separated lists (cond_value="{nc}, {pws} >=
  0.86 and 1 or …") and its single assignment keeps the FIRST value.
- 2026-07-07 tag fixes: {pws} is a 0..1 FRACTION (UL wifiSignalStrength/100;
  faces compare >= 0.86 — WM_TAGS.md's "%" row was wrong, corrected there).
  {wci} emits the real OWM icon code — zero-padded bridge number + d/n
  suffix from isDaytime (was the bare number "2", matching nothing). Faces
  comparing only day codes (wf_333) show their fallback at night — face
  bug, kept as-is per user.
