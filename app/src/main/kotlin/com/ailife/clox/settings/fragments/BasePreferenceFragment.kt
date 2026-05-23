package com.ailife.clox.settings.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.color.MaterialColors

abstract class BasePreferenceFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // PreferenceFragmentCompat inflates a transparent LinearLayout — give it an opaque
        // surface background so it doesn't flicker during enter/exit animations.
        view.setBackgroundColor(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        )
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }
    }
}
