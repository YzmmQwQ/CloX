package com.ailife.clox.overlay.quicksettings

import android.content.Context
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.qs.QsPrefs
import com.ailife.clox.qs.QsStateManager
import com.ailife.clox.qs.TileState
import com.ailife.clox.qs.tile.CustomTileMode
import com.ailife.clox.qs.tile.TileLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class QsTilePageAdapter(
    private val pages: List<List<String>>,
    private val columns: Int,       // always 2
    private val rows: Int,          // 2 or 3
    private val tileHeightPx: Int,  // fixed tile height in px
    private val stateManager: QsStateManager,
    private val scope: CoroutineScope,
    private val onTileClick: (tileId: String, tile: QsTileView) -> Unit,
    private val onTileLongClick: (tileId: String) -> Unit
) : RecyclerView.Adapter<QsTilePageAdapter.PageViewHolder>() {

    inner class PageViewHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container)

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val container = holder.container
        container.removeAllViews()
        val ctx = container.context
        val tileIds = pages[position]
        val margin = (5 * ctx.resources.displayMetrics.density).toInt()

        for (rowIdx in 0 until rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, tileHeightPx
                ).apply { setMargins(0, if (rowIdx > 0) margin else 0, 0, 0) }
            }

            for (colIdx in 0 until columns) {
                val slotIdx = rowIdx * columns + colIdx
                val tileId = tileIds.getOrNull(slotIdx)
                val descriptor = if (tileId != null) TileLayoutManager.descriptorFor(tileId, ctx) else null

                if (tileId != null && descriptor != null) {
                    val tile = QsTileView(ctx).apply {
                        configure(descriptor.iconRes, descriptor.defaultLabel)
                        onAction = { onTileClick(tileId, this) }
                        onLongAction = { onTileLongClick(tileId) }
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                            .apply { setMargins(margin, 0, if (colIdx < columns - 1) margin else 0, 0) }
                    }
                    row.addView(tile)

                    // For custom WRITE_SETTINGS tiles: read current state and apply it immediately
                    if (tileId.startsWith("custom:")) {
                        applyCustomTileState(tile, tileId, ctx)
                    }

                    stateManager.stateFor(tileId)?.onEach { state ->
                        tile.post { bindState(tile, tileId, state, ctx) }
                    }?.launchIn(scope)
                } else {
                    // Invisible placeholder keeps column spacing consistent
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                            .apply { setMargins(margin, 0, if (colIdx < columns - 1) margin else 0, 0) }
                    })
                }
            }
            container.addView(row)
        }
    }

    private fun bindState(tile: QsTileView, tileId: String, state: TileState, ctx: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val colored = prefs.getBoolean(QsPrefs.COLORED_TILES, true)

        val useSystem = prefs.getBoolean(QsPrefs.USE_SYSTEM_COLOR, false)

        fun stateColor(key: String, default: String): Int? {
            if (!colored) return null
            if (useSystem) return resolveSystemColor(ctx, key)
            return try { Color.parseColor(prefs.getString(key, default) ?: default) }
            catch (_: Exception) { null }
        }

        when (state) {
            is TileState.WifiState -> {
                tile.setIconRes(wifiSignalDrawable(state.signalLevel))
                val detail = when {
                    state.connected && state.ssid != null -> state.ssid
                    state.connected -> "Connected"
                    else -> null
                }
                val badge = if (state.connected && state.frequency != null) state.frequency else null
                val color = when {
                    state.connected -> stateColor(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED)
                    state.enabled   -> stateColor(QsPrefs.COLOR_IDLE, QsPrefs.DEFAULT_COLOR_IDLE)
                    else            -> null
                }
                tile.applyState(state.enabled, detail, badge, color)
            }

            is TileState.DataState -> {
                tile.setIconRes(cellularSignalDrawable(if (state.activelyRouting) state.signalLevel else 0))
                val detail = when {
                    state.activelyRouting -> state.operatorName
                    state.enabled         -> "Not in use"
                    else                  -> null
                }
                val badge = if (state.activelyRouting && state.networkType != null) state.networkType else null
                val color = when {
                    state.activelyRouting -> stateColor(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED)
                    state.enabled         -> stateColor(QsPrefs.COLOR_IDLE, QsPrefs.DEFAULT_COLOR_IDLE)
                    else                  -> null
                }
                tile.applyState(state.enabled, detail, badge, color)
            }

            is TileState.BtState -> {
                val detail = when {
                    !state.enabled                    -> null
                    state.connectedDeviceName != null -> state.connectedDeviceName
                    else                              -> "on"
                }
                val color = when {
                    state.connectedDeviceName != null ->
                        stateColor(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED)
                    state.enabled ->
                        stateColor(QsPrefs.COLOR_IDLE, QsPrefs.DEFAULT_COLOR_IDLE)
                    else -> null
                }
                tile.applyState(state.enabled, detail, null, color)
            }

            is TileState.VolumeState -> {
                val pct = if (state.max > 0) "${(state.level * 100f / state.max).toInt()}%" else "--"
                val detail = when (state.ringerMode) {
                    android.media.AudioManager.RINGER_MODE_SILENT  -> "Silent"
                    android.media.AudioManager.RINGER_MODE_VIBRATE -> "Vibrate  $pct"
                    else -> pct
                }
                val active = state.ringerMode != android.media.AudioManager.RINGER_MODE_SILENT && state.level > 0
                tile.applyState(active, detail, null,
                    if (active) stateColor(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED) else null)
            }

            is TileState.DndState -> {
                val detail = when (state.ringerMode) {
                    android.media.AudioManager.RINGER_MODE_SILENT  -> "Silent"
                    android.media.AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                    else -> null
                }
                tile.applyState(state.active, detail, null,
                    if (state.active) stateColor(QsPrefs.COLOR_IDLE, QsPrefs.DEFAULT_COLOR_IDLE) else null)
            }

            is TileState.RotateState ->
                tile.applyState(!state.locked, if (!state.locked) "Auto" else "Locked", null, null)

            is TileState.FlashlightState ->
                tile.applyState(state.on, null, null, null)

            is TileState.LocationState ->
                tile.applyState(state.enabled, null, null,
                    if (state.enabled) stateColor(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED) else null)

            is TileState.AodState ->
                tile.applyState(state.enabled, if (state.enabled) "On" else null, null, null)

            is TileState.AirplaneState ->
                tile.applyState(state.enabled, if (state.enabled) "On" else "Off", null,
                    if (state.enabled) stateColor(QsPrefs.COLOR_IDLE, QsPrefs.DEFAULT_COLOR_IDLE) else null)

            is TileState.HotspotState ->
                tile.applyState(state.enabled, if (state.enabled) "On" else "Off", null,
                    if (state.enabled) stateColor(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED) else null)

            is TileState.BatteryState -> { /* no battery tile */ }
        }
    }

    /**
     * Resolve a tile state color from the Material3 theme (wallpaper-derived on Android 12+).
     * Connected → colorPrimary, Idle → colorSecondary, Disconnected → colorError, Off → null
     */
    private fun resolveSystemColor(ctx: Context, key: String): Int? {
        val attr = when (key) {
            QsPrefs.COLOR_CONNECTED    -> com.google.android.material.R.attr.colorPrimary
            QsPrefs.COLOR_IDLE         -> com.google.android.material.R.attr.colorSecondary
            QsPrefs.COLOR_DISCONNECTED -> com.google.android.material.R.attr.colorError
            else                       -> return null  // off state: no color (dim ring)
        }
        val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, android.graphics.Color.WHITE)
        ta.recycle()
        return color
    }

    /** Read current value for a WRITE_SETTINGS custom tile and apply state to the tile view. */
    fun applyCustomTileState(tile: QsTileView, tileId: String, ctx: Context) {
        val uuid = tileId.removePrefix("custom:")
        val config = TileLayoutManager.loadCustomTiles(ctx).firstOrNull { it.uuid == uuid }
            ?: return
        if (config.mode != CustomTileMode.WRITE_SETTINGS) return
        val ns = config.settingsNamespace ?: return
        val key = config.settingsKey ?: return
        val active = try {
            val value = when (ns) {
                "system" -> Settings.System.getInt(ctx.contentResolver, key, 0)
                "secure" -> Settings.Secure.getInt(ctx.contentResolver, key, 0)
                "global" -> Settings.Global.getInt(ctx.contentResolver, key, 0)
                else -> 0
            }
            value != 0
        } catch (_: Exception) { false }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val colored = prefs.getBoolean(QsPrefs.COLORED_TILES, true)
        val color = if (colored && active) try {
            Color.parseColor(prefs.getString(QsPrefs.COLOR_CONNECTED, QsPrefs.DEFAULT_COLOR_CONNECTED))
        } catch (_: Exception) { null } else null

        tile.applyState(active, if (active) "On" else "Off", null, color)
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
}
