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
