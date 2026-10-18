package com.yzmm.clox.applist

import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView

class SpringEdgeEffectFactory(private val stiffness: Int = 5) : RecyclerView.EdgeEffectFactory() {

    // stiffness 0-10, default 5. strength 0.0–2.0, normalized to 1.0 at default.
    private val strength get() = stiffness.coerceIn(0, 10) / 5f

    override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
        return object : EdgeEffect(view.context) {
            private val vertical = direction == DIRECTION_TOP || direction == DIRECTION_BOTTOM
            private val sign = if (direction == DIRECTION_BOTTOM || direction == DIRECTION_RIGHT) -1f else 1f

            override fun onPull(deltaDistance: Float) { nudge(deltaDistance) }
            override fun onPull(deltaDistance: Float, displacement: Float) { nudge(deltaDistance) }

            private fun nudge(delta: Float) {
                if (strength == 0f) return
                val size = if (vertical) view.height else view.width
                val shift = sign * size * delta * BASE_PULL_MAGNITUDE * strength
                if (vertical) view.translationY += shift else view.translationX += shift
            }

            override fun onRelease() {
                if (vertical) {
                    if (view.translationY != 0f)
                        view.animate().translationY(0f).setInterpolator(OvershootInterpolator(2f)).setDuration(350).start()
                } else {
                    if (view.translationX != 0f)
                        view.animate().translationX(0f).setInterpolator(OvershootInterpolator(2f)).setDuration(350).start()
                }
            }

            override fun onAbsorb(velocity: Int) {
                if (strength == 0f) return
                val density = view.resources.displayMetrics.density
                val maxKick = BASE_ABSORB_MAX_DP * strength * density
                val kick = (sign * velocity * ABSORB_VELOCITY_FACTOR).coerceIn(-maxKick, maxKick)
                if (vertical) {
                    view.animate().translationY(kick).setDuration(80).setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            view.animate().translationY(0f).setInterpolator(OvershootInterpolator(2f)).setDuration(350).start()
                        }.start()
                } else {
                    view.animate().translationX(kick).setDuration(80).setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            view.animate().translationX(0f).setInterpolator(OvershootInterpolator(2f)).setDuration(350).start()
                        }.start()
                }
            }
        }
    }

    companion object {
        private const val BASE_PULL_MAGNITUDE = 0.18f
        private const val BASE_ABSORB_MAX_DP = 40f
        private const val ABSORB_VELOCITY_FACTOR = 0.008f
    }
}
