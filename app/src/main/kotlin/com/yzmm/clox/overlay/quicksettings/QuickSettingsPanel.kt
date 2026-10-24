package com.yzmm.clox.overlay.quicksettings

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconToggleButton
import androidx.wear.compose.material3.IconToggleButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import com.yzmm.clox.qs.QsPrefs
import com.yzmm.clox.qs.QsStateManager
import com.yzmm.clox.qs.TileState
import com.yzmm.clox.qs.tile.CustomTileMode
import com.yzmm.clox.qs.tile.TileLayoutManager
import com.yzmm.clox.ui.theme.isRoundScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class QsPanelAction { SETTINGS, RECENTS, FLASHLIGHT, POWER, SYSTEM_SETTINGS }

/**
 * Wear M3 control center: header, brightness/volume, a paged 3-column grid of native
 * [ButtonGroup] rows (three [IconToggleButton]s each, so pressing grows the touched button and
 * squeezes its neighbours), an optional media card and the action bar. The panel is hosted by
 * [QuickSettingsOverlay] and scrolls vertically; horizontal swipes belong to the tile pager.
 */
@Composable
fun QuickSettingsPanel(
    stateManager: QsStateManager,
    mediaController: QsMediaController,
    revision: Int,
    onTileClick: (String) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onTimeClick: () -> Unit,
    onDateClick: () -> Unit,
    onBatteryClick: () -> Unit,
    onAction: (QsPanelAction) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    val round = isRoundScreen()

    val time by stateManager.time.collectAsState()
    val battery by stateManager.battery.collectAsState()
    val brightness by stateManager.brightness.collectAsState()
    val volume by stateManager.volume.collectAsState()
    val media by mediaController.state.collectAsState()

    val rows = remember(revision, prefs) {
        (prefs.getString(QsPrefs.TILE_ROWS, "2")?.toIntOrNull() ?: 2).coerceIn(2, 3)
    }
    val tiles = remember(revision, prefs) { TileLayoutManager.loadActiveTiles(ctx) }
    val pages = remember(tiles, rows) { tiles.chunked(TileLayoutManager.COLUMNS * rows) }
    val customTiles = remember(revision, prefs) {
        TileLayoutManager.loadCustomTiles(ctx).associateBy { it.uuid }
    }

    val gridSpacing = 4.dp
    val tileHeight = 76.dp
    val pagerHeight = tileHeight * rows + gridSpacing * (rows - 1)
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    LaunchedEffect(revision) { pagerState.scrollToPage(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f)),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = if (round) 12.dp else 8.dp,
                end = if (round) 12.dp else 8.dp,
                top = 1.dp,
                bottom = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
        item {
            HeaderRow(
                time = time,
                date = SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date()),
                level = battery.level,
                charging = battery.charging,
                onTimeClick = onTimeClick,
                onDateClick = onDateClick,
                onBatteryClick = onBatteryClick,
            )
        }
        item {
            SliderRow(
                iconRes = R.drawable.baseline_brightness_low_24,
                trailingIconRes = R.drawable.baseline_brightness_high_24,
                value = brightness.toFloat(),
                range = 0f..255f,
                onChange = { onBrightnessChange(it.toInt()) },
            )
        }
        item {
            SliderRow(
                iconRes = R.drawable.ic_volume,
                trailing = "${(if (volume.max > 0) volume.level * 100 / volume.max else 0)}%",
                value = volume.level.toFloat(),
                range = 0f..volume.max.coerceAtLeast(1).toFloat(),
                onChange = { onVolumeChange(it.toInt()) },
            )
        }
        if (tiles.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.qs_no_tiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            item {
                Column {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(pagerHeight),
                        pageSpacing = 8.dp,
                        userScrollEnabled = pages.size > 1,
                    ) { pageIndex ->
                        val page = pages.getOrNull(pageIndex).orEmpty()
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(gridSpacing),
                        ) {
                            repeat(rows) { rowIndex ->
                                ButtonGroup(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    spacing = gridSpacing,
                                    expansionWidth = 18.dp,
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    repeat(TileLayoutManager.COLUMNS) { columnIndex ->
                                        val id = page.getOrNull(
                                            rowIndex * TileLayoutManager.COLUMNS + columnIndex
                                        )
                                        if (id == null) {
                                            Spacer(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                            )
                                        } else {
                                            val interactionSource = remember(id) {
                                                MutableInteractionSource()
                                            }
                                            TileCard(
                                                tileId = id,
                                                stateManager = stateManager,
                                                customTiles = customTiles,
                                                revision = revision,
                                                interactionSource = interactionSource,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .minWidth(IconToggleButtonDefaults.Size)
                                                    .animateWidth(interactionSource)
                                                    .fillMaxHeight(),
                                                onClick = onTileClick,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (media.active) {
            item {
                MediaCard(
                    title = media.title.orEmpty(),
                    artist = media.artist,
                    playing = media.playing,
                    onPrevious = mediaController::previous,
                    onToggle = mediaController::togglePlayPause,
                    onNext = mediaController::next,
                )
            }
        }
    }
    if (pages.size > 1) {
        PageIndicator(count = pages.size, selected = pagerState.currentPage)
    }
    Divider()
    BottomBar(onAction = onAction)
    }
}

@Composable
private fun HeaderRow(
    time: String,
    date: String,
    level: Int,
    charging: Boolean,
    onTimeClick: () -> Unit,
    onDateClick: () -> Unit,
    onBatteryClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable(onClick = onTimeClick),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onBatteryClick),
        ) {
            Icon(
                painter = painterResource(
                    if (charging) R.drawable.baseline_battery_charging_full_24
                    else R.drawable.baseline_battery_full_24
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "$level%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = date,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onDateClick),
        )
    }
}

@Composable
private fun SliderRow(
    iconRes: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    trailingIconRes: Int? = null,
    trailing: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            steps = 0,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        )
        when {
            trailingIconRes != null -> Icon(
                painter = painterResource(trailingIconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            trailing != null -> Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
        }
    }
}

@Composable
private fun MediaCard(
    title: String,
    artist: String?,
    playing: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!artist.isNullOrBlank()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MediaButton(R.drawable.ic_skip_prev, R.string.cd_media_previous, onPrevious)
            MediaButton(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) R.string.cd_media_pause else R.string.cd_media_play,
                onToggle,
            )
            MediaButton(R.drawable.ic_skip_next, R.string.cd_media_next, onNext)
        }
    }
}

@Composable
private fun MediaButton(iconRes: Int, descriptionRes: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(descriptionRes),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun TileCard(
    tileId: String,
    stateManager: QsStateManager,
    customTiles: Map<String, com.yzmm.clox.qs.tile.CustomTileConfig>,
    revision: Int,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
    onClick: (String) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    val descriptor = remember(tileId, revision, customTiles) {
        TileLayoutManager.descriptorFor(tileId, ctx)
    }
    val stateFlow = remember(tileId) {
        if (tileId.startsWith("custom:")) null else stateManager.stateFor(tileId)
    }
    val state = stateFlow?.collectAsState()?.value

    var customTick by remember(tileId) { mutableIntStateOf(0) }

    val customConfig = remember(tileId, customTiles, revision, customTick) {
        if (tileId.startsWith("custom:")) {
            customTiles[tileId.removePrefix("custom:")]
        } else null
    }
    val customActive = remember(tileId, customConfig, customTick) {
        readCustomSetting(ctx, customConfig)
    }

    if (descriptor == null) {
        Spacer(modifier = modifier)
        return
    }

    val visual = tileVisual(ctx, tileId, state, customActive)
    val accent = tileAccent(prefs, tileId, visual.active)
    val solid = prefs.getString(QsPrefs.TILE_STYLE, QsPrefs.TILE_STYLE_SOLID) ==
        QsPrefs.TILE_STYLE_SOLID
    val description = listOfNotNull(descriptor.defaultLabel, visual.detail)
        .joinToString(", ")

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconToggleButton(
            checked = visual.active,
            onCheckedChange = {
                onClick(tileId)
                if (tileId.startsWith("custom:")) customTick++
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(IconToggleButtonDefaults.Size),
            colors = IconToggleButtonDefaults.colors(
                checkedContainerColor = accent ?: MaterialTheme.colorScheme.primary,
                checkedContentColor = if (accent != null) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            ),
            interactionSource = interactionSource,
            shapes = IconToggleButtonDefaults.variantAnimatedShapes(),
            border = if (solid) null else BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Icon(
                painter = painterResource(visual.iconRes),
                contentDescription = description,
                modifier = Modifier.size(IconToggleButtonDefaults.DefaultIconSize),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = descriptor.defaultLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (visual.active) {
                accent ?: MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PageIndicator(count: Int, selected: Int) {
    Text(
        text = "${selected + 1} / $count",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    )
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

@Composable
private fun BottomBar(onAction: (QsPanelAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(R.drawable.baseline_tune_24, R.string.cd_launcher_settings, Modifier.size(34.dp)) {
            onAction(QsPanelAction.SETTINGS)
        }
        ActionButton(R.drawable.ic_recents, R.string.cd_recents, Modifier.size(34.dp)) {
            onAction(QsPanelAction.RECENTS)
        }
        ActionButton(R.drawable.baseline_flashlight_on_24, R.string.cd_flashlight, Modifier.size(34.dp)) {
            onAction(QsPanelAction.FLASHLIGHT)
        }
        ActionButton(R.drawable.ic_power, R.string.cd_power, Modifier.size(34.dp)) {
            onAction(QsPanelAction.POWER)
        }
        ActionButton(R.drawable.baseline_settings_24, R.string.cd_settings, Modifier.size(34.dp)) {
            onAction(QsPanelAction.SYSTEM_SETTINGS)
        }
    }
}

@Composable
private fun ActionButton(iconRes: Int, descriptionRes: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(descriptionRes),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

// ── Tile visuals ────────────────────────────────────────────────────────────

private data class TileVisual(
    val iconRes: Int,
    val active: Boolean,
    val detail: String? = null,
)

private fun tileVisual(
    ctx: Context,
    tileId: String,
    state: TileState?,
    customActive: Boolean?,
): TileVisual {
    if (tileId.startsWith("custom:")) {
        return TileVisual(
            iconRes = R.drawable.baseline_settings_24,
            active = customActive == true,
            detail = if (customActive != null) {
                ctx.getString(if (customActive) R.string.qs_tile_on else R.string.qs_tile_off)
            } else null,
        )
    }
    return when (state) {
        is TileState.WifiState -> TileVisual(
            iconRes = wifiSignalDrawable(state.signalLevel),
            active = state.enabled,
            detail = if (state.connected) {
                listOfNotNull(
                    state.ssid ?: ctx.getString(R.string.qs_tile_connected),
                    state.frequency,
                ).joinToString(" · ")
            } else null,
        )
        is TileState.DataState -> TileVisual(
            iconRes = cellularSignalDrawable(if (state.activelyRouting) state.signalLevel else 0),
            active = state.enabled,
            detail = when {
                state.activelyRouting -> listOfNotNull(state.operatorName, state.networkType)
                    .joinToString(" · ")
                    .ifBlank { null }
                state.enabled -> ctx.getString(R.string.qs_tile_not_in_use)
                else -> null
            },
        )
        is TileState.BtState -> TileVisual(
            iconRes = R.drawable.baseline_bluetooth_24,
            active = state.enabled,
            detail = when {
                !state.enabled -> null
                state.connectedDeviceName != null -> state.connectedDeviceName
                else -> ctx.getString(R.string.qs_tile_on_detail)
            },
        )
        is TileState.VolumeState -> {
            val pct = if (state.max > 0) "${state.level * 100 / state.max}%" else "--"
            TileVisual(
                iconRes = R.drawable.baseline_volume_up_24,
                active = state.ringerMode != AudioManager.RINGER_MODE_SILENT && state.level > 0,
                detail = when (state.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> ctx.getString(R.string.qs_tile_silent)
                    AudioManager.RINGER_MODE_VIBRATE -> "${ctx.getString(R.string.qs_tile_vibrate)}  $pct"
                    else -> pct
                },
            )
        }
        is TileState.DndState -> TileVisual(
            iconRes = R.drawable.baseline_do_not_disturb_24,
            active = state.active,
            detail = when (state.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> ctx.getString(R.string.qs_tile_silent)
                AudioManager.RINGER_MODE_VIBRATE -> ctx.getString(R.string.qs_tile_vibrate)
                else -> null
            },
        )
        is TileState.RotateState -> TileVisual(
            iconRes = R.drawable.baseline_screen_rotation_24,
            active = !state.locked,
            detail = ctx.getString(if (state.locked) R.string.qs_tile_locked else R.string.qs_tile_auto),
        )
        is TileState.FlashlightState -> TileVisual(
            iconRes = R.drawable.baseline_flashlight_on_24,
            active = state.on,
        )
        is TileState.LocationState -> TileVisual(
            iconRes = R.drawable.baseline_gps_fixed_24,
            active = state.enabled,
        )
        is TileState.AodState -> TileVisual(
            iconRes = R.drawable.baseline_aod_24,
            active = state.enabled,
            detail = if (state.enabled) ctx.getString(R.string.qs_tile_on) else null,
        )
        is TileState.AirplaneState -> TileVisual(
            iconRes = R.drawable.baseline_airplanemode_active_24,
            active = state.enabled,
            detail = ctx.getString(if (state.enabled) R.string.qs_tile_on else R.string.qs_tile_off),
        )
        is TileState.HotspotState -> TileVisual(
            iconRes = R.drawable.baseline_wifi_tethering_24,
            active = state.enabled,
            detail = ctx.getString(if (state.enabled) R.string.qs_tile_on else R.string.qs_tile_off),
        )
        is TileState.BatterySaverState -> TileVisual(
            iconRes = R.drawable.baseline_battery_saver_24,
            active = state.enabled,
            detail = ctx.getString(if (state.enabled) R.string.qs_tile_on else R.string.qs_tile_off),
        )
        is TileState.SyncState -> TileVisual(
            iconRes = R.drawable.baseline_sync_24,
            active = state.enabled,
            detail = ctx.getString(if (state.enabled) R.string.qs_tile_on else R.string.qs_tile_off),
        )
        else -> TileVisual(
            iconRes = R.drawable.baseline_settings_24,
            active = false,
        )
    }
}

private fun wifiSignalDrawable(level: Int) = when (level) {
    1 -> R.drawable.zzzzk_ic_wifi_signal_1
    2 -> R.drawable.zzzzk_ic_wifi_signal_2
    3 -> R.drawable.zzzzk_ic_wifi_signal_3
    4 -> R.drawable.zzzzk_ic_wifi_signal_4
    else -> R.drawable.zzzzk_ic_wifi_signal_0
}

private fun cellularSignalDrawable(level: Int) = when (level) {
    1 -> R.drawable.zzzzk_ic_signal_cellular_1
    2 -> R.drawable.zzzzk_ic_signal_cellular_2
    3 -> R.drawable.zzzzk_ic_signal_cellular_3
    4 -> R.drawable.zzzzk_ic_signal_cellular_4
    else -> R.drawable.zzzzk_ic_signal_cellular_0
}

private fun readCustomSetting(
    ctx: Context,
    config: com.yzmm.clox.qs.tile.CustomTileConfig?,
): Boolean? {
    if (config?.mode != CustomTileMode.WRITE_SETTINGS) return null
    val ns = config.settingsNamespace ?: return null
    val key = config.settingsKey ?: return null
    return try {
        when (ns) {
            "system" -> Settings.System.getInt(ctx.contentResolver, key, 0)
            "secure" -> Settings.Secure.getInt(ctx.contentResolver, key, 0)
            "global" -> Settings.Global.getInt(ctx.contentResolver, key, 0)
            else -> 0
        } != 0
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun tileAccent(prefs: SharedPreferences, tileId: String, active: Boolean): Color? {
    if (!active || !prefs.getBoolean(QsPrefs.COLORED_TILES, false)) return null
    val key = when (tileId) {
        "wifi", "data", "bluetooth", "volume", "location", "aod", "flashlight", "rotate" ->
            QsPrefs.COLOR_CONNECTED
        "dnd", "airplane" -> QsPrefs.COLOR_IDLE
        else -> return null
    }
    if (prefs.getBoolean(QsPrefs.USE_SYSTEM_COLOR, false)) {
        return when (key) {
            QsPrefs.COLOR_CONNECTED -> MaterialTheme.colorScheme.primary
            QsPrefs.COLOR_IDLE -> MaterialTheme.colorScheme.tertiary
            else -> null
        }
    }
    val default = when (key) {
        QsPrefs.COLOR_CONNECTED -> QsPrefs.DEFAULT_COLOR_CONNECTED
        QsPrefs.COLOR_IDLE -> QsPrefs.DEFAULT_COLOR_IDLE
        else -> return null
    }
    return try {
        Color(android.graphics.Color.parseColor(prefs.getString(key, default) ?: default))
    } catch (_: Exception) {
        null
    }
}
