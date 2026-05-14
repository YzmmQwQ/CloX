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

    data class ActionDef(val key: String, val displayName: String)

    /** Ordered list of user-configurable named actions. */
    val CONFIGURABLE_ACTIONS = listOf(
        ActionDef("tap_launcher",    "Launcher / Home"),
        ActionDef("widget_weather",  "Weather"),
        ActionDef("widget_health",   "Health"),
        ActionDef("widget_calendar", "Calendar widget"),
        ActionDef("w_agenda",        "Agenda"),
        ActionDef("w_fit",           "Fitness"),
        ActionDef("w_heart_rate",    "Heart rate"),
        ActionDef("w_compass",       "Compass"),
        ActionDef("w_keep",          "Notes"),
        ActionDef("m_email",         "Email"),
        ActionDef("m_calendar",      "Calendar app"),
        ActionDef("voice_search",    "Voice search"),
        ActionDef("w_contacts",      "Contacts"),
        ActionDef("w_sports",        "Sports"),
        ActionDef("w_gallery",       "Gallery / Photos"),
        ActionDef("w_messaging",     "Messages"),
        ActionDef("w_camera",        "Camera"),
        ActionDef("w_dialer",        "Phone / Dialer"),
        ActionDef("w_music",         "Music player"),
        ActionDef("w_clock",         "Clock / Alarm"),
        ActionDef("w_calculator",    "Calculator"),
        ActionDef("w_payment",       "Wallet / Payment"),
        ActionDef("w_map",           "Maps / Navigation"),
        ActionDef("browser",         "Browser"),
        ActionDef("flashlight",      "Flashlight"),
        ActionDef("socialmedia",     "Social media"),
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
