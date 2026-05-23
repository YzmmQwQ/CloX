package com.ailife.clox.settings.fragments

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

abstract class BaseSettingsFragment : Fragment() {

    protected val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    protected fun namedPrefs(name: String): SharedPreferences =
        requireContext().getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        )
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }
    }

    protected fun showListPicker(
        title: String,
        entries: Array<String>,
        values: Array<String>,
        currentValue: String,
        onSelect: (value: String, label: String) -> Unit
    ) {
        val selected = values.indexOf(currentValue).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(entries, selected) { dialog, which ->
                onSelect(values[which], entries[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    protected fun showTextInputDialog(
        title: String,
        currentValue: String,
        hint: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        onConfirm: (String) -> Unit
    ) {
        val layout = TextInputLayout(requireContext(), null,
            com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)
        layout.hint = hint
        layout.setPadding(48, 8, 48, 0)
        val edit = TextInputEditText(layout.context)
        edit.setText(currentValue)
        edit.inputType = inputType
        layout.addView(edit)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(edit.text?.toString()?.trim() ?: "")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    protected fun showColorDialog(
        title: String,
        currentValue: String,
        onConfirm: (String) -> Unit
    ) {
        showTextInputDialog(title, currentValue, hint = "#RRGGBB",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ) { raw ->
            if (raw.matches(Regex("#[0-9A-Fa-f]{6}"))) onConfirm(raw)
        }
    }
}
