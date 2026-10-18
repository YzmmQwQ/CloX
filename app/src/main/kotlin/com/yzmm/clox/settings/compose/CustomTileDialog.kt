package com.yzmm.clox.settings.compose

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import com.yzmm.clox.qs.tile.CustomTileConfig
import com.yzmm.clox.qs.tile.CustomTileMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Compose replacement for CustomTileDialogFragment: label + launch-app or
 * settings-key tile configuration, with app and setting-key pickers.
 */
@Composable
fun CustomTileDialog(
    existing: CustomTileConfig? = null,
    onSave: (CustomTileConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current

    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var mode by remember { mutableStateOf(existing?.mode ?: CustomTileMode.LAUNCH_ACTIVITY) }
    var component by remember { mutableStateOf(existing?.activityComponent.orEmpty()) }
    var namespace by remember { mutableStateOf(existing?.settingsNamespace ?: "system") }
    var settingsKey by remember { mutableStateOf(existing?.settingsKey.orEmpty()) }

    var error by remember { mutableStateOf<String?>(null) }

    var showAppPicker by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var showKeyPicker by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf<Array<String>?>(null) }

    LaunchedEffect(showAppPicker) {
        if (showAppPicker && apps == null) apps = loadLaunchableApps(ctx)
    }
    LaunchedEffect(showKeyPicker, namespace) {
        if (showKeyPicker) {
            keys = withContext(Dispatchers.IO) { loadSettingKeys(ctx, namespace) }
            if (keys?.isEmpty() == true) {
                showKeyPicker = false
                Toast.makeText(ctx, R.string.custom_tile_no_settings, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    MaterialTheme.shapes.large,
                )
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(
                text = stringResource(
                    if (existing != null) R.string.custom_tile_edit_title
                    else R.string.custom_tile_dialog_title),
                style = MaterialTheme.typography.titleMedium,
            )

            DialogField(
                value = label,
                onValueChange = { label = it },
                hint = stringResource(R.string.custom_tile_label_hint),
            )

            Text(
                text = stringResource(R.string.custom_tile_mode_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            RadioButton(
                selected = mode == CustomTileMode.LAUNCH_ACTIVITY,
                onSelect = { mode = CustomTileMode.LAUNCH_ACTIVITY },
            ) { Text(stringResource(R.string.custom_tile_launch_app)) }
            RadioButton(
                selected = mode == CustomTileMode.WRITE_SETTINGS,
                onSelect = { mode = CustomTileMode.WRITE_SETTINGS },
            ) { Text(stringResource(R.string.custom_tile_write_setting)) }

            if (mode == CustomTileMode.LAUNCH_ACTIVITY) {
                Button(
                    onClick = { showAppPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.custom_tile_choose_app)) },
                )
                DialogField(
                    value = component,
                    onValueChange = { component = it },
                    hint = stringResource(R.string.custom_tile_component_label),
                )
            } else {
                Text(
                    text = stringResource(R.string.custom_tile_namespace_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                RadioButton(
                    selected = namespace == "system",
                    onSelect = { namespace = "system" },
                ) { Text(stringResource(R.string.custom_tile_ns_system)) }
                RadioButton(
                    selected = namespace == "secure",
                    onSelect = { namespace = "secure" },
                ) { Text(stringResource(R.string.custom_tile_ns_secure)) }
                RadioButton(
                    selected = namespace == "global",
                    onSelect = { namespace = "global" },
                ) { Text(stringResource(R.string.custom_tile_ns_global)) }
                Button(
                    onClick = { showKeyPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.custom_tile_pick_setting)) },
                )
                DialogField(
                    value = settingsKey,
                    onValueChange = { settingsKey = it },
                    hint = stringResource(R.string.custom_tile_settings_key_label),
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val tileLabel = label.trim().ifEmpty {
                        ctx.getString(R.string.custom_tile_default_label)
                    }
                    val uuid = existing?.uuid ?: UUID.randomUUID().toString()
                    if (mode == CustomTileMode.LAUNCH_ACTIVITY) {
                        val comp = component.trim()
                        if (comp.isEmpty() || ComponentName.unflattenFromString(comp) == null) {
                            error = ctx.getString(R.string.custom_tile_component_label)
                            return@Button
                        }
                        onSave(CustomTileConfig(
                            uuid = uuid,
                            label = tileLabel,
                            mode = CustomTileMode.LAUNCH_ACTIVITY,
                            activityComponent = comp,
                        ))
                        onDismiss()
                    } else {
                        val key = settingsKey.trim()
                        if (key.isEmpty()) {
                            error = ctx.getString(R.string.custom_tile_settings_key_label)
                            return@Button
                        }
                        onSave(CustomTileConfig(
                            uuid = uuid,
                            label = tileLabel,
                            mode = CustomTileMode.WRITE_SETTINGS,
                            settingsNamespace = namespace,
                            settingsKey = key,
                        ))
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(
                        if (existing != null) R.string.custom_tile_save_btn
                        else R.string.custom_tile_add_btn))
                },
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.btn_cancel)) },
            )
        }
    }

    if (showAppPicker) {
        AppChoiceDialog(
            title = stringResource(R.string.custom_tile_choose_app_title),
            apps = apps,
            onSelect = { app ->
                showAppPicker = false
                component = app.component
                if (label.isBlank()) label = app.label
            },
            onDismiss = { showAppPicker = false },
        )
    }

    val keyList = keys
    if (showKeyPicker && keyList != null) {
        SettingsPickerDialog(
            title = stringResource(R.string.custom_tile_pick_setting_title),
            labels = keyList,
            selectedIndex = 0,
            onSelect = { index ->
                showKeyPicker = false
                settingsKey = keyList[index]
            },
            onDismiss = { showKeyPicker = false },
        )
    }
}

@Composable
private fun RadioButton(
    selected: Boolean,
    onSelect: () -> Unit,
    content: @Composable () -> Unit,
) {
    androidx.wear.compose.material3.RadioButton(
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth(),
        label = { content() },
    )
}

@Composable
private fun DialogField(value: String, onValueChange: (String) -> Unit, hint: String) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            inner()
        },
    )
}

private fun loadSettingKeys(ctx: Context, namespace: String): Array<String> {
    val uri: Uri = when (namespace) {
        "secure" -> Settings.Secure.CONTENT_URI
        "global" -> Settings.Global.CONTENT_URI
        else -> Settings.System.CONTENT_URI
    }
    val out = sortedSetOf<String>()
    runCatching {
        ctx.contentResolver.query(uri, arrayOf("name"), null, null, "name")?.use { c ->
            val col = c.getColumnIndex("name")
            if (col >= 0) while (c.moveToNext()) c.getString(col)?.let { out.add(it) }
        }
    }
    return out.toTypedArray()
}
