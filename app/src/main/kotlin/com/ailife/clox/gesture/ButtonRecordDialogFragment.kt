package com.ailife.clox.gesture

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class ButtonRecordDialogFragment : DialogFragment() {

    var onKeyRecorded: ((Int) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Record Button")
            .setMessage("Press any hardware button (Volume Up/Down, Camera, etc.) to map it.\n\nBack button cancels.")
            .setNegativeButton("Cancel", null)
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
