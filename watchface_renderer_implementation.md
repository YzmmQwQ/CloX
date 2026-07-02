# Watchface Rendering Engine — Implementation Notes

Status: **event-based refactor + per-watchface config implemented (2026-07-01).**
Validated headlessly (Lua parses, render harness passes) and via `compileDebugKotlin`
+ manifest/resource/asset merge. **Not yet run on a physical device** — see §8 for
the on-device verification checklist.

This documents the Lua `.watch` (Watchmaker / JBwatch) rendering engine plus the
Kotlin side that backs its per-watchface configuration. It is the *renderer*,
distinct from `watchface_handling_implementation_plan.md` (the selector UI / file
management).

Reference format docs live in `/home/selon/watchmaker-definition/`
(`WM_FORMAT_1.0.md`, `WM_FORMAT_2.0.md`, `WM_TAGS.md`, `WM_EFFECTS.md`,
`UL lua renderer/` = ground-truth renderer).

---

## 1. Files

### Lua (`app/src/main/assets/lua/`)

| File | Role |
|------|------|
| `watchface.lua` | The `WatchfaceScene`. Lifecycle, extract+load the active `.watch`, stage transform (512→screen, scale/offset), FPS cap, cadence-driven clock scheduler, feature toggles, bridge wiring, built-in default face, fallback logic. |
| `wf_render.lua` | The renderer. Parses `watch.xml`, builds cocos nodes per layer, classifies each layer's update cadence + data sources, applies transforms, bucketed updates, async bridge listeners. |
| `wf_cadence.lua` | **New.** Classifies each `{tag}` name → cadence bucket (FRAME/SECOND/MINUTE/HOUR/DAY/STATIC) and/or async data-source category. |
| `wf_tags.lua` | `{tag}` resolution from `WF.*` + clock, tag substitution, sandboxed Lua eval. Adds `buildFrame` (per-frame sub-second-only refresh). |
| `wf_protect.lua` | Decodes **protected** faces (`watch.pxml` base64-obfuscation + `.ppng` XOR images). |
| `watchface_bridge.lua` | (pre-existing) `WF.*` live data + `WatchfaceBridge` actions. Added `openConfig()`; guarded setters against a nil `json`. |
| `bridge.lua` / `main.lua` | (pre-existing) JNI event bus / entry point. Unchanged. |

Load chain: `main.lua` → `require "watchface"` → `wf_render` → `wf_tags`,
`wf_protect`, `wf_cadence`. Search paths `.` and `lua` are added in `AppDelegate.cpp`.

### Kotlin (`app/src/main/kotlin/com/ailife/clox/`)

| File | Role |
|------|------|
| `watchface/WatchfaceConfigPrefs.kt` | **New.** Per-face config (scale, X/Y offset, max FPS, unlimited) in SharedPreferences keyed by `path.hashCode()`; default face under `__default__`. |
| `watchface/config/WatchfaceConfigActivity.kt` | **New.** Translucent two-page config dialog (Position, Frame rate); writes prefs → reactive settings re-push. |
| `cocos/bridge/LiveDataHelper.kt` | `watchfaceSettingsJson` now emits per-face `zoom`/`xOffset`/`yOffset`/`maxFps`/`maxFpsUnlimited`. |
| `cocos/bridge/WatchfaceBridgeManager.kt` | Re-pushes settings on any `wf_cfg_*` change; handles the `openConfig` action. |
| `gesture/GestureHandler.kt` | New `watchface_config` gesture action. |
| `res/values/strings.xml` | `watchface_config` added to the gesture-action arrays. |
| `AndroidManifest.xml` | Registers `WatchfaceConfigActivity` (translucent). |

Asset: `app/src/main/assets/wf_default/bg.png` — copy of
`res/drawable-nodpi/ic_launcher_background.png`, used by the default face.

---

## 2. Architecture

### Coordinate model + the Y-axis fix

Canvas is 512×512, origin centre. **Authored layer positions are screen-DOWN**
(positive Y = toward the bottom). `WM_FORMAT_1.0 §4` documents Y-up, but real faces
render vertically inverted on device — so the renderer negates the authored Y at a
single site: `applyTransform` does `holder:setPosition(x, Y_SIGN * y)` with
`Y_SIGN = -1` (top of `wf_render.lua`; flip to `+1` to revert).

