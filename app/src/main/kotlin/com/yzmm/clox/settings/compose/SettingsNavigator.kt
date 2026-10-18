package com.yzmm.clox.settings.compose

import androidx.compose.runtime.staticCompositionLocalOf

/** Navigate within the settings graph from any screen (string routes). */
val LocalSettingsNavigator = staticCompositionLocalOf<(String) -> Unit> { {} }
