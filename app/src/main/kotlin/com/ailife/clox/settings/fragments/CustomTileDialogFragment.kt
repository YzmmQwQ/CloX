package com.ailife.clox.settings.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import androidx.fragment.app.DialogFragment
import com.ailife.clox.qs.tile.CustomTileConfig
import com.ailife.clox.qs.tile.CustomTileMode
import java.util.UUID

class CustomTileDialogFragment(
    private val onSave: (CustomTileConfig) -> Unit
) : DialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        buildDialogView()

    private fun buildDialogView(): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        val labelEdit = EditText(ctx).apply { hint = "Tile label" }
        val modeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val radioActivity = RadioButton(ctx).apply { text = "Launch App"; id = 1 }
        val radioSettings = RadioButton(ctx).apply { text = "Write Setting"; id = 2 }
        modeGroup.addView(radioActivity)
        modeGroup.addView(radioSettings)
        modeGroup.check(1)

        val activityInput = EditText(ctx).apply { hint = "com.pkg/.Activity" }
        val namespaceSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item,
                listOf("system", "secure", "global")).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        val keyInput = EditText(ctx).apply { hint = "settings_key" }
        val settingsGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(namespaceSpinner)
            addView(keyInput)
        }

        modeGroup.setOnCheckedChangeListener { _, id ->
            activityInput.visibility = if (id == 1) View.VISIBLE else View.GONE
            settingsGroup.visibility = if (id == 2) View.VISIBLE else View.GONE
        }

        val saveBtn = Button(ctx).apply { text = "Add Tile" }
        saveBtn.setOnClickListener {
            val label = labelEdit.text.toString().trim().ifEmpty { "Custom" }
            val mode = if (modeGroup.checkedRadioButtonId == 1) CustomTileMode.LAUNCH_ACTIVITY else CustomTileMode.WRITE_SETTINGS
            val config = CustomTileConfig(
                uuid = UUID.randomUUID().toString(),
                label = label,
                mode = mode,
                activityComponent = if (mode == CustomTileMode.LAUNCH_ACTIVITY)
                    activityInput.text.toString().trim().ifEmpty { null } else null,
                settingsNamespace = if (mode == CustomTileMode.WRITE_SETTINGS)
                    namespaceSpinner.selectedItem.toString() else null,
                settingsKey = if (mode == CustomTileMode.WRITE_SETTINGS)
                    keyInput.text.toString().trim().ifEmpty { null } else null
            )
            onSave(config)
            dismiss()
        }

        root.addView(labelEdit)
        root.addView(modeGroup)
        root.addView(activityInput)
        root.addView(settingsGroup)
        root.addView(saveBtn)
        return root
    }
}
