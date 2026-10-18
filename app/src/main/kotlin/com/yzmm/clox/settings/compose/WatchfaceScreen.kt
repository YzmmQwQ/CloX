package com.yzmm.clox.settings.compose

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.preference.PreferenceManager
import com.yzmm.clox.R
import com.yzmm.clox.util.safeStartActivity
import com.yzmm.clox.watchface.WatchfaceBatchImportActivity
import com.yzmm.clox.watchface.WatchfacePrefs
import com.yzmm.clox.watchface.gallery.GalleryActivity
import com.yzmm.clox.watchface.remote.RemoteManagementActivity
import com.yzmm.clox.watchface.selector.WatchfaceSelectorActivity
import com.yzmm.clox.watchface.store.WatchfaceStoreActivity

@Composable
fun WatchfaceScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }

    var selectorMode by remember {
        mutableStateOf(prefs.getString(WatchfacePrefs.KEY_SELECTOR_MODE, "pager") ?: "pager")
    }
    var sortOrder by remember {
        mutableStateOf(prefs.getString(WatchfacePrefs.KEY_SORT_ORDER, "name") ?: "name")
    }
    var autoScreen by remember {
        mutableStateOf(prefs.getBoolean(WatchfacePrefs.KEY_AUTO_CHANGE_ON_SCREEN_ON, false))
    }
    var autoInterval by remember {
        mutableStateOf(prefs.getString(WatchfacePrefs.KEY_AUTO_CHANGE_INTERVAL, "none") ?: "none")
    }

    var showSelectorMode by remember { mutableStateOf(false) }
    var showSortOrder by remember { mutableStateOf(false) }
    var showAutoInterval by remember { mutableStateOf(false) }

    val selectorEntries = stringArrayResource(R.array.wf_selector_mode_entries)
    val selectorValues = stringArrayResource(R.array.wf_selector_mode_values)
    val sortEntries = stringArrayResource(R.array.wf_sort_entries)
    val sortValues = stringArrayResource(R.array.wf_sort_values)
    val intervalEntries = stringArrayResource(R.array.wf_auto_interval_entries)
    val intervalValues = stringArrayResource(R.array.wf_auto_interval_values)

    fun homeIntent(extra: String) = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        setPackage(ctx.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        putExtra(com.yzmm.clox.MainActivity.EXTRA_CLOSE_OVERLAYS, true)
        putExtra(extra, true)
    }

    WearSettingsScreen(title = stringResource(R.string.pref_watchface_title)) {
        SettingsButton(
            title = res(R.string.pref_wf_open_selector_title),
            summary = res(R.string.pref_wf_open_selector_summary),
        ) { ctx.safeStartActivity(Intent(ctx, WatchfaceSelectorActivity::class.java)) }

        SettingsButton(
            title = res(R.string.pref_wf_open_config_title),
            summary = res(R.string.pref_wf_open_config_summary),
        ) {
            ctx.safeStartActivity(homeIntent(com.yzmm.clox.MainActivity.EXTRA_OPEN_WATCHFACE_CONFIG))
        }

        SettingsButton(
            title = res(R.string.pref_wf_defaults_title),
            summary = res(R.string.pref_wf_defaults_summary),
        ) {
            ctx.safeStartActivity(homeIntent(com.yzmm.clox.MainActivity.EXTRA_OPEN_WATCHFACE_DEFAULTS))
        }

        SectionHeader(res(R.string.pref_wf_category_selector))
        SettingsButton(
            title = res(R.string.pref_wf_selector_mode_title),
            summary = selectorEntries.getOrElse(selectorValues.indexOf(selectorMode)) { selectorMode },
        ) { showSelectorMode = true }
        SettingsButton(
            title = res(R.string.pref_wf_sort_title),
            summary = sortEntries.getOrElse(sortValues.indexOf(sortOrder)) { sortOrder },
        ) { showSortOrder = true }

        SectionHeader(res(R.string.pref_wf_category_auto))
        SettingsSwitch(
            title = res(R.string.pref_wf_auto_screen_on_title),
            summary = res(R.string.pref_wf_auto_screen_on_summary),
            checked = autoScreen,
            onCheckedChange = {
                autoScreen = it
                prefs.edit().putBoolean(WatchfacePrefs.KEY_AUTO_CHANGE_ON_SCREEN_ON, it).apply()
            },
        )
        SettingsButton(
            title = res(R.string.pref_wf_auto_interval_title),
            summary = intervalEntries.getOrElse(intervalValues.indexOf(autoInterval)) { autoInterval },
        ) { showAutoInterval = true }

        SectionHeader(res(R.string.pref_wf_category_management))
        SettingsButton(
            title = res(R.string.pref_wf_store_title),
            summary = res(R.string.pref_wf_store_summary),
        ) { ctx.safeStartActivity(Intent(ctx, WatchfaceStoreActivity::class.java)) }
        SettingsButton(
            title = res(R.string.pref_wf_remote_title),
            summary = res(R.string.pref_wf_remote_summary),
        ) { ctx.safeStartActivity(Intent(ctx, RemoteManagementActivity::class.java)) }
        SettingsButton(
            title = res(R.string.pref_wf_import_title),
            summary = res(R.string.pref_wf_import_summary),
        ) { ctx.safeStartActivity(Intent(ctx, WatchfaceBatchImportActivity::class.java)) }
        SettingsButton(
            title = res(R.string.pref_wf_gallery_title),
            summary = res(R.string.pref_wf_gallery_summary),
        ) { ctx.safeStartActivity(Intent(ctx, GalleryActivity::class.java)) }
    }

    if (showSelectorMode) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_wf_selector_mode_title),
            labels = selectorEntries,
            selectedIndex = selectorValues.indexOf(selectorMode).coerceAtLeast(0),
            onSelect = { index ->
                showSelectorMode = false
                selectorMode = selectorValues[index]
                prefs.edit().putString(WatchfacePrefs.KEY_SELECTOR_MODE, selectorMode).apply()
            },
            onDismiss = { showSelectorMode = false },
        )
    }
    if (showSortOrder) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_wf_sort_title),
            labels = sortEntries,
            selectedIndex = sortValues.indexOf(sortOrder).coerceAtLeast(0),
            onSelect = { index ->
                showSortOrder = false
                sortOrder = sortValues[index]
                prefs.edit().putString(WatchfacePrefs.KEY_SORT_ORDER, sortOrder).apply()
            },
            onDismiss = { showSortOrder = false },
        )
    }
    if (showAutoInterval) {
        SettingsPickerDialog(
            title = stringResource(R.string.pref_wf_auto_interval_title),
            labels = intervalEntries,
            selectedIndex = intervalValues.indexOf(autoInterval).coerceAtLeast(0),
            onSelect = { index ->
                showAutoInterval = false
                autoInterval = intervalValues[index]
                prefs.edit().putString(WatchfacePrefs.KEY_AUTO_CHANGE_INTERVAL, autoInterval).apply()
            },
            onDismiss = { showAutoInterval = false },
        )
    }
}
