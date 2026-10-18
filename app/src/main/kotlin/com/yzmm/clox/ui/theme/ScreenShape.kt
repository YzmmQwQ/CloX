package com.yzmm.clox.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.yzmm.clox.R

/**
 * Screen shape, adapted from MaterialNCM: the launcher normally reads the
 * system config, but some non-Wear watches report it incorrectly (or are
 * square and don't want Wear's round-edge scaling), so the user can override.
 */
enum class ScreenShape(val labelRes: Int, val prefValue: String) {
    AUTO(R.string.screen_shape_auto, "auto"),
    ROUND(R.string.screen_shape_round, "round"),
    SQUARE(R.string.screen_shape_square, "square");

    companion object {
        const val KEY = "screen_shape"
        fun fromPref(value: String?): ScreenShape =
            entries.firstOrNull { it.prefValue == value } ?: AUTO
        fun read(context: android.content.Context): ScreenShape =
            fromPref(PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY, AUTO.prefValue))

        fun write(context: android.content.Context, shape: ScreenShape) {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit { putString(KEY, shape.prefValue) }
        }
    }
}

private val LocalScreenRound = staticCompositionLocalOf { true }

/** Whether the current screen is round, after the user override. */
@Composable
fun isRoundScreen(): Boolean = LocalScreenRound.current

@Composable
fun byShape(round: Dp, square: Dp): Dp = if (isRoundScreen()) round else square

/**
 * Applies the chosen shape. Overriding [LocalConfiguration]'s round flag makes
 * Wear M3 / Horologist components (TimeText, list padding, scroll indicator)
 * lay out for the right shape as well.
 */
@Composable
fun ProvideScreenShape(shape: ScreenShape, content: @Composable () -> Unit) {
    val base = LocalConfiguration.current
    val round = when (shape) {
        ScreenShape.AUTO -> base.isScreenRound
        ScreenShape.ROUND -> true
        ScreenShape.SQUARE -> false
    }
    val config = remember(base, round) {
        if (base.isScreenRound == round) base
        else Configuration(base).apply {
            screenLayout =
                (screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK.inv()) or
                    (if (round) Configuration.SCREENLAYOUT_ROUND_YES
                    else Configuration.SCREENLAYOUT_ROUND_NO)
        }
    }
    CompositionLocalProvider(
        LocalConfiguration provides config,
        LocalScreenRound provides round,
        content = content,
    )
}

/** Square displays aren't clipped at the corners, so content can sit closer to the edge. */
val SquareEdgePadding: Dp = 6.dp
