package com.yzmm.clox.settings.compose

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R

/**
 * Compose replacement for ButtonRecordDialogFragment: captures the next
 * hardware key press and returns its key code.
 */
@Composable
fun ButtonRecordDialog(onKeyRecorded: (Int) -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    MaterialTheme.shapes.large,
                )
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
                    when (val keyCode = event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            onDismiss()
                            true
                        }
                        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER -> false
                        else -> {
                            onKeyRecorded(keyCode)
                            true
                        }
                    }
                }
                .focusable()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.dialog_btn_record_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.dialog_btn_record_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = stringResource(R.string.btn_cancel)) },
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
