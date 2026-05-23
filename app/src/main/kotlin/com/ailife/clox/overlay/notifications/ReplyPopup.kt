package com.ailife.clox.overlay.notifications

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.ailife.clox.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.LinearLayout

object ReplyPopup {

    fun show(context: Context, sbn: StatusBarNotification) {
        val replyAction = sbn.notification.actions?.firstOrNull {
            it.remoteInputs?.isNotEmpty() == true
        } ?: return

        val remoteInput = replyAction.remoteInputs.first()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val inputLayout = TextInputLayout(context).apply {
            hint = remoteInput.label?.toString() ?: "Reply"
        }
        val editText = TextInputEditText(context)
        inputLayout.addView(editText)
        layout.addView(inputLayout)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_reply_title)
            .setView(layout)
            .setPositiveButton(R.string.btn_send) { _, _ ->
                val text = editText.text?.toString() ?: return@setPositiveButton
                val intent = Intent().apply {
                    val bundle = Bundle()
                    bundle.putCharSequence(remoteInput.resultKey, text)
                    RemoteInput.addResultsToIntent(replyAction.remoteInputs, this, bundle)
                }
                try { replyAction.actionIntent.send(context, 0, intent) } catch (e: Exception) {}
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
