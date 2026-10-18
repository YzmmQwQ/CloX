package com.yzmm.clox.settings.compose

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import com.yzmm.clox.icon.renderToBitmap
import com.yzmm.clox.tapaction.TapActionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TapAppRow(val ri: ResolveInfo, val label: String, val icon: Bitmap)

@Composable
fun TapActionsScreen() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun currentSummary(key: String) =
        TapActionHandler.getMappedAppLabel(ctx, key)
            ?: ctx.getString(R.string.pref_tap_not_configured)

    var summaries by remember {
        mutableStateOf(
            TapActionHandler.CONFIGURABLE_ACTIONS.associate { it.key to currentSummary(it.key) }
        )
    }
    var pickerFor by remember { mutableStateOf<TapActionHandler.ActionDef?>(null) }
    var apps by remember { mutableStateOf<List<TapAppRow>?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                summaries = TapActionHandler.CONFIGURABLE_ACTIONS
                    .associate { it.key to currentSummary(it.key) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pickerFor) {
        if (pickerFor == null || apps != null) return@LaunchedEffect
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val iconSizePx = (40 * ctx.resources.displayMetrics.density + 0.5f).toInt()
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).sortedBy { it.loadLabel(pm).toString().lowercase() }
                .map { ri ->
                    TapAppRow(
                        ri = ri,
                        label = ri.loadLabel(pm).toString(),
                        icon = ri.loadIcon(pm).renderToBitmap(iconSizePx),
                    )
                }
        }
    }

    WearSettingsScreen(title = stringResource(R.string.pref_tap_title)) {
        SectionHeader(res(R.string.pref_tap_category_builtin))
        SettingsNote(res(R.string.pref_tap_builtin_desc))
        for (def in TapActionHandler.CONFIGURABLE_ACTIONS) {
            SettingsButton(
                title = res(def.displayNameRes),
                summary = summaries[def.key],
            ) { pickerFor = def }
        }
    }

    pickerFor?.let { def ->
        AlertDialog(
            visible = true,
            onDismissRequest = { pickerFor = null },
            title = { Text(text = stringResource(def.displayNameRes)) },
            transformationSpec = rememberDialogTransformationSpec(),
        ) {
            item {
                Button(
                    onClick = {
                        TapActionHandler.setComponent(ctx, def.key, "")
                        summaries = summaries + (def.key to
                                ctx.getString(R.string.pref_tap_not_configured))
                        pickerFor = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.pref_tap_clear)) },
                )
            }
            apps?.forEach { app ->
                item {
                    Button(
                        onClick = {
                            val info = app.ri.activityInfo
                            TapActionHandler.setComponent(
                                ctx, def.key, "${info.packageName}/${info.name}")
                            summaries = summaries + (def.key to app.label)
                            pickerFor = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = {
                            Image(
                                bitmap = app.icon.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        label = { Text(text = app.label) },
                    )
                }
            }
        }
    }
}
