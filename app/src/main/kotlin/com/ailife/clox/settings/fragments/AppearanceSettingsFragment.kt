package com.ailife.clox.settings.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.ailife.clox.settings.compose.AppearanceScreen
import com.ailife.clox.ui.theme.CloXWearTheme

/** Hosts the Wear Material 3 Appearance screen (first migrated Compose page). */
class AppearanceSettingsFragment : BaseSettingsFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            CloXWearTheme {
                AppearanceScreen()
            }
        }
    }
}
