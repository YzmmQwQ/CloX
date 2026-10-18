package com.ailife.clox.settings.compose

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ailife.clox.R
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.applist.FolderIconFactory

@Composable
fun FoldersScreen() {
    val ctx = LocalContext.current
    val navigate = LocalSettingsNavigator.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var folders by remember { mutableStateOf(AppListPrefs.folders(ctx)) }
    var renameTarget by remember { mutableStateOf<AppListPrefs.Folder?>(null) }
    var deleteTarget by remember { mutableStateOf<AppListPrefs.Folder?>(null) }
    var optionsTarget by remember { mutableStateOf<AppListPrefs.Folder?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) folders = AppListPrefs.folders(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    WearSettingsScreen(title = stringResource(R.string.folders_title)) {
        if (folders.isEmpty()) {
            SettingsNote(res(R.string.folders_empty))
        }
        for (folder in folders) {
            val iconSizePx = (44 * ctx.resources.displayMetrics.density).toInt()
            val icon = FolderIconFactory.build(
                ctx, folder.members, iconSizePx, FolderIconFactory.Shape.ROUNDED_RECT)
                .toBitmap(iconSizePx, iconSizePx)
                .asImageBitmap()
            SettingsButton(
                title = folder.name,
                summary = res(R.string.folder_member_count, folder.members.size),
                icon = {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                },
                onClick = { navigate(SettingsRoutes.folderMembers(folder.id)) },
                onLongClick = { optionsTarget = folder },
            )
        }
    }

    optionsTarget?.let { folder ->
        SettingsPickerDialog(
            title = folder.name,
            labels = arrayOf(
                stringResource(R.string.menu_edit_members),
                stringResource(R.string.menu_rename_folder),
                stringResource(R.string.menu_delete_folder),
            ),
            selectedIndex = 0,
            onSelect = { index ->
                optionsTarget = null
                when (index) {
                    0 -> navigate(SettingsRoutes.folderMembers(folder.id))
                    1 -> renameTarget = folder
                    2 -> deleteTarget = folder
                }
            },
            onDismiss = { optionsTarget = null },
        )
    }

    renameTarget?.let { folder ->
        SettingsTextDialog(
            title = stringResource(R.string.folder_rename_title),
            initial = folder.name,
            onConfirm = { name ->
                renameTarget = null
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    AppListPrefs.renameFolder(ctx, folder.id, trimmed)
                    folders = AppListPrefs.folders(ctx)
                }
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { folder ->
        SettingsConfirmDialog(
            title = stringResource(R.string.folder_delete_title),
            message = stringResource(R.string.folder_delete_message, folder.name),
            confirmLabel = stringResource(R.string.menu_delete_folder),
            onConfirm = {
                deleteTarget = null
                AppListPrefs.deleteFolder(ctx, folder.id)
                folders = AppListPrefs.folders(ctx)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}
