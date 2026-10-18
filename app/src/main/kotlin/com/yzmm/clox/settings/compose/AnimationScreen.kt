package com.yzmm.clox.settings.compose

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yzmm.clox.R
import com.yzmm.clox.applist.AppListPrefs
import com.yzmm.clox.util.ClosingAppDetector
import java.util.Locale

@Composable
fun AnimationScreen() {
    val ctx = LocalContext.current
    val appListPrefs = remember {
        ctx.getSharedPreferences(AppListPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    val hasRemoteAnim = AppListPrefs.hasRemoteAnimPermission(ctx)
    val realEntry = stringResource(R.string.anim_setting_real_window_entry)

    val openEntries = stringArrayResource(R.array.open_anim_type_entries) + realEntry
    val openValues = stringArrayResource(R.array.open_anim_type_values) + AppListPrefs.OPEN_ANIM_REAL_WINDOW

    val snapshotOnApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val closeEntriesBase = stringArrayResource(R.array.close_anim_type_entries)
    val closeValuesBase = stringArrayResource(R.array.close_anim_type_values)
    val closeEntries = closeEntriesBase + realEntry +
        (if (snapshotOnApi) arrayOf(stringResource(R.string.anim_setting_snapshot_entry)) else emptyArray())
    val closeValues = closeValuesBase + AppListPrefs.CLOSE_ANIM_REAL_WINDOW +
        (if (snapshotOnApi) arrayOf(AppListPrefs.CLOSE_ANIM_SNAPSHOT) else emptyArray())

    val unavailable = if (hasRemoteAnim) emptySet() else setOf(
        AppListPrefs.OPEN_ANIM_REAL_WINDOW,
        AppListPrefs.CLOSE_ANIM_REAL_WINDOW,
        AppListPrefs.CLOSE_ANIM_SNAPSHOT)

    fun marked(entries: Array<String>, values: Array<String>) =
        entries.mapIndexed { i, e -> if (values[i] in unavailable) "$e ⚠" else e }.toTypedArray()

    var openOn by remember {
        mutableStateOf(
            appListPrefs.getString(AppListPrefs.KEY_LAUNCH_ANIM, AppListPrefs.LAUNCH_ANIM_CUSTOM) ==
                AppListPrefs.LAUNCH_ANIM_CUSTOM
        )
    }
    var openType by remember { mutableStateOf(AppListPrefs.openAnimType(ctx)) }
    var launchBlur by remember {
        mutableStateOf(appListPrefs.getBoolean(AppListPrefs.KEY_LAUNCH_BLUR, false))
    }
    var returnOn by remember {
        mutableStateOf(appListPrefs.getBoolean(AppListPrefs.KEY_RETURN_ANIM_ENABLED, true))
    }
    var closeType by remember { mutableStateOf(AppListPrefs.closeAnimType(ctx)) }
    var resumeClose by remember {
        mutableStateOf(appListPrefs.getBoolean(AppListPrefs.KEY_RESUME_CLOSE_ANIM, false))
    }

    var gestureReturnOn by remember {
        mutableStateOf(appListPrefs.getBoolean(AppListPrefs.KEY_GESTURE_RETURN_ENABLED, true))
    }
    var gestureOnly by remember {
        mutableStateOf(appListPrefs.getBoolean(AppListPrefs.KEY_GESTURE_RETURN_ONLY_GESTURE, true))
    }
    var gestureHandoff by remember {
        mutableStateOf(appListPrefs.getBoolean(AppListPrefs.KEY_GESTURE_HANDOFF_SURFACE, true))
    }
    val gestureReturnEntries = stringArrayResource(R.array.gesture_return_anim_entries)
    val gestureReturnValues = stringArrayResource(R.array.gesture_return_anim_values)
    var gestureType by remember {
        mutableStateOf(
            appListPrefs.getString(
                AppListPrefs.KEY_GESTURE_RETURN_ANIM, AppListPrefs.GESTURE_RETURN_CIRCLE)
                ?: AppListPrefs.GESTURE_RETURN_CIRCLE
        )
    }
    var gestureSpeed by remember {
        mutableStateOf(appListPrefs.getFloat(AppListPrefs.KEY_GESTURE_RETURN_SPEED, 1.0f)
            .coerceIn(0.5f, 2.0f))
    }
    var gestureDelay by remember {
        mutableStateOf(appListPrefs.getInt(AppListPrefs.KEY_GESTURE_RETURN_DELAY, 200)
            .coerceIn(0, 500))
    }

    var detectClosing by remember {
        mutableStateOf(
            ClosingAppDetector.hasUsagePermission(ctx) && AppListPrefs.detectClosingApp(ctx)
        )
    }
    var pendingEnableDetect by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }

    var animSpeed by remember {
        mutableStateOf(appListPrefs.getFloat(AppListPrefs.KEY_ANIM_SPEED, 1.0f)
            .coerceIn(0.5f, 2.0f))
    }
    var closeSpeed by remember {
        mutableStateOf(appListPrefs.getFloat(AppListPrefs.KEY_CLOSE_ANIM_SPEED, 1.0f)
            .coerceIn(0.5f, 2.0f))
    }
    var closeDelay by remember {
        mutableStateOf(appListPrefs.getInt(AppListPrefs.KEY_CLOSE_ANIM_DELAY, 50)
            .coerceIn(0, 500))
    }

    var showOpenType by remember { mutableStateOf(false) }
    var showCloseType by remember { mutableStateOf(false) }
    var showGestureType by remember { mutableStateOf(false) }
    var showRemoteUnavailable by remember { mutableStateOf(false) }

    // Reconcile the usage-access switch after returning from the system screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ClosingAppDetector.hasUsagePermission(ctx)
                if (!granted && AppListPrefs.detectClosingApp(ctx)) {
                    AppListPrefs.setDetectClosingApp(ctx, false)
                }
                if (pendingEnableDetect && granted) {
                    pendingEnableDetect = false
                    AppListPrefs.setDetectClosingApp(ctx, true)
                }
                detectClosing = AppListPrefs.detectClosingApp(ctx) && granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    WearSettingsScreen(title = stringResource(R.string.applist_setting_animations_title)) {
        SectionHeader(res(R.string.anim_setting_open_category))
        SettingsSwitch(
            title = res(R.string.anim_setting_open_title),
            summary = res(R.string.anim_setting_open_summary),
            checked = openOn,
            onCheckedChange = {
                openOn = it
                appListPrefs.edit().putString(
                    AppListPrefs.KEY_LAUNCH_ANIM,
                    if (it) AppListPrefs.LAUNCH_ANIM_CUSTOM else AppListPrefs.LAUNCH_ANIM_SYSTEM,
                ).apply()
            },
        )
        if (openOn) {
            SettingsButton(
                title = res(R.string.anim_setting_open_type_title),
                summary = openEntries.getOrElse(openValues.indexOf(openType)) { openType },
            ) { showOpenType = true }
        }
        SettingsSwitch(
            title = res(R.string.anim_setting_launch_blur_title),
            summary = res(R.string.anim_setting_launch_blur_summary),
            checked = launchBlur,
            onCheckedChange = {
                launchBlur = it
                appListPrefs.edit().putBoolean(AppListPrefs.KEY_LAUNCH_BLUR, it).apply()
            },
        )

        SectionHeader(res(R.string.anim_setting_return_category))
        SettingsSwitch(
            title = res(R.string.anim_setting_return_title),
            summary = res(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    R.string.anim_setting_return_summary_11plus
                else R.string.anim_setting_return_summary_pre11
            ),
            checked = returnOn,
            onCheckedChange = {
                returnOn = it
                appListPrefs.edit().putBoolean(AppListPrefs.KEY_RETURN_ANIM_ENABLED, it).apply()
            },
        )
        if (returnOn) {
            SettingsButton(
                title = res(R.string.anim_setting_close_type_title),
                summary = closeEntries.getOrElse(closeValues.indexOf(closeType)) { closeType },
            ) { showCloseType = true }
        }
        SettingsSwitch(
            title = res(R.string.anim_setting_resume_close_title),
            summary = res(R.string.anim_setting_resume_close_summary),
            checked = resumeClose,
            onCheckedChange = {
                resumeClose = it
                appListPrefs.edit().putBoolean(AppListPrefs.KEY_RESUME_CLOSE_ANIM, it).apply()
            },
        )

        SectionHeader(res(R.string.anim_setting_gesture_return_category))
        SettingsSwitch(
            title = res(R.string.anim_setting_gesture_return_title),
            summary = res(R.string.anim_setting_gesture_return_summary),
            checked = gestureReturnOn,
            onCheckedChange = {
                gestureReturnOn = it
                appListPrefs.edit().putBoolean(AppListPrefs.KEY_GESTURE_RETURN_ENABLED, it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.anim_setting_gesture_handoff_title),
            summary = res(R.string.anim_setting_gesture_handoff_summary),
            checked = gestureHandoff,
            onCheckedChange = {
                gestureHandoff = it
                appListPrefs.edit().putBoolean(AppListPrefs.KEY_GESTURE_HANDOFF_SURFACE, it).apply()
            },
        )
        SettingsSwitch(
            title = res(R.string.anim_setting_detect_closing_title),
            summary = res(R.string.anim_setting_detect_closing_summary),
            checked = detectClosing,
            onCheckedChange = { checked ->
                if (!checked) {
                    detectClosing = false
                    AppListPrefs.setDetectClosingApp(ctx, false)
                } else if (ClosingAppDetector.hasUsagePermission(ctx)) {
                    detectClosing = true
                    AppListPrefs.setDetectClosingApp(ctx, true)
                } else {
                    pendingEnableDetect = true
                    showUsageDialog = true
                }
            },
        )
        if (gestureReturnOn) {
            SettingsButton(
                title = res(R.string.anim_setting_gesture_return_type_title),
                summary = gestureReturnEntries
                    .getOrElse(gestureReturnValues.indexOf(gestureType)) { gestureType },
            ) { showGestureType = true }
            SettingsSwitch(
                title = res(R.string.anim_setting_gesture_return_only_title),
                summary = res(R.string.anim_setting_gesture_return_only_summary),
                checked = gestureOnly,
                onCheckedChange = {
                    gestureOnly = it
                    appListPrefs.edit().putBoolean(
                        AppListPrefs.KEY_GESTURE_RETURN_ONLY_GESTURE, it).apply()
                },
            )
            SettingsSliderFloat(
                title = res(R.string.anim_setting_gesture_return_speed_title),
                value = gestureSpeed,
                range = 0.5f..2.0f,
                steps = 14,
                valueText = String.format(Locale.US, "%.1f×", gestureSpeed),
                onChange = {
                    gestureSpeed = it
                    appListPrefs.edit().putFloat(
                        AppListPrefs.KEY_GESTURE_RETURN_SPEED, it).apply()
                },
            )
            SettingsSlider(
                title = res(R.string.anim_setting_gesture_return_delay_title),
                value = gestureDelay,
                range = 0..500,
                valueText = "$gestureDelay ms",
                onChange = {
                    gestureDelay = it
                    appListPrefs.edit().putInt(
                        AppListPrefs.KEY_GESTURE_RETURN_DELAY, it).apply()
                },
            )
        }

        SectionHeader(res(R.string.anim_setting_speed_category))
        SettingsSliderFloat(
            title = res(R.string.anim_setting_speed_title),
            value = animSpeed,
            range = 0.5f..2.0f,
            steps = 14,
            valueText = String.format(Locale.US, "%.1f×", animSpeed),
            onChange = {
                animSpeed = it
                appListPrefs.edit().putFloat(AppListPrefs.KEY_ANIM_SPEED, it).apply()
            },
        )

        SectionHeader(res(R.string.anim_setting_close_speed_category))
        SettingsSliderFloat(
            title = res(R.string.anim_setting_close_speed_title),
            value = closeSpeed,
            range = 0.5f..2.0f,
            steps = 14,
            valueText = String.format(Locale.US, "%.1f×", closeSpeed),
            onChange = {
                closeSpeed = it
                appListPrefs.edit().putFloat(AppListPrefs.KEY_CLOSE_ANIM_SPEED, it).apply()
            },
        )
        SettingsSlider(
            title = res(R.string.anim_setting_close_delay_title),
            value = closeDelay,
            range = 0..500,
            valueText = "$closeDelay ms",
            onChange = {
                closeDelay = it
                appListPrefs.edit().putInt(AppListPrefs.KEY_CLOSE_ANIM_DELAY, it).apply()
            },
        )
    }

    if (showOpenType) {
        SettingsPickerDialog(
            title = stringResource(R.string.anim_setting_open_type_title),
            labels = marked(openEntries, openValues),
            selectedIndex = openValues.indexOf(openType).coerceAtLeast(0),
            onSelect = { index ->
                showOpenType = false
                val value = openValues[index]
                if (value in unavailable) showRemoteUnavailable = true
                else {
                    openType = value
                    appListPrefs.edit().putString(AppListPrefs.KEY_OPEN_ANIM_TYPE, value).apply()
                }
            },
            onDismiss = { showOpenType = false },
        )
    }
    if (showCloseType) {
        SettingsPickerDialog(
            title = stringResource(R.string.anim_setting_close_type_title),
            labels = marked(closeEntries, closeValues),
            selectedIndex = closeValues.indexOf(closeType).coerceAtLeast(0),
            onSelect = { index ->
                showCloseType = false
                val value = closeValues[index]
                if (value in unavailable) showRemoteUnavailable = true
                else {
                    closeType = value
                    appListPrefs.edit().putString(AppListPrefs.KEY_CLOSE_ANIM_TYPE, value).apply()
                }
            },
            onDismiss = { showCloseType = false },
        )
    }
    if (showGestureType) {
        SettingsPickerDialog(
            title = stringResource(R.string.anim_setting_gesture_return_type_title),
            labels = gestureReturnEntries,
            selectedIndex = gestureReturnValues.indexOf(gestureType).coerceAtLeast(0),
            onSelect = { index ->
                showGestureType = false
                gestureType = gestureReturnValues[index]
                appListPrefs.edit().putString(
                    AppListPrefs.KEY_GESTURE_RETURN_ANIM, gestureType).apply()
            },
            onDismiss = { showGestureType = false },
        )
    }
    if (showRemoteUnavailable) {
        SettingsInfoDialog(
            title = stringResource(R.string.anim_setting_remote_unavailable_title),
            message = stringResource(R.string.anim_setting_remote_unavailable_msg),
            onDismiss = { showRemoteUnavailable = false },
        )
    }
    if (showUsageDialog) {
        SettingsConfirmDialog(
            title = stringResource(R.string.anim_setting_detect_closing_perm_title),
            message = stringResource(R.string.anim_setting_detect_closing_perm_msg),
            confirmLabel = stringResource(R.string.anim_setting_detect_closing_grant),
            onConfirm = {
                ClosingAppDetector.openUsageAccessSettings(ctx)
                showUsageDialog = false
            },
            onDismiss = {
                showUsageDialog = false
                pendingEnableDetect = false
            },
        )
    }
}
