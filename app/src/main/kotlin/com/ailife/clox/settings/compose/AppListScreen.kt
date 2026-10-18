package com.ailife.clox.settings.compose

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ailife.clox.R
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.icon.IconPackLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun AppListScreen() {
    val ctx = LocalContext.current
    val appListPrefs = remember {
        ctx.getSharedPreferences(AppListPrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    val styleEntries = stringArrayResource(R.array.app_list_style_entries)
    val styleValues = stringArrayResource(R.array.app_list_style_values)
    val sortEntries = stringArrayResource(R.array.app_list_sort_entries)
    val sortValues = stringArrayResource(R.array.app_list_sort_values)
    val curveEntries = stringArrayResource(R.array.curve_entries)
    val curveValues = stringArrayResource(R.array.curve_values)

    var style by remember {
        mutableStateOf(appListPrefs.getString("style", AppListPrefs.STYLE_LIST)
            ?: AppListPrefs.STYLE_LIST)
    }
    var sort by remember {
        mutableStateOf(appListPrefs.getString("sort", AppListPrefs.SORT_ALPHA)
            ?: AppListPrefs.SORT_ALPHA)
    }
    var iconPack by remember { mutableStateOf(appListPrefs.getString("icon_pack", "") ?: "") }
    var bgTick by remember { mutableStateOf(0) }
    var badge by remember { mutableStateOf(appListPrefs.getBoolean("notif_badge", false)) }
    var badgeCount by remember { mutableStateOf(appListPrefs.getBoolean("notif_badge_count", true)) }
    var wallpaperDim by remember {
        mutableStateOf(appListPrefs.getInt("wallpaper_dim", 0).coerceIn(0, 90))
    }
    var overscroll by remember {
        mutableStateOf(appListPrefs.getInt("overscroll", 5).coerceIn(0, 10))
    }

    var showStyle by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showIconPack by remember { mutableStateOf(false) }
    var showCurveFor by remember { mutableStateOf<String?>(null) }

    // Style-specific states
    var listSearch by remember { mutableStateOf(appListPrefs.getBoolean("list_search", true)) }
    var list3d by remember { mutableStateOf(appListPrefs.getBoolean("list_3d", false)) }
    var list3dScale by remember {
        mutableStateOf(appListPrefs.getInt("list_3d_min_scale", 50).coerceIn(20, 100))
    }
    var list3dCurve by remember {
        mutableStateOf(appListPrefs.getString("list_3d_curve", "linear") ?: "linear")
    }
    var listIconSize by remember {
        mutableStateOf(appListPrefs.getInt("list_icon_size", 0).coerceIn(0, 120))
    }
    var listRoundOverscroll by remember {
        mutableStateOf(appListPrefs.getBoolean("list_round_overscroll", false))
    }

    var gridCols by remember { mutableStateOf(appListPrefs.getInt("grid_cols", 4).coerceIn(2, 6)) }
    var gridNames by remember { mutableStateOf(appListPrefs.getBoolean("grid_names", true)) }
    var gridIconSize by remember {
        mutableStateOf(appListPrefs.getInt("grid_icon_size", 0).coerceIn(0, 120))
    }
    var gridRoundOverscroll by remember {
        mutableStateOf(appListPrefs.getBoolean("grid_round_overscroll", false))
    }

    var waterfall3d by remember { mutableStateOf(appListPrefs.getBoolean("waterfall_3d", false)) }
    var waterfall3dScale by remember {
        mutableStateOf(appListPrefs.getInt("waterfall_3d_min_scale", 50).coerceIn(20, 100))
    }
    var waterfall3dCurve by remember {
        mutableStateOf(appListPrefs.getString("waterfall_3d_curve", "linear") ?: "linear")
    }
    var waterfallIconSize by remember {
        mutableStateOf(appListPrefs.getInt("waterfall_icon_size", 0).coerceIn(0, 120))
    }
    var waterfallRoundOverscroll by remember {
        mutableStateOf(appListPrefs.getBoolean("waterfall_round_overscroll", false))
    }

    var honeycomb3d by remember { mutableStateOf(appListPrefs.getBoolean("honeycomb_3d", false)) }
    var honeycomb3dScale by remember {
        mutableStateOf(appListPrefs.getInt("honeycomb_3d_min_scale", 90).coerceIn(20, 100))
    }
    var honeycombIconSize by remember {
        mutableStateOf(appListPrefs.getInt("honeycomb_icon_size", 0).coerceIn(0, 120))
    }
    var honeycombRoundOverscroll by remember {
        mutableStateOf(appListPrefs.getBoolean("honeycomb_round_overscroll", false))
    }

    var grid2dNames by remember { mutableStateOf(appListPrefs.getBoolean("grid2d_names", false)) }
    var grid2d3d by remember { mutableStateOf(appListPrefs.getBoolean("grid2d_3d", false)) }
    var grid2d3dScale by remember {
        mutableStateOf(appListPrefs.getInt("grid2d_3d_min_scale", 50).coerceIn(20, 100))
    }
    var grid2dIconSize by remember {
        mutableStateOf(appListPrefs.getInt("grid2d_icon_size", 0).coerceIn(0, 120))
    }
    var grid2dRoundOverscroll by remember {
        mutableStateOf(appListPrefs.getBoolean("grid2d_round_overscroll", false))
    }

    var pagesCols by remember { mutableStateOf(appListPrefs.getInt("pages_cols", 4).coerceIn(2, 6)) }
    var pagesRows by remember { mutableStateOf(appListPrefs.getInt("pages_rows", 5).coerceIn(2, 8)) }

    var gravityAccel by remember { mutableStateOf(appListPrefs.getBoolean("gravity_accel", true)) }
    var gravityStrength by remember {
        mutableStateOf(appListPrefs.getInt("gravity_strength", 10).coerceIn(0, 20))
    }
    var gravityBounce by remember {
        mutableStateOf(appListPrefs.getInt("gravity_bounce", 5).coerceIn(0, 10))
    }
    var gravityFriction by remember {
        mutableStateOf(appListPrefs.getInt("gravity_friction", 3).coerceIn(0, 10))
    }

    val navigate = LocalSettingsNavigator.current
    var hiddenAppsVisible by remember {
        mutableStateOf(AppListPrefs.hideAppsPassword(ctx) == null)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hiddenAppsVisible = AppListPrefs.hideAppsPassword(ctx) == null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val iconPacks = remember { IconPackLoader.getInstalledPacks(ctx) }
    val iconPackEntries = remember(iconPacks) {
        arrayOf(ctx.getString(R.string.pref_icon_pack_none)) + iconPacks.map { it.second }
    }
    val iconPackValues = remember(iconPacks) {
        arrayOf("") + iconPacks.map { it.first }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { saveAppListBackground(ctx, uri) }
            if (ok) bgTick++
        }
    }

    val hasImage = remember(bgTick) { AppListPrefs.bgImagePath(ctx) != null }
    val usingWallpaper = remember(bgTick) {
        !hasImage && AppListPrefs.hasReadWallpaperPermission(ctx)
    }
    val canPushWallpaper = remember(bgTick) {
        !AppListPrefs.hasReadWallpaperPermission(ctx) && hasImage
    }

    WearSettingsScreen(title = stringResource(R.string.pref_app_list_title)) {
        SectionHeader(res(R.string.applist_setting_general_category))
        SettingsButton(
            title = res(R.string.pref_app_list_style_title),
            summary = styleEntries.getOrElse(styleValues.indexOf(style)) { style },
        ) { showStyle = true }
        SettingsButton(
            title = res(R.string.pref_app_list_sort_title),
            summary = sortEntries.getOrElse(sortValues.indexOf(sort)) { sort },
        ) { showSort = true }
        if (sort == AppListPrefs.SORT_CUSTOM) {
            SettingsButton(
                title = res(R.string.pref_app_list_sort_custom_hint),
                summary = null,
            ) { }
        }
        SettingsButton(
            title = res(R.string.pref_icon_pack_title),
            summary = iconPackEntries.getOrElse(iconPackValues.indexOf(iconPack)) {
                ctx.getString(R.string.pref_icon_pack_none)
            },
        ) { showIconPack = true }
        if (hiddenAppsVisible) {
            SettingsButton(
                title = res(R.string.pref_hidden_apps_title),
                summary = res(R.string.applist_setting_hidden_apps_summary),
            ) { navigate(SettingsRoutes.HIDE_APPS) }
        }
        SettingsButton(
            title = res(R.string.folders_title),
            summary = res(R.string.folders_summary),
        ) { navigate(SettingsRoutes.FOLDERS) }

        SectionHeader(res(R.string.applist_setting_background_category))
        SettingsButton(
            title = res(R.string.applist_setting_bg_image_title),
            summary = when {
                hasImage -> res(R.string.app_list_bg_set)
                usingWallpaper -> res(R.string.applist_setting_bg_using_wallpaper)
                else -> res(R.string.app_list_bg_not_set)
            },
        ) { pickImage.launch("image/*") }
        if (hasImage || usingWallpaper) {
            SettingsSlider(
                title = res(R.string.applist_setting_darken_bg_title),
                value = wallpaperDim,
                range = 0..90,
                valueText = "$wallpaperDim",
                onChange = {
                    wallpaperDim = it
                    appListPrefs.edit().putInt("wallpaper_dim", it).apply()
                },
            )
        }
        if (canPushWallpaper) {
            SettingsButton(
                title = res(R.string.applist_setting_set_wallpaper_btn),
            ) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { pushAppListBgToWallpaper(ctx) }
                    Toast.makeText(
                        ctx,
                        ctx.getString(
                            if (ok) R.string.applist_setting_set_wallpaper_done
                            else R.string.applist_setting_set_wallpaper_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        if (hasImage) {
            SettingsButton(
                title = res(R.string.applist_setting_clear_bg_btn),
            ) {
                AppListPrefs.saveBgImagePath(ctx, null)
                bgTick++
            }
        }

        SectionHeader(res(R.string.pref_notif_badge_category))
        SettingsSwitch(
            title = res(R.string.pref_notif_badge_title),
            summary = res(R.string.pref_notif_badge_summary),
            checked = badge,
            onCheckedChange = {
                badge = it
                appListPrefs.edit().putBoolean("notif_badge", it).apply()
            },
        )
        if (badge) {
            SettingsSwitch(
                title = res(R.string.pref_notif_badge_count_title),
                summary = res(R.string.pref_notif_badge_count_summary),
                checked = badgeCount,
                onCheckedChange = {
                    badgeCount = it
                    appListPrefs.edit().putBoolean("notif_badge_count", it).apply()
                },
            )
        }
        SettingsSlider(
            title = res(R.string.pref_overscroll_title),
            value = overscroll,
            range = 0..10,
            valueText = "$overscroll",
            onChange = {
                overscroll = it
                appListPrefs.edit().putInt("overscroll", it).apply()
            },
        )

        // ── Style-specific options ──────────────────────────────────────────
        if (style == AppListPrefs.STYLE_LIST) {
            SectionHeader(res(R.string.applist_setting_list_options_category))
            SettingsSwitch(
                title = res(R.string.pref_list_search_title),
                checked = listSearch,
                onCheckedChange = {
                    listSearch = it
                    appListPrefs.edit().putBoolean("list_search", it).apply()
                },
            )
            SettingsSwitch(
                title = res(R.string.pref_list_3d_title),
                summary = res(R.string.pref_list_3d_summary),
                checked = list3d,
                onCheckedChange = {
                    list3d = it
                    appListPrefs.edit().putBoolean("list_3d", it).apply()
                },
            )
            if (list3d) {
                SettingsSlider(
                    title = res(R.string.pref_3d_min_scale_title),
                    value = list3dScale,
                    range = 20..100,
                    valueText = "$list3dScale",
                    onChange = {
                        list3dScale = it
                        appListPrefs.edit().putInt("list_3d_min_scale", it).apply()
                    },
                )
                SettingsButton(
                    title = res(R.string.pref_3d_curve_title),
                    summary = curveEntries.getOrElse(curveValues.indexOf(list3dCurve)) { "linear" },
                ) { showCurveFor = "list" }
            }
            SettingsSlider(
                title = res(R.string.pref_icon_size_title),
                value = listIconSize,
                range = 0..120,
                valueText = "$listIconSize",
                onChange = {
                    listIconSize = it
                    appListPrefs.edit().putInt("list_icon_size", it).apply()
                },
            )
            SettingsSwitch(
                title = res(R.string.pref_round_overscroll_title),
                summary = res(R.string.pref_round_overscroll_summary),
                checked = listRoundOverscroll,
                onCheckedChange = {
                    listRoundOverscroll = it
                    appListPrefs.edit().putBoolean("list_round_overscroll", it).apply()
                },
            )
        }

        if (style == AppListPrefs.STYLE_GRID) {
            SectionHeader(res(R.string.applist_setting_grid_options_category))
            SettingsSlider(
                title = res(R.string.pref_grid_cols_title),
                value = gridCols,
                range = 2..6,
                valueText = "$gridCols",
                onChange = {
                    gridCols = it
                    appListPrefs.edit().putInt("grid_cols", it).apply()
                },
            )
            SettingsSwitch(
                title = res(R.string.pref_grid_names_title),
                checked = gridNames,
                onCheckedChange = {
                    gridNames = it
                    appListPrefs.edit().putBoolean("grid_names", it).apply()
                },
            )
            SettingsSlider(
                title = res(R.string.pref_icon_size_title),
                value = gridIconSize,
                range = 0..120,
                valueText = "$gridIconSize",
                onChange = {
                    gridIconSize = it
                    appListPrefs.edit().putInt("grid_icon_size", it).apply()
                },
            )
            SettingsSwitch(
                title = res(R.string.pref_round_overscroll_title),
                summary = res(R.string.pref_round_overscroll_summary),
                checked = gridRoundOverscroll,
                onCheckedChange = {
                    gridRoundOverscroll = it
                    appListPrefs.edit().putBoolean("grid_round_overscroll", it).apply()
                },
            )
        }

        if (style == AppListPrefs.STYLE_WATERFALL) {
            SectionHeader(res(R.string.applist_setting_waterfall_options_category))
            SettingsSwitch(
                title = res(R.string.pref_waterfall_3d_title),
                summary = res(R.string.pref_waterfall_3d_summary),
                checked = waterfall3d,
                onCheckedChange = {
                    waterfall3d = it
                    appListPrefs.edit().putBoolean("waterfall_3d", it).apply()
                },
            )
            if (waterfall3d) {
                SettingsSlider(
                    title = res(R.string.pref_3d_min_scale_title),
                    value = waterfall3dScale,
                    range = 20..100,
                    valueText = "$waterfall3dScale",
                    onChange = {
                        waterfall3dScale = it
                        appListPrefs.edit().putInt("waterfall_3d_min_scale", it).apply()
                    },
                )
                SettingsButton(
                    title = res(R.string.pref_3d_curve_title),
                    summary = curveEntries.getOrElse(curveValues.indexOf(waterfall3dCurve)) { "linear" },
                ) { showCurveFor = "waterfall" }
            }
            SettingsSlider(
                title = res(R.string.pref_icon_size_title),
                value = waterfallIconSize,
                range = 0..120,
                valueText = "$waterfallIconSize",
                onChange = {
                    waterfallIconSize = it
                    appListPrefs.edit().putInt("waterfall_icon_size", it).apply()
                },
            )
            SettingsSwitch(
                title = res(R.string.pref_round_overscroll_title),
                summary = res(R.string.pref_round_overscroll_summary),
                checked = waterfallRoundOverscroll,
                onCheckedChange = {
                    waterfallRoundOverscroll = it
                    appListPrefs.edit().putBoolean("waterfall_round_overscroll", it).apply()
                },
            )
        }

        if (style == AppListPrefs.STYLE_HONEYCOMB) {
            SectionHeader(res(R.string.applist_setting_honeycomb_options_category))
            SettingsSwitch(
                title = res(R.string.pref_honeycomb_3d_title),
                summary = res(R.string.pref_honeycomb_3d_summary),
                checked = honeycomb3d,
                onCheckedChange = {
                    honeycomb3d = it
                    appListPrefs.edit().putBoolean("honeycomb_3d", it).apply()
                },
            )
            if (honeycomb3d) {
                SettingsSlider(
                    title = res(R.string.pref_3d_min_scale_title),
                    value = honeycomb3dScale,
                    range = 20..100,
                    valueText = "$honeycomb3dScale",
                    onChange = {
                        honeycomb3dScale = it
                        appListPrefs.edit().putInt("honeycomb_3d_min_scale", it).apply()
                    },
                )
            }
            SettingsSlider(
                title = res(R.string.pref_honeycomb_icon_size_title),
                value = honeycombIconSize,
                range = 0..120,
                valueText = "$honeycombIconSize",
                onChange = {
                    honeycombIconSize = it
                    appListPrefs.edit().putInt("honeycomb_icon_size", it).apply()
                },
            )
            SettingsSwitch(
                title = res(R.string.pref_round_overscroll_title),
                summary = res(R.string.pref_round_overscroll_summary),
                checked = honeycombRoundOverscroll,
                onCheckedChange = {
                    honeycombRoundOverscroll = it
                    appListPrefs.edit().putBoolean("honeycomb_round_overscroll", it).apply()
                },
            )
        }

        if (style == AppListPrefs.STYLE_GRID2D) {
            SectionHeader(res(R.string.applist_setting_grid2d_options_category))
            SettingsSwitch(
                title = res(R.string.pref_grid2d_names_title),
                checked = grid2dNames,
                onCheckedChange = {
                    grid2dNames = it
                    appListPrefs.edit().putBoolean("grid2d_names", it).apply()
                },
            )
            SettingsSwitch(
                title = res(R.string.pref_grid2d_3d_title),
                checked = grid2d3d,
                onCheckedChange = {
                    grid2d3d = it
                    appListPrefs.edit().putBoolean("grid2d_3d", it).apply()
                },
            )
            if (grid2d3d) {
                SettingsSlider(
                    title = res(R.string.pref_3d_min_scale_title),
                    value = grid2d3dScale,
                    range = 20..100,
                    valueText = "$grid2d3dScale",
                    onChange = {
                        grid2d3dScale = it
                        appListPrefs.edit().putInt("grid2d_3d_min_scale", it).apply()
                    },
                )
            }
            SettingsSlider(
                title = res(R.string.pref_grid2d_icon_size_title),
                value = grid2dIconSize,
                range = 0..120,
                valueText = "$grid2dIconSize",
                onChange = {
                    grid2dIconSize = it
                    appListPrefs.edit().putInt("grid2d_icon_size", it).apply()
                },
            )
            SettingsSwitch(
                title = res(R.string.pref_round_overscroll_title),
                summary = res(R.string.pref_round_overscroll_summary),
                checked = grid2dRoundOverscroll,
                onCheckedChange = {
                    grid2dRoundOverscroll = it
                    appListPrefs.edit().putBoolean("grid2d_round_overscroll", it).apply()
                },
            )
        }

        if (style == AppListPrefs.STYLE_PAGES) {
            SectionHeader(res(R.string.applist_setting_pages_options_category))
            SettingsSlider(
                title = res(R.string.pref_pages_cols_title),
                value = pagesCols,
                range = 2..6,
                valueText = "$pagesCols",
                onChange = {
                    pagesCols = it
                    appListPrefs.edit().putInt("pages_cols", it).apply()
                },
            )
            SettingsSlider(
                title = res(R.string.pref_pages_rows_title),
                value = pagesRows,
                range = 2..8,
                valueText = "$pagesRows",
                onChange = {
                    pagesRows = it
                    appListPrefs.edit().putInt("pages_rows", it).apply()
                },
            )
        }

        if (style == AppListPrefs.STYLE_GRAVITY) {
            SectionHeader(res(R.string.applist_setting_gravity_options_category))
            SettingsSwitch(
                title = res(R.string.pref_gravity_accel_title),
                summary = res(R.string.pref_gravity_accel_summary),
                checked = gravityAccel,
                onCheckedChange = {
                    gravityAccel = it
                    appListPrefs.edit().putBoolean("gravity_accel", it).apply()
                },
            )
            SettingsSlider(
                title = res(R.string.pref_gravity_strength_title),
                value = gravityStrength,
                range = 0..20,
                valueText = "$gravityStrength",
                onChange = {
                    gravityStrength = it
                    appListPrefs.edit().putInt("gravity_strength", it).apply()
                },
            )
            SettingsSlider(
                title = res(R.string.pref_gravity_bounce_title),
                value = gravityBounce,
                range = 0..10,
                valueText = "$gravityBounce",
                onChange = {
                    gravityBounce = it
                    appListPrefs.edit().putInt("gravity_bounce", it).apply()
                },
            )
            SettingsSlider(
                title = res(R.string.pref_gravity_friction_title),
                value = gravityFriction,
                range = 0..10,
                valueText = "$gravityFriction",
                onChange = {
                    gravityFriction = it
                    appListPrefs.edit().putInt("gravity_friction", it).apply()
                },
            )
        }
    }

    if (showStyle) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_app_list_style_title),
            labels = styleEntries,
            selectedIndex = styleValues.indexOf(style).coerceAtLeast(0),
            onSelect = { index ->
                showStyle = false
                style = styleValues[index]
                appListPrefs.edit().putString("style", style).apply()
            },
            onDismiss = { showStyle = false },
        )
    }
    if (showSort) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_app_list_sort_title),
            labels = sortEntries,
            selectedIndex = sortValues.indexOf(sort).coerceAtLeast(0),
            onSelect = { index ->
                showSort = false
                sort = sortValues[index]
                appListPrefs.edit().putString("sort", sort).apply()
            },
            onDismiss = { showSort = false },
        )
    }
    if (showIconPack) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_icon_pack_title),
            labels = iconPackEntries,
            selectedIndex = iconPackValues.indexOf(iconPack).coerceAtLeast(0),
            onSelect = { index ->
                showIconPack = false
                iconPack = iconPackValues[index]
                appListPrefs.edit().putString("icon_pack", iconPack).apply()
                IconPackLoader.setSelectedPack(ctx, iconPack.ifEmpty { null })
            },
            onDismiss = { showIconPack = false },
        )
    }
    showCurveFor?.let { which ->
        val current = if (which == "list") list3dCurve else waterfall3dCurve
        SettingsPickerDialog(
            title = stringResource(R.string.pref_3d_curve_title),
            labels = curveEntries,
            selectedIndex = curveValues.indexOf(current).coerceAtLeast(0),
            onSelect = { index ->
                val value = curveValues[index]
                if (which == "list") {
                    list3dCurve = value
                    appListPrefs.edit().putString("list_3d_curve", value).apply()
                } else {
                    waterfall3dCurve = value
                    appListPrefs.edit().putString("waterfall_3d_curve", value).apply()
                }
                showCurveFor = null
            },
            onDismiss = { showCurveFor = null },
        )
    }
}

