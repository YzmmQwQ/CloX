package com.yzmm.clox.settings.compose

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import com.yzmm.clox.update.UpdateController
import com.yzmm.clox.util.safeStartActivity

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val activity = ctx as? Activity

    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()
    }

    val updater = remember(activity) { activity?.let { UpdateController(it) } }
    var autoUpdate by remember { mutableStateOf(updater?.isUpdateCheckEnabled() ?: false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    DisposableEffect(updater) {
        updater?.onStatus = { state, text ->
            checking = state == UpdateController.CheckState.CHECKING
            status = text
        }
        onDispose { updater?.onStatus = null }
    }

    fun openUrl(url: String) = ctx.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    WearSettingsScreen(title = stringResource(R.string.pref_about_title)) {
        SettingsItem {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                Text(
                    text = res(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (versionName != null) {
                        res(R.string.about_version, versionName)
                    } else {
                        res(R.string.about_version_unknown)
                    },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsButton(
            title = res(R.string.about_onyx_project),
            summary = res(R.string.about_onyx_description),
        ) { openUrl("https://codeberg.org/OnyxUI/OnyxUI") }

        SectionHeader(res(R.string.about_developers_category))
        SettingsButton(
            title = res(R.string.about_dev1_name),
            summary = res(R.string.about_dev1_url),
        ) { openUrl("https://gitlab.com/ailife8881") }
        SettingsButton(
            title = res(R.string.about_dev2_name),
            summary = res(R.string.about_dev2_url),
        ) { openUrl("https://gitlab.com/Iroha9876") }

        SectionHeader(res(R.string.about_updates_category))
        SettingsSwitch(
            title = res(R.string.about_auto_update_title),
            summary = res(R.string.about_auto_update_summary),
            checked = autoUpdate,
            onCheckedChange = {
                autoUpdate = it
                updater?.setUpdateCheckEnabled(it)
            },
        )
        SettingsButton(
            title = res(R.string.about_check_updates_btn),
            summary = status ?: res(R.string.about_repo_url),
        ) {
            checking = true
            updater?.check(userInitiated = true)
        }
        if (checking) {
            SettingsItem {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
