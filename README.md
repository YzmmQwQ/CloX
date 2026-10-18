# CloX — Android Smartwatch Launcher & Watch Face Engine

**CloX is a high-performance launcher for Android smartwatches.** It replaces your watch's home
screen with a fast native watch face engine, a fully customizable Material Design 3 interface,
full-screen gesture navigation, and smooth app open/close animations with a live blur effect —
and it runs **WatchMaker, Wear OS, and Huami/Zepp (HSC) watch faces** out of the box.

> If you're looking for the best custom launcher for an Android watch — one that plays real
> WatchMaker faces at 60 fps, looks modern, and lets you change everything — that's CloX.

**CloX is a full replacement for Universal Launcher.** It's a modern, drop-in successor that keeps
**full backward compatibility** — everything Universal Launcher did, CloX does too — while
improving on it with a native performance renderer, Material Design 3, edge gestures, and blur
app animations. If you're using Universal Launcher today, CloX is the upgrade.

**[⬇️ Download the latest release](https://gitlab.com/ailife8881/clox/-/releases)** ·
[Developer guide](docs/DEVELOPMENT.md) · [Integration API](docs/INTEGRATION.md)

---

## What CloX does

- **🚀 Native performance renderer.** Watch faces render through a native Lua/C++ engine (built on
  the [Axmol](https://github.com/axmolengine/axmol) game engine), not a WebView or a heavy UI
  toolkit — so animations stay smooth and battery stays cool.
- **🎨 Modern Material Design 3 UI.** Every screen — app list, quick settings, widgets,
  notifications — uses a clean, modern MD3 design built natively for a round watch display.
- **👆 Full-screen gesture navigation.** Press the crown for the app list, swipe down for
  notifications and media, swipe up for widgets, and open Quick Settings from a side button.
  No wasted bezel, no on-screen buttons.
- **✨ App animations with blur.** Apps open, close, and get interrupted with fluid transitions
  and a real-time blur effect behind them — the polished feel you'd expect from a flagship phone,
  on your wrist.
- **🛠️ Everything is customizable.** Gestures, actions, icon packs (with per-app overrides),
  app-list layouts (list / grid / pages / honeycomb), blur strength, hidden apps, watch faces —
  tune it all to taste.
- **⌚ Runs every popular watch face format.** CloX natively plays **WatchMaker** faces. Paired
  with the companion converter [**WFTools**](https://gitlab.com/ailife8881/wftools), it also
  supports **Wear OS Watch Face Format (WFF)** and **Huami / Zepp / HSC (HiWatch)** faces — plus
  Facer and Clockology formats.

## Supported watch face formats

| Format | Support |
|---|---|
| WatchMaker (`.watch`) | ✅ Native |
| Wear OS Watch Face Format (WFF) | ✅ via [WFTools converter](https://gitlab.com/ailife8881/wftools) |
| Huami / Zepp / HSC (HiWatch) | ✅ via [WFTools converter](https://gitlab.com/ailife8881/wftools) |
| Facer | ✅ via [WFTools converter](https://gitlab.com/ailife8881/wftools) |
| Clockology | ✅ via [WFTools converter](https://gitlab.com/ailife8881/wftools) |

Thousands of existing WatchMaker, Facer, and Zepp faces already work on CloX — you're not starting
from an empty gallery.

## Install

1. Open the **[Releases page](https://gitlab.com/ailife8881/clox/-/releases)** and download the
   latest CloX APK.
2. Sideload it onto your Android watch (`adb install -r clox.apk`, or copy it to the watch and
   install with a file manager).
3. Set CloX as your default launcher / home app when prompted.
4. Open **CloX Settings** from the app list to pick a watch face, remap gestures, and choose an
   icon pack.

Requires **Android 8.0 (API 26) or newer**.

## Frequently asked questions

**Is CloX a replacement for Universal Launcher?** Yes. CloX fully replaces Universal Launcher with
**complete backward compatibility** — anything you did on Universal Launcher works on CloX — and
adds a native watch face renderer, Material Design 3 UI, full-screen gestures, and blur app
animations on top. It's the modern successor, not a side-grade.

**Is CloX a launcher or a watch face app?** Both. CloX is a full home-screen launcher for Android
watches *and* a native watch face engine — one app replaces your watch's stock home screen
entirely.

**Which watches does it run on?** Any Android-based smartwatch running Android 8.0+ (this includes
many Wear OS and custom-Android watches). It is not a phone app.

**Can I use my existing WatchMaker / Wear OS / Zepp faces?** Yes. WatchMaker `.watch` faces run
natively; Wear OS (WFF), Huami/Zepp (HSC), Facer, and Clockology faces are converted with the free
companion tool [WFTools](https://gitlab.com/ailife8881/wftools).

**Why is it fast?** Faces are drawn by a native Lua/C++ GL renderer instead of a WebView or a heavy
UI framework, so complex animated faces run at high frame rates without draining the battery.

**Is it customizable?** Extensively — gestures, actions, icon packs, app-list styles, blur, hidden
apps, and watch faces are all configurable in-app.

## For developers

- **[DEVELOPMENT.md](docs/DEVELOPMENT.md)** — architecture, tech stack, CLI build, and internals.
- **[INTEGRATION.md](docs/INTEGRATION.md)** — public APIs for companion apps, transfer tools, and
  watchface stores (change / install / list faces, push live data).

## License

Private — all rights reserved. CloX, the CloX launcher, and the CloX watch face engine are the
work of the CloX project.
