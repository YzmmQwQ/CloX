package com.ailife.clox.overlay.widgets.picker

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.ailife.clox.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AddCardDialog : DialogFragment() {

    var onFullWidthWidget: (() -> Unit)? = null
    var onSingleCellRow: (() -> Unit)? = null
    var onBuiltInClock: (() -> Unit)? = null
    var onBuiltInWeather: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = arrayOf(
            getString(R.string.widget_add_full_width),
            getString(R.string.widget_add_three_cell),
            getString(R.string.widget_add_clock),
            getString(R.string.widget_add_weather)
        )
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.widget_add_card)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> onFullWidthWidget?.invoke()
                    1 -> onSingleCellRow?.invoke()
                    2 -> onBuiltInClock?.invoke()
                    3 -> onBuiltInWeather?.invoke()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "add_card"
    }
}
