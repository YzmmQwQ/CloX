package com.yzmm.clox.applist

/**
 * Configuration for the 3D depth effect: controls how items scale based on
 * their distance from the viewport center.
 *
 * Curves available (larger values = more aggressive edge shrinkage):
 *  - [CURVE_LINEAR]:      Original linear falloff.
 *  - [CURVE_SMOOTHSTEP]:  Smoothstep (r²(3-2r)) — center stays larger longer.
 *  - [CURVE_EXPONENTIAL]: Cubic (r³) — even more center emphasis.
 */
data class Effect3DConfig(
    val curve: String = CURVE_LINEAR,
    val minScalePercent: Int = 50
) {
    companion object {
        const val CURVE_LINEAR      = "linear"
        const val CURVE_SMOOTHSTEP  = "smoothstep"
        const val CURVE_EXPONENTIAL = "exponential"
    }

    /** Maps a raw normalised distance [ratio] (0 = dead centre, 1 = farthest edge)
     *  to an adjusted value that is then used to compute final scale. */
    private fun adjustRatio(ratio: Float): Float {
        val r = ratio.coerceIn(0f, 1f)
        return when (curve) {
            CURVE_SMOOTHSTEP  -> r * r * (3f - 2f * r)
            CURVE_EXPONENTIAL -> r * r * r
            else              -> r   // linear
        }
    }

    /** Returns the final scale for an item at the given normalised [ratio]. */
    fun scaleFor(ratio: Float): Float {
        val minScale = minScalePercent / 100f
        return 1f - adjustRatio(ratio) * (1f - minScale)
    }
}
