package com.yzmm.clox.settings.compose

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
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
import com.yzmm.clox.R
import com.yzmm.clox.applist.AppListPrefs
import com.yzmm.clox.icon.IconResolver
import com.yzmm.clox.icon.renderToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class FolderAppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Bitmap,
    val selected: Boolean,
)

@Composable
fun FolderMembersScreen(folderId: String) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<FolderAppEntry>?>(null) }

    fun selectedCount(list: List<FolderAppEntry>) = list.count { it.selected }

    fun save(updated: List<FolderAppEntry>) {
        entries = updated
        AppListPrefs.setFolderMembers(
            ctx, folderId,
            updated.filter { it.selected }
                .map { "${it.packageName}/${it.activityName}" })
    }

    LaunchedEffect(folderId) {
        entries = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val members = AppListPrefs.folders(ctx)
                .firstOrNull { it.id == folderId }?.members?.toSet() ?: emptySet()
            val iconSizePx = (36 * ctx.resources.displayMetrics.density + 0.5f).toInt()
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).sortedBy { it.loadLabel(pm).toString().lowercase() }
                .map { ri ->
                    val key = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
                    FolderAppEntry(
                        packageName = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                        label = ri.loadLabel(pm).toString(),
                        icon = IconResolver.resolve(
                            ctx, ri.activityInfo.packageName, ri.activityInfo.name)
                            .renderToBitmap(iconSizePx),
                        selected = key in members,
                    )
                }
        }
    }

    WearSettingsScreen(title = stringResource(R.string.folder_members_title)) {
        val list = entries
        if (list != null) {
            SettingsNote(
                res(R.string.folder_member_count, selectedCount(list)) +
                    " / ${AppListPrefs.MAX_FOLDER_MEMBERS}"
            )
            list.forEach { entry ->
                SettingsItem {
                    CheckboxButton(
                        checked = entry.selected,
                        onCheckedChange = { checked ->
                            val current = entries ?: return@CheckboxButton
                            if (checked && selectedCount(current) >= AppListPrefs.MAX_FOLDER_MEMBERS) {
                                Toast.makeText(ctx, R.string.folder_full, Toast.LENGTH_SHORT).show()
                                return@CheckboxButton
                            }
                            save(current.map {
                                if (it.packageName == entry.packageName &&
                                    it.activityName == entry.activityName
                                ) it.copy(selected = checked) else it
                            })
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
    }
}
