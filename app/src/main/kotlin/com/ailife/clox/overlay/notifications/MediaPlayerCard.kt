package com.ailife.clox.overlay.notifications

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderEffectBlur
import eightbitlab.com.blurview.RenderScriptBlur
import android.widget.ImageView
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.util.safeStartActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.TimeUnit

/**
 * Plain Kotlin class (not a View) that binds media session data to views in [item_media_player.xml].
 */
class MediaPlayerCard(root: View, private val onVolumeClick: () -> Unit) {

    private val albumArt: ImageView          = root.findViewById(R.id.albumArt)
    private val mediaAppIcon: ImageView      = root.findViewById(R.id.mediaAppIcon)
    private val mediaAppName: TextView       = root.findViewById(R.id.mediaAppName)
    private val trackTitle: TextView         = root.findViewById(R.id.trackTitle)
    private val artist: TextView             = root.findViewById(R.id.artistName)
    private val progress: WaveSeekBar        = root.findViewById(R.id.mediaProgress)
    private val timeElapsed: TextView        = root.findViewById(R.id.timeElapsed)
    private val timeTotal: TextView          = root.findViewById(R.id.timeTotal)
    private val btnPlayPause: MaterialButton = root.findViewById(R.id.btnPlayPause)
    private val btnPrev: ImageButton         = root.findViewById(R.id.btnPrev)
    private val btnNext: ImageButton         = root.findViewById(R.id.btnNext)
    private val btnClose: ImageButton        = root.findViewById(R.id.btnClose)
    private val btnLoop: ImageButton         = root.findViewById(R.id.btnLoop)
    private val btnVolume: ImageButton       = root.findViewById(R.id.btnVolume)
    private val mediaControls: BlurView      = root.findViewById(R.id.mediaControls)

    private var controller: MediaController? = null
    private var duration = 0L

    private enum class LoopMode { NONE, ALL, ONE }
    private var loopMode = LoopMode.NONE

    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekRunnable = object : Runnable {
        override fun run() {
            val pos = controller?.playbackState?.position ?: return
            progress.progress = pos.toInt()
            timeElapsed.text = formatMs(pos)
            seekHandler.postDelayed(this, 500)
        }
    }

    init {
        // Set up BlurView to blur the album art behind the controls pill
        val decorView = root.rootView as? ViewGroup ?: (root as? ViewGroup)
        if (decorView != null) {
            val algorithm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                RenderEffectBlur() else RenderScriptBlur(root.context)
            @Suppress("DEPRECATION")
            mediaControls.setupWith(decorView, algorithm)
                .setBlurRadius(10f)
                .setBlurAutoUpdate(true)
        }

        mediaAppIcon.setOnClickListener {
            val pkg = controller?.packageName ?: return@setOnClickListener
            val intent = mediaAppIcon.context.packageManager
                .getLaunchIntentForPackage(pkg)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                           android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (intent != null) mediaAppIcon.context.safeStartActivity(intent)
        }

        btnPlayPause.setOnClickListener { togglePlayback() }
        btnPrev.setOnClickListener { controller?.transportControls?.skipToPrevious() }
        btnNext.setOnClickListener { controller?.transportControls?.skipToNext() }
        btnClose.setOnClickListener { root.visibility = View.GONE; progress.playing = false }
        btnLoop.setOnClickListener { cycleLoopMode() }
        btnVolume.setOnClickListener { onVolumeClick() }

        progress.onSeekStart = { seekHandler.removeCallbacks(seekRunnable) }
        progress.onProgressChanged = { pos, fromUser -> if (fromUser) timeElapsed.text = formatMs(pos.toLong()) }
        progress.onSeekEnd = { pos ->
            controller?.transportControls?.seekTo(pos.toLong())
            seekHandler.postDelayed(seekRunnable, 200)
        }
    }

    fun bindActiveSession(
        context: Context,
        listenerComponent: ComponentName,
        onSessionFound: ((Boolean) -> Unit)? = null
    ) {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val mc = msm.getActiveSessions(listenerComponent).firstOrNull()
            bindController(mc)
            onSessionFound?.invoke(mc != null)
        } catch (e: SecurityException) {
            onSessionFound?.invoke(false)
        }
    }

    private fun bindController(mc: MediaController?) {
        controller?.unregisterCallback(mediaCallback)
        controller = mc
        if (mc == null) { progress.playing = false; seekHandler.removeCallbacks(seekRunnable); return }
        mc.registerCallback(mediaCallback)
        updateMetadata(mc.metadata)
        updatePlayState(mc.playbackState)
        loadAppInfo(mc.packageName)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = updatePlayState(state)
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        trackTitle.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        artist.text     = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) albumArt.setImageBitmap(art)
        else albumArt.setImageResource(R.drawable.ic_music_note)

        duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration > 0L) { progress.max = duration.toInt(); timeTotal.text = formatMs(duration) }
        else { progress.max = 100; timeTotal.text = "" }
    }

    private fun updatePlayState(state: PlaybackState?) {
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        btnPlayPause.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        progress.playing = isPlaying
        if (isPlaying) {
            seekHandler.removeCallbacks(seekRunnable); seekHandler.post(seekRunnable)
        } else {
            seekHandler.removeCallbacks(seekRunnable)
            val pos = state?.position ?: 0L
            progress.progress = pos.toInt(); timeElapsed.text = formatMs(pos)
        }
    }

    private fun loadAppInfo(packageName: String) {
        try {
            val pm = albumArt.context.packageManager
            mediaAppIcon.setImageDrawable(pm.getApplicationIcon(packageName))
            mediaAppName.text = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
        } catch (e: Exception) { mediaAppIcon.setImageDrawable(null); mediaAppName.text = "" }
    }

    private fun togglePlayback() {
        val ctrl = controller ?: return
        if (ctrl.playbackState?.state == PlaybackState.STATE_PLAYING) ctrl.transportControls.pause()
        else ctrl.transportControls.play()
    }

    private fun cycleLoopMode() {
        loopMode = when (loopMode) { LoopMode.NONE -> LoopMode.ALL; LoopMode.ALL -> LoopMode.ONE; LoopMode.ONE -> LoopMode.NONE }
        when (loopMode) {
            LoopMode.NONE -> { btnLoop.setImageResource(R.drawable.ic_loop);     btnLoop.alpha = 0.4f }
            LoopMode.ALL  -> { btnLoop.setImageResource(R.drawable.ic_loop);     btnLoop.alpha = 1f   }
            LoopMode.ONE  -> { btnLoop.setImageResource(R.drawable.ic_loop_one); btnLoop.alpha = 1f   }
        }
        controller?.transportControls?.sendCustomAction("repeat_mode", android.os.Bundle().apply { putInt("repeat_mode", loopMode.ordinal) })
    }

    fun detach() {
        seekHandler.removeCallbacks(seekRunnable)
        controller?.unregisterCallback(mediaCallback)
    }

    private fun showVolumePanel() {
        val am = albumArt.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    private fun formatMs(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
