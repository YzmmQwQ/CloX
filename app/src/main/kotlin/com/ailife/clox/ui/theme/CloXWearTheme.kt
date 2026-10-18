package com.ailife.clox.ui.theme

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot

/** CloX brand blue (brand_500) — seeds the fallback Wear M3 palette. */
private const val CLOX_SEED = 0xFF5A8BC5.toInt()

private const val NEUTRAL_CHROMA = 1.5
private const val NEUTRAL_VARIANT_CHROMA = 3.0

/**
 * Wear M3 colour scheme from a seed, using the same mapping as MaterialNCM /
 * Wear OS: primary is tone 90, containers tone 30, buttons neutral tone 15-30,
 * background pure black. Tonal Spot keeps the chromatic accent restrained.
 */
fun wearColorScheme(seed: Int): ColorScheme {
    val s: DynamicScheme = SchemeTonalSpot(
        Hct.fromInt(seed), true, 0.0,
        ColorSpec.SpecVersion.SPEC_2021, DynamicScheme.Platform.PHONE,
    )
    fun TonalPalette.c(tone: Int) = Color(tone(tone))
    val p = s.primaryPalette
    val sec = s.secondaryPalette
    val t = s.tertiaryPalette
    // Near-neutral button/background palettes so containers stay grey and only
    // the accent elements follow the seed hue.
    val hue = Hct.fromInt(seed).hue
    val n = TonalPalette.fromHueAndChroma(hue, NEUTRAL_CHROMA)
    val nv = TonalPalette.fromHueAndChroma(hue, NEUTRAL_VARIANT_CHROMA)
    val e = s.errorPalette
    return ColorScheme(
        primary = p.c(90),
        primaryDim = p.c(80),
        primaryContainer = p.c(30),
        onPrimary = p.c(10),
        onPrimaryContainer = p.c(95),
        secondary = sec.c(90),
        secondaryDim = sec.c(80),
        secondaryContainer = sec.c(30),
        onSecondary = sec.c(10),
        onSecondaryContainer = sec.c(95),
        tertiary = t.c(90),
        tertiaryDim = t.c(80),
        tertiaryContainer = t.c(30),
        onTertiary = t.c(10),
        onTertiaryContainer = t.c(95),
        surfaceContainerLow = n.c(15),
        surfaceContainer = n.c(20),
        surfaceContainerHigh = n.c(30),
        onSurface = n.c(95),
        onSurfaceVariant = nv.c(80),
        outline = nv.c(60),
        outlineVariant = nv.c(40),
        background = n.c(0),
        onBackground = n.c(100),
        error = e.c(80),
        onError = e.c(10),
        errorContainer = e.c(30),
        onErrorContainer = e.c(95),
    )
}

/**
 * CloX Wear Material 3 theme. Uses system dynamic colour when the platform
 * supports it (Wear OS 6 / API 31+), otherwise the brand-seeded scheme.
 * Also applies the user's round/square screen override.
 */
@Composable
fun CloXWearTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        PreferenceManager.getDefaultSharedPreferences(context)
    }
    var shape by remember(context) { mutableStateOf(ScreenShape.read(context)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ScreenShape.KEY) shape = ScreenShape.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val scheme = remember(context) {
        dynamicColorScheme(context) ?: wearColorScheme(CLOX_SEED)
    }
    ProvideScreenShape(shape) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
