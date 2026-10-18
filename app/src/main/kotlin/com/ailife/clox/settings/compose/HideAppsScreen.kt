package com.ailife.clox.settings.compose

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.Text
import com.ailife.clox.R
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.icon.IconResolver
import com.ailife.clox.icon.renderToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HideAppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Bitmap,
    val selected: Boolean,
)

@Composable
fun HideAppsScreen() {
    val ctx = LocalContext.current

    var whitelist by remember {
        mutableStateOf(AppListPrefs.hideMode(ctx) == AppListPrefs.HIDE_MODE_WHITELIST)
    }
    var entries by remember { mutableStateOf<List<HideAppEntry>?>(null) }
    var password by remember { mutableStateOf(AppListPrefs.hideAppsPassword(ctx)) }
    var showExplain by remember { mutableStateOf(false) }
    var showInput by remember { mutableStateOf(false) }

    fun save(updated: List<HideAppEntry>) {
        entries = updated
        AppListPrefs.saveHiddenComponents(
            ctx, updated.filter { it.selected }
                .map { "${it.packageName}/${it.activityName}" }.toSet())
    }

    fun saveMode(isWhitelist: Boolean) {
        whitelist = isWhitelist
        AppListPrefs.get(ctx).edit().putString(
            AppListPrefs.KEY_HIDE_MODE,
            if (isWhitelist) AppListPrefs.HIDE_MODE_WHITELIST
            else AppListPrefs.HIDE_MODE_BLACKLIST,
        ).apply()
    }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val hidden = AppListPrefs.hiddenComponents(ctx)
            val iconSizePx = (36 * ctx.resources.displayMetrics.density + 0.5f).toInt()
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).sortedBy { it.loadLabel(pm).toString().lowercase() }
                .map { ri ->
                    val key = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
                    HideAppEntry(
                        packageName = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                        label = ri.loadLabel(pm).toString(),
                        icon = IconResolver.resolve(
                            ctx, ri.activityInfo.packageName, ri.activityInfo.name)
                            .renderToBitmap(iconSizePx),
                        selected = key in hidden,
                    )
                }
        }
    }

    WearSettingsScreen(title = stringResource(R.string.hidden_apps_title)) {
        SettingsButton(
            title = res(R.string.hide_apps_tab_blacklist),
            enabled = whitelist,
        ) { saveMode(false) }
        SettingsButton(
            title = res(R.string.hide_apps_tab_whitelist),
            enabled = !whitelist,
        ) { saveMode(true) }

        SettingsButton(
            title = res(R.string.cd_select_all),
            icon = { androidx.wear.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
            ) },
        ) { entries?.let { save(it.map { e -> e.copy(selected = true) }) } }

        SettingsButton(
            title = res(R.string.cd_deselect_all),
            icon = { androidx.wear.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_close),
                contentDescription = null,
            ) },
        ) { entries?.let { save(it.map { e -> e.copy(selected = false) }) } }

        SettingsSwitch(
            title = res(R.string.hide_apps_secret_code_btn),
            checked = password != null,
            onCheckedChange = { checked ->
                if (checked) showExplain = true
                else {
                    AppListPrefs.saveHideAppsPassword(ctx, null)
                    password = null
                }
            },
        )
        password?.let { pw ->
            SettingsNote(res(R.string.secret_code_dial_hint, pw))
        }

        entries?.forEach { entry ->
            SettingsItem {
                CheckboxButton(
                    checked = entry.selected,
                    onCheckedChange = { checked ->
                        entries?.let { list ->
                            save(list.map {
                                if (it.packageName == entry.packageName &&
                                    it.activityName == entry.activityName
                                ) it.copy(selected = checked) else it
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Image(
                            bitmap = entry.icon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = entry.activityName.substringAfterLast('.'),
                            style = androidx.wear.compose.material3.MaterialTheme.typography.labelSmall,
                        )
                    },
                    label = { Text(text = entry.label) },
                )
            }
        }
    }

    if (showExplain) {
        SettingsConfirmDialog(
            title = stringResource(R.string.dialog_secret_code_title),
            message = stringResource(R.string.dialog_secret_code_message),
            confirmLabel = stringResource(R.string.dialog_secret_code_positive),
            onConfirm = {
                showExplain = false
                showInput = true
            },
            onDismiss = { showExplain = false },
        )
    }
    if (showInput) {
        SettingsTextDialog(
            title = stringResource(R.string.dialog_enter_password_title),
            initial = "",
            numeric = true,
            onConfirm = { value ->
                showInput = false
                val pw = value.trim()
                if (pw.length >= 4 && pw.all { it.isDigit() }) {
                    AppListPrefs.saveHideAppsPassword(ctx, pw)
                    password = pw
                }
            },
            onDismiss = { showInput = false },
        )
    }
}
