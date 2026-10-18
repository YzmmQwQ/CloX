package com.yzmm.clox.settings.compose

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import com.yzmm.clox.icon.renderToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableApp(val label: String, val component: String, val icon: Bitmap)

/** Loads launcher apps with labels + small icons (call from a coroutine/LaunchedEffect). */
suspend fun loadLaunchableApps(ctx: Context): List<LaunchableApp> =
    withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val iconSizePx = (40 * ctx.resources.displayMetrics.density + 0.5f).toInt()
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).sortedBy { it.loadLabel(pm).toString().lowercase() }
            .map { ri ->
                val info = ri.activityInfo
                LaunchableApp(
                    label = ri.loadLabel(pm).toString(),
                    component = "${info.packageName}/${info.name}",
                    icon = ri.loadIcon(pm).renderToBitmap(iconSizePx),
                )
            }
    }

@Composable
fun AppChoiceDialog(
    title: String,
    apps: List<LaunchableApp>?,
    onSelect: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        transformationSpec = rememberDialogTransformationSpec(),
    ) {
        if (onClear != null) {
            item {
                Button(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.pref_tap_clear)) },
                )
            }
        }
        if (apps == null) {
            item { CircularProgressIndicator() }
        } else {
            apps.forEach { app ->
                item {
                    Button(
                        onClick = { onSelect(app) },
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
