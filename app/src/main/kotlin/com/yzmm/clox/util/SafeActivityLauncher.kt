package com.yzmm.clox.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.yzmm.clox.R

/**
 * Crash-safe activity launching.
 *
 * Any `startActivity` / `startActivityForResult` / `ActivityResultLauncher.launch`
 * can throw at runtime — most commonly [ActivityNotFoundException] when no app on
 * the device can handle the intent (e.g. no file manager to import a watchface, no
 * browser to open a URL) or [SecurityException] when the OS blocks the target. On a
 * stripped-down smartwatch ROM these are routine, not exceptional. These helpers
 * catch every launch failure, surface a user-visible error, and return instead of
 * letting the app crash.
 */
object SafeActivityLauncher {

    private const val TAG = "CloX/SafeLaunch"

    /**
     * Java-callable crash-safe startActivity. Kotlin code should use the
     * [safeStartActivity] extension instead. Returns true on success.
     */
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, intent: Intent, showError: Boolean = true): Boolean =
        context.safeStartActivity(intent, showError = showError)

    /** Walks the ContextWrapper chain to find a usable Activity for a dialog. */
    tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    /**
     * Reports a launch failure to the user. Prefers a themed [AlertDialog] when an
     * Activity is reachable from [context]; otherwise (services, overlay windows,
     * application context) falls back to a Toast.
     */
    fun reportError(context: Context, e: Throwable) {
        val message = when (e) {
            is ActivityNotFoundException -> context.getString(R.string.launch_error_no_app)
            is SecurityException -> context.getString(R.string.launch_error_not_permitted)
            else -> context.getString(R.string.launch_error_generic)
        }
        val activity = context.findActivity()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            try {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.launch_error_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            } catch (dialogError: Exception) {
                // Window may be gone between the checks and show(); fall through to Toast.
                Log.w(TAG, "Could not show error dialog, falling back to toast", dialogError)
            }
        }
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}

/**
 * Crash-safe [Context.startActivity]. Returns true if the activity was launched,
 * false (after showing an error) if it failed.
 *
 * @param showError when false, failures are swallowed silently (still logged) —
 *   use for best-effort launches where a visible error would be noise.
 * @param onError optional custom failure handler; when provided it replaces the
 *   default error UI.
 */
fun Context.safeStartActivity(
    intent: Intent,
    options: Bundle? = null,
    showError: Boolean = true,
    onError: ((Exception) -> Unit)? = null,
): Boolean = try {
    if (options != null) startActivity(intent, options) else startActivity(intent)
    true
} catch (e: Exception) {
    // ActivityNotFoundException, SecurityException, AndroidRuntimeException (missing
    // NEW_TASK flag from a non-Activity context), and any other launch failure.
    Log.w("CloX/SafeLaunch", "startActivity failed for $intent", e)
    when {
        onError != null -> onError(e)
        showError -> SafeActivityLauncher.reportError(this, e)
    }
    false
}

/**
 * Crash-safe [Activity.startActivityForResult].
 */
fun Activity.safeStartActivityForResult(
    intent: Intent,
    requestCode: Int,
    options: Bundle? = null,
    showError: Boolean = true,
    onError: ((Exception) -> Unit)? = null,
): Boolean = try {
    if (options != null) startActivityForResult(intent, requestCode, options)
    else startActivityForResult(intent, requestCode)
    true
} catch (e: Exception) {
    Log.w("CloX/SafeLaunch", "startActivityForResult failed for $intent", e)
    when {
        onError != null -> onError(e)
        showError -> SafeActivityLauncher.reportError(this, e)
    }
    false
}

/**
 * Crash-safe launch from a [Fragment]. Delegates to the fragment's own
 * startActivity (so it participates in the fragment host) but catches failures.
 */
fun Fragment.safeStartActivity(
    intent: Intent,
    options: Bundle? = null,
    showError: Boolean = true,
    onError: ((Exception) -> Unit)? = null,
): Boolean = try {
    if (options != null) startActivity(intent, options) else startActivity(intent)
    true
} catch (e: Exception) {
    Log.w("CloX/SafeLaunch", "fragment startActivity failed for $intent", e)
    val ctx = context
    when {
        onError != null -> onError(e)
        showError && ctx != null -> SafeActivityLauncher.reportError(ctx, e)
    }
    false
}

/**
 * Crash-safe [Fragment.startActivityForResult].
 */
@Suppress("DEPRECATION")
fun Fragment.safeStartActivityForResult(
    intent: Intent,
    requestCode: Int,
    options: Bundle? = null,
    showError: Boolean = true,
    onError: ((Exception) -> Unit)? = null,
): Boolean = try {
    if (options != null) startActivityForResult(intent, requestCode, options)
    else startActivityForResult(intent, requestCode)
    true
} catch (e: Exception) {
    Log.w("CloX/SafeLaunch", "fragment startActivityForResult failed for $intent", e)
    val ctx = context
    when {
        onError != null -> onError(e)
        showError && ctx != null -> SafeActivityLauncher.reportError(ctx, e)
    }
    false
}

/**
 * Crash-safe [ActivityResultLauncher.launch]. Covers document pickers / permission
 * launchers whose underlying intent may have no handler (the classic "no file
 * manager" import crash). [context] is only needed to show the error.
 */
fun <I> ActivityResultLauncher<I>.safeLaunch(
    input: I,
    context: Context,
    options: androidx.core.app.ActivityOptionsCompat? = null,
    showError: Boolean = true,
    onError: ((Exception) -> Unit)? = null,
): Boolean = try {
    launch(input, options)
    true
} catch (e: Exception) {
    Log.w("CloX/SafeLaunch", "launcher.launch failed for $input", e)
    when {
        onError != null -> onError(e)
        showError -> SafeActivityLauncher.reportError(context, e)
    }
    false
}