**Only holder positions are negated.** Per-layer geometry (markers, rings, hands,
text arcs) is *procedural* and already authored in cocos Y-up (12 o'clock = +Y), so
it is left untouched — negating it would send 12 o'clock to the bottom and make hands
point down. Visuals (sprites/glyphs) are never mirrored, so they stay upright. A
second constant `Y_SIGN_ROT` (currently `+1`) is reserved for rotation sense if an
on-device check shows hands/numerals sweep the wrong way (§8).

The built-in default face is authored in the same screen-down convention, so the
single flip is consistent for all faces.

### Per-layer node layout

```
holder (cc.Node)  — x/y (Y-flipped), rotation, anim_scale, opacity, skew, visibility
  └─ visual       — the sprite / label / DrawNode, base-scaled to fit width×height
```
Groups (2.0) are holders with no visual; children attach by `parentId` (resolved in
a second pass, so parent-after-child ordering works).

### Build passes (`WFRender:load`)

1. Create holder + visual per layer. **Classify** the layer: scan a superset of
   attributes for `{tags}`, reduce to the *fastest* cadence bucket + the set of async
   categories used. Store `rec.cadence`, `rec.async`. `gif`/`image_gif` force FRAME.
2. Parent holders (document order = draw order).
3. Initial transform + content + visibility, then index each layer into
   `self.byCadence[bucket]` and `self.byAsync[category]`. Derive `needFrame` /
   `needSecond`, build the feature summary, and register async bridge listeners.
   Each layer's transform+update is `pcall`-isolated.

### Update model — event-based, cadence-throttled

The old engine rebuilt ~150 tags and re-applied every dynamic layer 60×/s. Now:

- **`wf_cadence.classify(tag)`** assigns each tag a clock bucket and/or an async
  category (see §3 of `WM_TAGS.md` mapping). Unknown → MINUTE (worst case = 1-min lag).
- **Bucketed re-eval.** The scene computes, each tick, which boundaries crossed
  (`second`/`minute`/`hour`/`day`, plus `frame` when the face needs it) and calls
  `WFRender:tickBuckets(ticks)`, which re-evaluates only the layers in the buckets
  that ticked. Buckets hold disjoint layer sets, so a minute wrap (which also ticks
  second) never double-evaluates.
- **Two-tier context.** A single persistent `self.ctx` is built fully at load. Each
  frame, `WFTags.buildFrame` mutates only the ~7 sub-second tags in place (no
  allocation). A full `WFTags.build` runs only on a second-or-slower boundary or an
  async push — negligible at ≤1×/s vs 60×/s before.
- **Scheduler start/stop.** `WatchfaceScene:restartClock()` (run on every load)
  schedules the 0-interval per-frame func **only if** the face has a FRAME layer;
  otherwise it schedules a **1 s poll** and the per-frame loop stays fully suspended.
  Always unschedule-before-schedule; both timers cleared in `onExit`.

### Async data + feature toggles

- The renderer registers a `WatchfaceBridge.onChange` listener per async category the
  face uses (weather, health:*, sensor:*, battery, calendar, notification, network,
  system, storage, alarm, timezone). A push refreshes the snapshot and re-evaluates
  just that source's layers **immediately** (there may be no next frame when the loop
  is suspended). Handlers are torn down in `WFRender:clear()` on the next switch, or
  stale closures would fire into freed layer records.
- The renderer exposes `self.features = {weather, health{…}, sensor{…}}`. The scene's
  `applyFeatures` **diffs against the previous face** and calls
  `setWeatherEnabled`/`setHealthEnabled`/`setSensorEnabled` only on change — enabling
  what the new face uses, disabling what it dropped. `loadDefault` and `onExit` run it
  too, so unused Java fetching/sensors are turned off.

### Tag pipeline

`WFTags.build(clock)` snapshots all tags from `WF.*` + date; theme `{ucolor*}` is
overlaid by the renderer. `sub()` replaces `{tag}`; `evalText()` runs Lua only if the
result looks like code (so `"2024-2026"` stays literal); `evalNumber()` is permissive
arithmetic.

