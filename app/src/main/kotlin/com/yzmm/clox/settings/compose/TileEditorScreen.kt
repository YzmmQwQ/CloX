package com.yzmm.clox.settings.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import com.yzmm.clox.qs.tile.TileId
import com.yzmm.clox.qs.tile.TileLayoutManager

@Composable
fun TileEditorScreen() {
    val ctx = LocalContext.current
    var addDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<com.yzmm.clox.qs.tile.CustomTileConfig?>(null) }

    var active by remember { mutableStateOf(TileLayoutManager.loadActiveTiles(ctx)) }
    var customVersion by remember { mutableStateOf(0) }

    val customTiles = remember(customVersion) { TileLayoutManager.loadCustomTiles(ctx) }
    val allIds = TileId.values().map { it.key } + customTiles.map { "custom:${it.uuid}" }
    val available = allIds.filter { it !in active }

    fun save(list: List<String>) {
        active = list
        TileLayoutManager.saveActiveTiles(ctx, list)
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in active.indices) return
        val list = active.toMutableList()
        val item = list.removeAt(index)
        list.add(target, item)
        save(list)
    }

    fun editCustom(id: String) {
        val uuid = id.removePrefix("custom:")
        editTarget = customTiles.firstOrNull { it.uuid == uuid }
    }

    WearSettingsScreen(title = stringResource(R.string.qs_tile_editor_title)) {
        SectionHeader(res(R.string.tile_editor_active_tiles))
        active.forEachIndexed { index, id ->
            SettingsItem { TileRow(
                id = id,
                onUp = if (index > 0) ({ move(index, -1) }) else null,
                onDown = if (index < active.lastIndex) ({ move(index, 1) }) else null,
                onRemove = {
                    save(active.toMutableList().also { it.removeAt(index) })
                },
                onClick = if (id.startsWith("custom:")) ({ editCustom(id) }) else null,
            ) }
        }

        SectionHeader(res(R.string.tile_editor_available_tiles))
        for (id in available) {
            SettingsItem { TileRow(
                id = id,
                onAdd = { save(active + id) },
                onClick = if (id.startsWith("custom:")) ({ editCustom(id) }) else null,
            ) }
        }

        SettingsButton(
            title = res(R.string.tile_editor_add_custom),
        ) { addDialog = true }
    }

    if (addDialog) {
        CustomTileDialog(
            onSave = { config ->
                addDialog = false
                TileLayoutManager.addCustomTile(ctx, config)
                save(active + "custom:${config.uuid}")
                customVersion++
            },
            onDismiss = { addDialog = false },
        )
    }
    editTarget?.let { existing ->
        CustomTileDialog(
            existing = existing,
            onSave = { updated ->
                editTarget = null
                TileLayoutManager.updateCustomTile(ctx, updated)
                customVersion++
            },
            onDismiss = { editTarget = null },
        )
    }
}

@Composable
private fun TileRow(
    id: String,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val descriptor = remember(id) { TileLayoutManager.descriptorFor(id, ctx) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        descriptor?.iconRes?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = descriptor?.defaultLabel ?: id,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (onUp != null) {
            IconButton(onClick = onUp) { Text("↑") }
        }
        if (onDown != null) {
            IconButton(onClick = onDown) { Text("↓") }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Text("✕") }
        }
        if (onAdd != null) {
            IconButton(onClick = onAdd) { Text("＋") }
        }
        if (onClick != null) {
            Text(
                text = "✎",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clickable { onClick() },
            )
        }
    }
}
