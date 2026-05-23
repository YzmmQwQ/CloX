package com.ailife.clox.tapaction

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import android.util.Log
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import com.ailife.clox.R

/**
 * Handles watchface tap actions sent from Lua via WatchfaceBridge.tapAction(shortcut).
 *
 * Two kinds of action:
 *  - Built-in: handled directly (volume, media keys, set_alarm_HH:MM). No configuration needed.
 *  - Configurable: maps a named shortcut to a user-chosen app component stored in SharedPreferences.
 *    Watchfaces reference the name (e.g. "widget_weather") instead of a hardcoded package so
 *    users can point each action at whichever app they prefer.
 */
object TapActionHandler {

    private const val TAG         = "TapActionHandler"
    private const val PREF_PREFIX = "tap_action_"

    data class ActionDef(val key: String, val displayNameRes: Int)

    /** Ordered list of user-configurable named actions. */
    val CONFIGURABLE_ACTIONS = listOf(
        ActionDef("tap_launcher",    R.string.tap_launcher_home),
        ActionDef("widget_weather",  R.string.tap_weather),
        ActionDef("widget_health",   R.string.tap_health),
        ActionDef("widget_calendar", R.string.tap_calendar_widget),
        ActionDef("w_agenda",        R.string.tap_agenda),
        ActionDef("w_fit",           R.string.tap_fitness),
        ActionDef("w_heart_rate",    R.string.tap_heart_rate),
        ActionDef("w_compass",       R.string.tap_compass),
        ActionDef("w_keep",          R.string.tap_notes),
        ActionDef("m_email",         R.string.tap_email),
        ActionDef("m_calendar",      R.string.tap_calendar_app),
        ActionDef("voice_search",    R.string.tap_voice_search),
        ActionDef("w_contacts",      R.string.tap_contacts),
        ActionDef("w_sports",        R.string.tap_sports),
        ActionDef("w_gallery",       R.string.tap_gallery_photos),
        ActionDef("w_messaging",     R.string.tap_messages),
        ActionDef("w_camera",        R.string.tap_camera),
        ActionDef("w_dialer",        R.string.tap_phone_dialer),
        ActionDef("w_music",         R.string.tap_music_player),
        ActionDef("w_clock",         R.string.tap_clock_alarm),
        ActionDef("w_calculator",    R.string.tap_calculator),
        ActionDef("w_payment",       R.string.tap_wallet_payment),
        ActionDef("w_map",           R.string.tap_maps_navigation),
        ActionDef("browser",         R.string.tap_browser),
        ActionDef("flashlight",      R.string.tap_flashlight),
        ActionDef("socialmedia",     R.string.tap_social_media),
    )

    /**
     * Main entry point. Returns true if the action was handled (even if no app is configured
     * for a known shortcut — prevents unknown-action fallthrough).
     */
    fun handle(ctx: Context, action: String): Boolean {
        if (action.isBlank()) return false
        return handleBuiltin(ctx, action) || handleConfigured(ctx, action)
    }

    // ── Built-in actions ──────────────────────────────────────────────────────

    private fun handleBuiltin(ctx: Context, action: String): Boolean = when {
        action == "vol_up" -> {
            audioManager(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            true
        }
        action == "vol_down" -> {
            audioManager(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            true
        }
        action == "media_play_pause" -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); true }
        action == "media_play"       -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);       true }
        action == "media_pause"      -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE);      true }
        action == "media_next"       -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);       true }
        action == "media_prev"       -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);   true }
        action.startsWith("set_alarm_") -> setAlarm(ctx, action.removePrefix("set_alarm_"))
        else -> false
    }

    private fun setAlarm(ctx: Context, timeStr: String): Boolean {
        val parts = timeStr.split(":")
        if (parts.size != 2) return false
        return try {
            ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR,    parts[0].toInt())
                putExtra(AlarmClock.EXTRA_MINUTES, parts[1].toInt())
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "set_alarm failed: $timeStr", e)
            false
        }
    }

    // ── Configurable actions ──────────────────────────────────────────────────

    private fun handleConfigured(ctx: Context, action: String): Boolean {
        val isKnown = CONFIGURABLE_ACTIONS.any { it.key == action }
        val component = getComponent(ctx, action)

        if (!isKnown) return false          // not a recognised action key
        if (component.isNullOrBlank()) {
            Log.d(TAG, "Action '$action' known but not configured — ignored")
            return true                     // known action, just nothing mapped — swallow it
        }

        val parts = component.split("/", limit = 2)
        if (parts.size < 2) {
            Log.e(TAG, "Bad component format for '$action': $component")
            return true
        }
        return try {
            ctx.startActivity(Intent().apply {
                this.component = ComponentName(parts[0], parts[1])
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch '$action' → $component", e)
            true
        }
    }

    // ── Preference helpers ────────────────────────────────────────────────────

    /** Returns the stored "package/activity" string, or null if the action is unrecognised. */
    fun getComponent(ctx: Context, action: String): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val key   = "$PREF_PREFIX$action"
        return if (prefs.contains(key)) prefs.getString(key, "") else null
    }

    fun setComponent(ctx: Context, action: String, component: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("$PREF_PREFIX$action", component)
            .apply()
    }

    /** Human-readable label of the currently mapped app, or null if unset. */
    fun getMappedAppLabel(ctx: Context, action: String): String? {
        val component = getComponent(ctx, action)?.takeIf { it.isNotEmpty() } ?: return null
        val pkg = component.split("/", limit = 2).firstOrNull() ?: return null
        return try {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { pkg }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun audioManager(ctx: Context) =
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun dispatchMediaKey(ctx: Context, keyCode: Int) {
        val am = audioManager(ctx)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   keyCode))
    }
}
