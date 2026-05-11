package com.ailife.clox.overlay.notifications

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.ailife.clox.R
import com.google.android.material.card.MaterialCardView

class MediaPlayerCard @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : MaterialCardView(ctx, attrs) {

    private val albumArt: ImageView
    private val trackTitle: TextView
    private val artist: TextView
    private val progressBar: SeekBar
    private val playPause: ImageButton
    private val prev: ImageButton
    private val next: ImageButton

    private var controller: MediaController? = null

    init {
        LayoutInflater.from(ctx).inflate(R.layout.card_media_player, this, true)
        albumArt = findViewById(R.id.albumArt)
        trackTitle = findViewById(R.id.trackTitle)
        artist = findViewById(R.id.artistName)
        progressBar = findViewById(R.id.mediaProgress)
        playPause = findViewById(R.id.btnPlayPause)
        prev = findViewById(R.id.btnPrev)
        next = findViewById(R.id.btnNext)

        playPause.setOnClickListener { togglePlayback() }
        prev.setOnClickListener { controller?.transportControls?.skipToPrevious() }
        next.setOnClickListener { controller?.transportControls?.skipToNext() }
    }

    fun bindActiveSession(context: Context, listenerComponent: ComponentName) {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = msm.getActiveSessions(listenerComponent)
            controller = sessions.firstOrNull()
            controller?.let { bindController(it) }
            visibility = if (controller != null) View.VISIBLE else View.GONE
        } catch (e: SecurityException) {
            visibility = View.GONE
        }
    }

    private fun bindController(mc: MediaController) {
        mc.registerCallback(object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) { updateMetadata(metadata) }
            override fun onPlaybackStateChanged(state: PlaybackState?) { updatePlayState(state) }
        })
        updateMetadata(mc.metadata)
        updatePlayState(mc.playbackState)
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        trackTitle.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        artist.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (art != null) albumArt.setImageBitmap(art)
        else albumArt.setImageResource(R.drawable.ic_music_note)
    }

    private fun updatePlayState(state: PlaybackState?) {
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        playPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun togglePlayback() {
        val ctrl = controller ?: return
        val state = ctrl.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING) ctrl.transportControls.pause()
        else ctrl.transportControls.play()
    }
}
