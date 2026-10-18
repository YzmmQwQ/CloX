package com.yzmm.clox.gesture

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.yzmm.clox.R

class ButtonRecordDialogFragment : DialogFragment() {

    var onKeyRecorded: ((Int) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_btn_record_title)
            .setMessage(R.string.dialog_btn_record_message)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    dismiss()
                    true
                }
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER -> false  // not interceptable
                else -> {
                    onKeyRecorded?.invoke(keyCode)
                    dismiss()
                    true
                }
            }
        }
        return dialog
    }

    companion object {
        const val TAG = "ButtonRecordDialog"
    }
}