private fun saveAppListBackground(ctx: Context, uri: Uri): Boolean = runCatching {
    val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: return@runCatching false
    val dm = ctx.resources.displayMetrics
    val vw = dm.widthPixels
    val vh = dm.heightPixels
    val scale = maxOf(vw.toFloat() / src.width, vh.toFloat() / src.height)
    val sw = (src.width * scale).toInt()
    val sh = (src.height * scale).toInt()
    val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
    val cropped = Bitmap.createBitmap(scaled, (sw - vw) / 2, (sh - vh) / 2, vw, vh)
    val dir = File(ctx.filesDir, "backgrounds").apply { mkdirs() }
    val dest = File(dir, "app_list_bg.jpg")
    FileOutputStream(dest).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    AppListPrefs.saveBgImagePath(ctx, dest.absolutePath)
    true
}.getOrDefault(false)

private fun pushAppListBgToWallpaper(ctx: Context): Boolean = runCatching {
    val path = AppListPrefs.bgImagePath(ctx) ?: return@runCatching false
    val src = BitmapFactory.decodeFile(path) ?: return@runCatching false
    val dim = AppListPrefs.wallpaperDim(ctx)
    val dimAlpha = (dim / 100f * 255).toInt().coerceIn(0, 255)
    val bmp = if (dimAlpha > 0) {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(Color.argb(dimAlpha, 0, 0, 0))
        out
    } else src
    WallpaperManager.getInstance(ctx)
        .setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
    true
}.getOrDefault(false)
