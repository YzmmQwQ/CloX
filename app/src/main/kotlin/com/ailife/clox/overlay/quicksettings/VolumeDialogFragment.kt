package com.ailife.clox.overlay.quicksettings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ailife.clox.R
import com.ailife.clox.qs.QsPermissionHelper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider

class VolumeDialogFragment : BottomSheetDialogFragment() {

    private lateinit var audio: AudioManager

    private val streams = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_NOTIFICATION
    )

    private val sliderIds = listOf(
        R.id.volMediaSlider, R.id.volRingSlider, R.id.volAlarmSlider, R.id.volNotifSlider
    )
    private val pctIds = listOf(
        R.id.volMediaPct, R.id.volRingPct, R.id.volAlarmPct, R.id.volNotifPct
    )

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            view?.let { syncSliders(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_volume, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audio = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupSliders(view)
        syncSliders(view)
    }

    private fun setupSliders(view: View) {
        streams.forEachIndexed { i, stream ->
            val slider = view.findViewById<Slider>(sliderIds[i])
            val max = audio.getStreamMaxVolume(stream).toFloat()
            slider.valueFrom = 0f
            slider.valueTo = max.coerceAtLeast(1f)
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    try {
                        audio.setStreamVolume(stream, value.toInt(), 0)
                        view.findViewById<TextView>(pctIds[i]).text =
                            "${(value / max * 100).toInt()}%"
                    } catch (e: SecurityException) {
                        // Muting/unmuting the ring or notification stream toggles the
                        // ringer/DND state, which requires notification-policy access.
                        // Without it the framework throws instead of applying the change.
                        // Revert the slider to the real level and send the user to grant it.
                        syncSliders(view)
                        QsPermissionHelper.ensureDndAccess(requireContext())
                    }
                }
            }
        }
    }

    private fun syncSliders(view: View) {
        streams.forEachIndexed { i, stream ->
            val slider = view.findViewById<Slider>(sliderIds[i])
            val max = audio.getStreamMaxVolume(stream).toFloat()
            val cur = audio.getStreamVolume(stream).toFloat()
            slider.value = cur.coerceIn(slider.valueFrom, slider.valueTo)
            view.findViewById<TextView>(pctIds[i]).text =
                "${(cur / max * 100).toInt()}%"
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    override fun onPause() {
        super.onPause()
        runCatching { requireContext().unregisterReceiver(volumeReceiver) }
    }
}
