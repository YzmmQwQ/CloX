package com.ailife.clox

import android.content.ComponentName
import android.content.Intent
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.UserHandle
import android.util.Log
import android.view.SurfaceControl
import androidx.annotation.RequiresApi

/**
 * Handshake protocol between the launcher and Android 11+ gesture navigation.
 * The system sends this contract in the HOME intent; the launcher replies with
 * the icon's screen rect so the system can animate the closing app window to it.
 *
 * Mirrors AOSP's GestureNavContract.java from Launcher3.
 */
@RequiresApi(Build.VERSION_CODES.R)
class GestureNavContract private constructor(
    val componentName: ComponentName,
    val userHandle: UserHandle,
    private val callback: Message
) {

    fun sendEndPosition(position: RectF) = sendEndPosition(position, null)

    /**
     * Reply with the icon rect and, optionally, a real [SurfaceControl] holding the icon. When a
     * surface is provided the system morphs the closing app window directly into it (a true
     * window→icon morph); when null the system just collapses the window toward [position].
     */
    fun sendEndPosition(position: RectF, surface: SurfaceControl?) {
        val result = Bundle().apply {
            putParcelable(EXTRA_ICON_POSITION, position)
            putParcelable(EXTRA_ICON_SURFACE, surface)
        }
        try {
            val reply = Message.obtain()
            reply.copyFrom(callback)
            reply.data = result
            callback.replyTo.send(reply)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending icon position", e)
        }
    }

    companion object {
        private const val TAG = "GestureNavContract"
        const val EXTRA_GESTURE_CONTRACT  = "gesture_nav_contract_v1"
        const val EXTRA_ICON_POSITION     = "gesture_nav_contract_icon_position"
        const val EXTRA_ICON_SURFACE      = "gesture_nav_contract_surface_control"
        private const val EXTRA_REMOTE_CALLBACK = "android.intent.extra.REMOTE_CALLBACK"

        fun fromIntent(intent: Intent?): GestureNavContract? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || intent == null) return null
            val extras = intent.getBundleExtra(EXTRA_GESTURE_CONTRACT) ?: return null
            intent.removeExtra(EXTRA_GESTURE_CONTRACT)

            val component = extras.getParcelable<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
            val user      = extras.getParcelable<UserHandle>(Intent.EXTRA_USER)
            val callback  = extras.getParcelable<Message>(EXTRA_REMOTE_CALLBACK)

            return if (component != null && user != null && callback?.replyTo != null)
                GestureNavContract(component, user, callback) else null
        }
    }
}