---

## 3. Per-watchface configuration, FPS & dialog

- **Storage.** `WatchfaceConfigPrefs` namespaces keys by `path.hashCode()`:
  `wf_cfg_<hash>_scale/_x_offset/_y_offset/_max_fps/_unlimited`. The default face uses
  the `__default__` path token. Defaults: scale 1.0, offsets 0, max FPS **25**,
  unlimited false.
- **Reactive pipeline.** `LiveDataHelper.watchfaceSettingsJson` reads the active
  `watchface_path`, looks up its per-face config, and emits `zoom`(=scale),
  `xOffset`, `yOffset`, `maxFps`, `maxFpsUnlimited`, `aodEnabled`. Any `wf_cfg_*`
  change trips `WatchfaceBridgeManager`'s prefs listener → re-push `settings` → Lua
  reacts (live).
- **Stage transform.** `WatchfaceScene:applyStageTransform` fits the 512 canvas to
  `min(W,H)` (never overflows), then applies scale/offset (positive `yOffset` = down).
- **FPS enforcement (Lua owns the render tick).** `applyFrameRate` computes
  `effectiveFps = aodEnabled and 1 or (maxFpsUnlimited and 60 or maxFps)` and calls
  `cc.Director:setAnimationInterval(1/fps)`. AOD hard-caps at **1 FPS**; normal caps at
  the per-face max (default 25). *Note:* this caps the whole GL loop, so launcher
  transitions also run at the cap — raise via "Unlimited" if 60 is wanted.
