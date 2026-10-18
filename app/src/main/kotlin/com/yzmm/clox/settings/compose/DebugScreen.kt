package com.yzmm.clox.settings.compose

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.preference.PreferenceManager
import com.yzmm.clox.R
import com.yzmm.clox.debug.DebugPrefs
import com.yzmm.clox.debug.LauncherMaintenance
import com.yzmm.clox.debug.SettingsBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DebugScreen() {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    val scope = rememberCoroutineScope()

    var ignoreLuaErrors by remember {
        mutableStateOf(prefs.getBoolean(DebugPrefs.KEY_IGNORE_LUA_ERRORS, false))
    }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var confirmForceStop by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }

    fun showStatus(message: String) {
        backupStatus = message
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                SettingsBackupManager.export(ctx, uri)
            }
            showStatus(ctx.getString(
                if (ok) R.string.debug_export_success else R.string.debug_export_failed))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingImport = uri }

    WearSettingsScreen(title = stringResource(R.string.pref_debug_title)) {
        SettingsSwitch(
            title = res(R.string.debug_ignore_lua_errors_title),
            summary = res(R.string.debug_ignore_lua_errors_summary),
            checked = ignoreLuaErrors,
            onCheckedChange = {
                ignoreLuaErrors = it
                prefs.edit().putBoolean(DebugPrefs.KEY_IGNORE_LUA_ERRORS, it).apply()
            },
        )

        SectionHeader(res(R.string.debug_category_backup))
        SettingsButton(
            title = res(R.string.debug_export_settings_title),
            summary = res(R.string.debug_export_settings_summary),
        ) { exportLauncher.launch("clox_settings_backup.json") }
        SettingsButton(
            title = res(R.string.debug_import_settings_title),
            summary = res(R.string.debug_import_settings_summary),
        ) { importLauncher.launch(arrayOf("application/json", "*/*")) }
        backupStatus?.let {
            SectionHeader(it)
        }

        SectionHeader(res(R.string.debug_category_danger))
        SettingsButton(
            title = res(R.string.debug_force_stop_title),
            summary = res(R.string.debug_force_stop_summary),
        ) { confirmForceStop = true }
        SettingsButton(
            title = res(R.string.debug_uninstall_title),
            summary = res(R.string.debug_uninstall_summary),
        ) { confirmUninstall = true }
    }

    pendingImport?.let { uri ->
        SettingsConfirmDialog(
            title = stringResource(R.string.debug_import_settings_title),
            message = stringResource(R.string.debug_import_settings_summary),
            confirmLabel = stringResource(android.R.string.ok),
            onConfirm = {
                pendingImport = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        SettingsBackupManager.import(ctx, uri)
                    }
                    if (ok) {
                        showStatus(ctx.getString(R.string.debug_import_success))
                        delay(1200)
                        LauncherMaintenance.forceStopAndRelaunch()
                    } else {
                        showStatus(ctx.getString(R.string.debug_import_failed))
                    }
                }
            },
            onDismiss = { pendingImport = null },
        )
    }
    if (confirmForceStop) {
        SettingsConfirmDialog(
            title = stringResource(R.string.debug_force_stop_confirm_title),
            message = stringResource(R.string.debug_force_stop_confirm_message),
            confirmLabel = stringResource(R.string.debug_force_stop_btn),
            onConfirm = { LauncherMaintenance.forceStopAndRelaunch() },
            onDismiss = { confirmForceStop = false },
        )
    }
    if (confirmUninstall) {
        SettingsConfirmDialog(
            title = stringResource(R.string.debug_uninstall_confirm_title),
            message = stringResource(R.string.debug_uninstall_confirm_message),
            confirmLabel = stringResource(R.string.debug_uninstall_btn),
            onConfirm = { LauncherMaintenance.uninstall(ctx) },
            onDismiss = { confirmUninstall = false },
        )
    }
}
