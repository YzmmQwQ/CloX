package com.yzmm.clox.ui.theme

import android.content.Context
import android.content.res.Configuration

/**
 * Applies the user's round/square override to an Activity's base context so
 * View code, resource qualifiers and [DeviceInfoHelper] see the chosen shape.
 * Compose screens additionally wrap the value through [ProvideScreenShape].
 */
object ScreenShapeConfig {
    fun wrap(base: Context): Context {
        val shape = ScreenShape.read(base)
        if (shape == ScreenShape.AUTO) return base
        val round = shape == ScreenShape.ROUND
        val config = Configuration(base.resources.configuration)
        config.screenLayout =
            (config.screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK.inv()) or
                (if (round) Configuration.SCREENLAYOUT_ROUND_YES
                else Configuration.SCREENLAYOUT_ROUND_NO)
        return base.createConfigurationContext(config)
    }
}
