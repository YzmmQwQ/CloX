# CloX — App List Feature Progress

## Task: Full App List Overhaul

---

## Foundation
- [ ] `AppItem.kt` — add `customOrder: Int`
- [ ] `AppListPrefs.kt` — centralized prefs object (all keys + readers)
- [ ] `AppListBackend.kt` — redesign interface: `createContentView`, `bindItems`, click/longpress callbacks
- [ ] `AppListAdapter.kt` — generic shared adapter (used by RV backends), ItemTouchHelper drag support

## Custom Layout Engines
- [ ] `WaterfallLayoutManager.kt` — custom RecyclerView.LayoutManager, 3-4-3-4 alternating rows
- [ ] `HoneycombView.kt` — custom ViewGroup, hex arrangement, 2D pan (OverScroller)
- [ ] `Grid2DView.kt` — custom ViewGroup, grid arrangement, 2D pan (OverScroller)

## Backends (6 total)
- [ ] `backends/ListBackend.kt` — rewrite: optional search bar, optional 3D perspective effect
- [ ] `backends/GridBackend.kt` — new: configurable columns, optional name labels
- [ ] `backends/WaterfallBackend.kt` — new: uses WaterfallLayoutManager, force-round icons, optional 3D
- [ ] `backends/HoneycombBackend.kt` — new: uses HoneycombView, optional 3D, adjustable icon size
- [ ] `backends/Grid2DBackend.kt` — new: uses Grid2DView, configurable cols, optional names, optional 3D, adjustable icon size
- [ ] `backends/PagesBackend.kt` — new: horizontal pages (ViewPager2), configurable H×W, swipe-past-page-0 → close app list

## App List Core
- [ ] `AppListViewModel.kt` — sort: alpha/custom, custom order persistence (JSON), icon pack awareness
- [ ] `AppListFragment.kt` — rewrite: dynamic backend from prefs, swap on settings change, wire long-press/click/close callbacks
- [ ] `GestureInterceptorLayout.kt` — add `pagesBackendActive` flag to block horiz swipes in pages mode
- [ ] `MainActivity.kt` — wire `AppListFragment.onRequestClose` + `onPagesBackendActive` callbacks

## Long Press & Context Menu
- [ ] `AppContextMenu.kt` — popup with: Uninstall, App Info, Change Icon
- [ ] `IconPickerDialog.kt` — icon pack search grid + gallery picker; save to `filesDir/icons/<pkg>.png`
- [ ] `IconResolver.kt` — update to read custom icon path from `AppListPrefs`

## Settings
- [ ] `strings.xml` — add all style/sub-setting strings and arrays
- [ ] `item_app_grid.xml` — icon + optional name for grid-style items
- [ ] `item_app_icon.xml` — icon-only item (waterfall/honeycomb/grid2d)
- [ ] `fragment_app_list.xml` — simplify to plain FrameLayout (backend owns content)
- [ ] `preferences_app_list.xml` — full hierarchy: style picker + per-style sub-settings + generic settings
- [ ] `AppListSettingsFragment.kt` — rewrite: show/hide sub-settings dynamically based on selected style

---

## Architecture Constraints
- No Compose
- RecyclerView backends share `AppListAdapter`; 2D backends own their own View
- `AppListFragment` holds a single `AppListBackend` reference; swaps it on prefs change
- Drag reorder only when `sort = custom`; drag handle visible in List mode; long-press drag in Grid mode
- Long press without move → `AppContextMenu`; long press + drag → reorder (where supported)
- Custom icon saved to `context.filesDir/icons/<packageName>.png`; read via `AppListPrefs.customIconPath()`
