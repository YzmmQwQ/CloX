# CloX — App List Feature Progress

## Task: Full App List Overhaul

---

## Foundation ✅
- [x] `AppItem.kt` — added `customOrder: Int`
- [x] `AppListPrefs.kt` — centralised prefs (all keys + readers/writers)
- [x] `AppListBackend.kt` — redesigned: `createContentView`, `bindItems`, click/longpress callbacks
- [x] `AppListAdapter.kt` — generic shared adapter, ItemTouchHelper drag (handle path + longpress path)

## Custom Layout Engines ✅
- [x] `WaterfallLayoutManager.kt` — custom RecyclerView.LayoutManager, 3-4-3-4 rows
- [x] `HoneycombView.kt` — ViewGroup, hex arrangement (4-3-4-3), 2D OverScroller pan, optional 3D
- [x] `Grid2DView.kt` — ViewGroup, grid, 2D OverScroller pan, optional names, optional 3D

## Backends ✅ (all 6)
- [x] `backends/ListBackend.kt` — optional search bar, optional 3D depth, drag handle
- [x] `backends/GridBackend.kt` — configurable columns, optional name labels, drag
- [x] `backends/WaterfallBackend.kt` — WaterfallLayoutManager, force-round icons, optional 3D
- [x] `backends/HoneycombBackend.kt` — HoneycombView, optional 3D, adjustable icon size
- [x] `backends/Grid2DBackend.kt` — Grid2DView, cols, names, 3D, icon size
- [x] `backends/PagesBackend.kt` — ViewPager2, configurable H×W, swipe-past-page-0 → close

## App List Core ✅
- [x] `AppListViewModel.kt` — alpha/custom sort, custom order JSON persistence, new app → bottom
- [x] `AppListFragment.kt` — dynamic backend from prefs, swap on settings change, callbacks wired
- [x] `GestureInterceptorLayout.kt` — `pagesBackendActive` flag blocks horiz for ViewPager2
- [x] `MainActivity.kt` — wires `onRequestClose` + `onPagesBackendActive` on fragment

## Long Press & Context Menu ✅
- [x] `AppContextMenu.kt` — PopupMenu: Uninstall, App Info, Change Icon, Reset Icon
- [x] `IconPickerDialog.kt` — icon pack search grid + gallery picker; saves to `filesDir/icons/`
- [x] `IconResolver.kt` — reads custom icon path from AppListPrefs (priority 1)

## Settings ✅
- [x] `strings.xml` — all style/sub-setting strings and arrays
- [x] `item_app_grid.xml` — icon + optional name for grid items
- [x] `item_app_icon.xml` — icon-only item (waterfall/honeycomb/grid2d)
- [x] `fragment_app_list.xml` — plain FrameLayout (backend owns content)
- [x] `preferences_app_list.xml` — per-style categories + generic settings
- [x] `AppListSettingsFragment.kt` — categories shown/hidden by selected style

---

## What's Done
All 6 styles built and wired. Settings show dynamic sub-options per style.
Long-press opens context menu (uninstall / app info / change icon). Icon picker
searches the active ADW icon pack or picks from gallery, saves permanently.
Custom sort with drag-to-reorder (drag handle in List, long-press in Grid).
Pages backend hands back control to gesture system on swipe-past-page-0.

## Next
- Wire `SettingsActivity` navigation to show `AppListSettingsFragment` (currently `GeneralSettingsFragment` is the default root)
- Test all 6 styles on device
- `onActivityResult` for gallery icon pick — needs wiring in `MainActivity` → `IconPickerDialog.handleGalleryResult`
- Hidden apps management (tap `hidden_apps` pref → multi-select list)
