package com.yzmm.clox.overlay.quicksettings

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.yzmm.clox.R
import com.yzmm.clox.overlay.notifications.CloXNotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Compact media-session state for the control center card. */
data class MediaUiState(
    val active: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
)

/**
 * Reads the active media session through the app's notification-listener grant, which is the
 * only supported way for a normal app to call [MediaSessionManager.getActiveSessions].
 */
class QsMediaController(private val ctx: Context) {

    private val _state = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    fun start() = refresh()

    fun stop() {
        controller?.unregisterCallback(callback)
        controller = null
        _state.value = MediaUiState()
    }

    fun refresh() {
        runCatching { controller?.unregisterCallback(callback) }
        controller = try {
            val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(ctx, CloXNotificationListenerService::class.java)
            manager.getActiveSessions(listener).firstOrNull()
        } catch (_: Exception) {
            null
        }
        controller?.registerCallback(callback, handler)
        publish()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next() { controller?.transportControls?.skipToNext() }

    fun previous() { controller?.transportControls?.skipToPrevious() }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = MediaUiState()
            return
        }
        val metadata = c.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ctx.getString(R.string.qs_media)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        _state.value = MediaUiState(
            active = true,
            title = title,
            artist = artist,
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }
}
