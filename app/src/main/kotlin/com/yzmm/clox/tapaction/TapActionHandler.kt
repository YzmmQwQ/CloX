package com.yzmm.clox.tapaction

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import com.yzmm.clox.R

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

    /**
     * Outcome of [handle], so the caller (which owns a window) can surface the
     * right UI. Most actions just complete; the two failure modes need a dialog.
     */
    sealed class Result {
        /** Action was carried out, or it was not one of ours — nothing to surface. */
        object Handled : Result()
        /** A recognised configurable action that has no app mapped yet — offer to configure it. */
        data class NotConfigured(val def: ActionDef) : Result()
        /** The target activity could not be launched (app uninstalled/disabled, or no handler). */
        data class LaunchFailed(val appLabel: String?) : Result()
    }

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
     * Main entry point. Returns a [Result] describing what happened so the caller can
     * surface a dialog for the two user-facing failure modes (no app mapped, or the
     * mapped app can't be launched). A blank/unrecognised action is [Result.Handled]
     * (nothing to do) — this preserves the old "swallow unknown actions" behaviour.
     */
    fun handle(ctx: Context, action: String): Result {
        if (action.isBlank()) return Result.Handled
        handleBuiltin(ctx, action)?.let { return it }
        return handleConfigured(ctx, action)
    }

    // ── Built-in actions ──────────────────────────────────────────────────────

    /** Returns null when [action] is not a built-in (fall through to configured actions). */
    private fun handleBuiltin(ctx: Context, action: String): Result? = when {
        action == "vol_up" -> {
            audioManager(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            Result.Handled
        }
        action == "vol_down" -> {
            audioManager(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            Result.Handled
        }
        action == "media_play_pause" -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); Result.Handled }
        action == "media_play"       -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);       Result.Handled }
        action == "media_pause"      -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE);      Result.Handled }
        action == "media_next"       -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);       Result.Handled }
        action == "media_prev"       -> { dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);   Result.Handled }
        action.startsWith("set_alarm_") -> setAlarm(ctx, action.removePrefix("set_alarm_"))
        // System-settings shortcuts (symbolic — the OS resolves the concrete screen).
        action == "open_settings"  -> startIntentSafely(ctx, Intent(Settings.ACTION_SETTINGS))
        action == "battery_status" -> startIntentSafely(ctx, Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        // Direct launch of a specific app named by the watchface itself (not user-configured).
        // Format: "w_app:label`package`activity" (label optional; a bare package or
        // "package`activity" is also accepted). "m_app" means "launch on phone" in the
        // WatchMaker vocabulary; CloX has no phone companion, so it launches locally too.
        action.startsWith("w_app:") -> launchAppSpec(ctx, action.removePrefix("w_app:"))
        action.startsWith("m_app:") -> launchAppSpec(ctx, action.removePrefix("m_app:"))
        // Deep link / URL launched via ACTION_VIEW, e.g. "w_link:app://open.my.app"
        // or "w_link:https://example.com". Mirrors WFF <Launch target="app://…"/>.
        action.startsWith("w_link:") -> launchLink(ctx, action.removePrefix("w_link:"))
        else -> null
    }

    private fun launchLink(ctx: Context, uri: String): Result {
        val target = uri.trim()
        if (target.isEmpty()) return Result.Handled   // malformed spec — nothing to launch
        return startIntentSafely(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(target)))
    }

    /**
     * Launches an app from a WatchMaker `w_app`/`m_app` spec. The spec is a
     * backtick-separated string; the concrete component is the last one or two
     * fields (a leading display label, if present, is ignored):
     *   "label`pkg`activity" | "pkg`activity" | "pkg"
     */
    private fun launchAppSpec(ctx: Context, spec: String): Result {
        val fields = spec.split("`").map { it.trim() }.filter { it.isNotEmpty() }
        val intent = when (fields.size) {
            0 -> return Result.Handled   // empty spec — malformed, nothing to launch
            1 -> ctx.packageManager.getLaunchIntentForPackage(fields[0])
                ?: run { Log.w(TAG, "w_app: no launch intent for ${fields[0]}"); return Result.LaunchFailed(null) }
            else -> {
                // Last two fields are package + activity; earlier fields are the label.
                val pkg = fields[fields.size - 2]
                val activity = fields.last()
                Intent().setComponent(ComponentName(pkg, activity))
            }
        }
        return startIntentSafely(ctx, intent)
    }

    private fun startIntentSafely(ctx: Context, intent: Intent?): Result {
        if (intent == null) return Result.LaunchFailed(null)
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.Handled
        } catch (e: Exception) {
            Log.e(TAG, "startActivity failed: $intent", e)
            Result.LaunchFailed(null)
        }
    }

    private fun setAlarm(ctx: Context, timeStr: String): Result {
        val parts = timeStr.split(":")
        if (parts.size != 2) return Result.Handled   // malformed time — ignore
        return try {
            ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR,    parts[0].toInt())
                putExtra(AlarmClock.EXTRA_MINUTES, parts[1].toInt())
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Result.Handled
        } catch (e: Exception) {
            Log.e(TAG, "set_alarm failed: $timeStr", e)
            Result.LaunchFailed(null)
        }
    }

    // ── Configurable actions ──────────────────────────────────────────────────

    private fun handleConfigured(ctx: Context, action: String): Result {
        val def = CONFIGURABLE_ACTIONS.firstOrNull { it.key == action }
            ?: return Result.Handled        // not a recognised action key — nothing to do

        val component = getComponent(ctx, action)
        if (component.isNullOrBlank()) {
            Log.d(TAG, "Action '$action' known but not configured — prompting to configure")
            return Result.NotConfigured(def)   // known action, no app mapped — offer to set one
        }

        val parts = component.split("/", limit = 2)
        if (parts.size < 2) {
            Log.e(TAG, "Bad component format for '$action': $component")
            return Result.LaunchFailed(getMappedAppLabel(ctx, action))
        }
        return try {
            ctx.startActivity(Intent().apply {
                this.component = ComponentName(parts[0], parts[1])
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Result.Handled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch '$action' → $component", e)
            Result.LaunchFailed(getMappedAppLabel(ctx, action))
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