- **Gesture + dialog.** Gesture action `watchface_config` (bindable to double-tap /
  long-press) and Lua `WatchfaceBridge.openConfig()` both open
  `WatchfaceConfigActivity` — a translucent, plain-view panel with two pages:
  **Position** (scale / X / Y sliders + reset) and **Frame rate** (15/25/60/Unlimited
  radio; AOD's fixed 1 FPS shown as info). It edits `WatchfaceConfigPrefs` for the
  active face; the live preview relies on the home GL surface continuing to render
  behind the translucent activity (see §8).

---

## 4. Format support

**Layer types:** `image`, `text` (TTF/bitmap/system, tint, case transform), `ring`,
`progress`, `image_cond`, `shape`, `rounded`, `markers`, `markers_hm`, `text_ring`,
`text_curved`, `gif`/`image_gif`/`image_tap`, `group`.
**Stubbed (no crash, not drawn):** `chart`, `series`, `calendar`, `map`, `video`.
**Tags:** broad coverage — time/date, hand rotations, battery, weather (current +
forecast), health, sensors/compass, system, network, calendar, timezones,
notifications, counters, theme colours. Unknown tags → empty string.
**Versions:** 1.0 (flat) and 2.0 (`build_source="studio"`, groups, `<ThemeSets>`).
Attribute parsing is quote-agnostic.

---

## 5. Protected faces (`protection="y"`) — reverse-engineered

Some faces ship a 135-byte stub `watch.xml`, the real definition in `watch.pxml`, and
images as `.ppng`:

- **`watch.pxml`** = standard Base64 of the real `watch.xml` with two alphabet swaps:
  `D↔g` (3↔32) and `4↔L` (11↔56). Decoded in `wf_protect.decodePxml`.
- **`.ppng`** = the PNG bytes XOR'd with the repeating 5-byte key **`SWHn-`**. Decoded
  to a sibling `.png` on first use (cached via `FileUtils:writeStringToFile`).

`watchface.lua` detects `watch.pxml`; `wf_render:imageFile()` maps `.ppng` → decoded
`.png` lazily.

> ⚠️ Crypto params were derived from ONE sample (`wf_347_jb`) and may not be universal
> (`wf_351_jb` still decodes garbled — §8).

---

## 6. Engine / native facts (verified against `axmol/` source)

- `DrawNode:drawSolidTriangle` is the **fill+border** variant; the filled-colour
  triangle is **`drawTriangle(p1,p2,p3,color)`** (DrawNode.h:500). `drawSolidRect` and
  7-arg `drawSolidCircle` are valid.
- `cc.Director:getInstance():setAnimationInterval(sec)` is bound
  (`axlua_base_auto.cpp:17989`) — used for the FPS cap.
- `Scheduler:scheduleScriptFunc(fn, interval, paused)` /
  `unscheduleScriptEntry(id)` are bound (`axlua_base_manual.cpp:724/777`) — drive the
  per-frame vs 1 s clock.
- `FileUtils:getStringFromFile`/`writeStringToFile` are binary-safe;
  `imageFile` falls back to the FileUtils search paths (assets) when a file isn't in
  the face's own `images/` dir (lets the default face load its bundled background).
- `axUnzipToDir` / `axRemoveDir` (from `AppDelegate.cpp`) extract the `.watch` zip.
  Lua print → logcat tag `axmol` as `[LUA-print] …`.

---

## 7. Default face

Minimal theme over the launcher-background asset (`wf_default/bg.png`): **rounded-tip
hands** (via the `rounded` builder as capsules) and small digital time / date /
battery. Accent **#82abd1** on the date, second hand and centre cap (`ucolor_default`).
The outer second-sweep ring and hour indices were removed for a clean look. Needs no
packaged `.watch` assets; used whenever no face is set, a load fails, or a face parses
to 0 renderable layers.

---

## 8. On-device verification checklist

- **Y positions:** a real face's bottom elements render at the bottom, top at the top;
  glyphs upright, images correctly faced.
- **Rotation sense:** analog hands sweep clockwise with 12 at top. If reversed, set
  `Y_SIGN_ROT = -1` in `wf_render.lua` (and check `text_ring`/`text_curved`).
- **Sprite vertical alignment:** confirm `alignment="tl"/"bl"` sprites pin the right
  edge (the anchor-table risk); swap the `t*`/`b*` rows of `AP` if wrong.
- **Throttling:** a minute-only face suspends the per-frame loop; a smooth-second face
  keeps it (log the tick rate to confirm).
- **Feature toggles:** Java logs `setWeatherEnabled(true/false)` on switch; no weather
  fetch for faces without `w*` tags.
- **Config live preview:** dragging sliders moves the face while the dialog is open —
  depends on the home `GLSurfaceView` still rendering under the translucent activity;
  if it pauses, changes apply on dialog close instead.
- **FPS feel:** launcher transitions at the 25 FPS default may feel less smooth than
  60; "Unlimited" restores 60.
- Pre-existing: `wf_347_jb` shader/template misplacement; `wf_351_jb` garbled decode.

---

## 9. Not yet implemented

- Effects/shaders (`WM_EFFECTS.md`): shadow, outline/glow, gradient/angle shaders.
  Shape `shader=` still ignored → solid fill.
- `scripts/script.txt` Lua callbacks; `template="…"` layout semantics.
- Layer types: `chart`, `series`, `calendar`, `map`, `video`.
- The old **global** watchface prefs (`watchface_zoom`/`_x_offset`/`_y_offset`/
  `_frame_rate`) are superseded by per-face config and no longer drive rendering — any
  settings-screen controls for them are now dead and should be removed or repointed at
  the config dialog.

---

## 10. Testing

- **Headless:** `wf_cadence` classifier spot-checks + a stubbed-`cc.*` harness that
  loads the default face and a synthetic weather face — asserting cadence buckets,
  `needFrame`, the Y-flip (`y=150` → cocos `-150`), feature detection, async listener
  registration, and teardown on `clear()`. (Stubs can't catch missing engine APIs —
  those are verified against `axmol/` source, §6.)
- **Kotlin:** `:app:compileDebugKotlin` green; `processDebugMainManifest`,
  `mergeDebugResources`, `mergeDebugAssets` all succeed.
- `wf_render` prints per-load diagnostics: `[WFRender] loaded N layers (M dyn,
  frame=… sec=…), theme=…`.

---

## 11. Behaviour / fallbacks

- No face set, load fails, or 0 renderable layers → built-in default face.
- Switching back to default (path → `false`) loads the default; failed real paths are
  remembered to avoid reload thrash.
- Reacts to bridge: `settings` (path/scale/offset/FPS/AOD), `cmd_setActive/reload/
  reset`, plus per-category async pushes and the `openConfig` action.
