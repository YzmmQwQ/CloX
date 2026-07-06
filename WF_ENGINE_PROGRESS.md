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
