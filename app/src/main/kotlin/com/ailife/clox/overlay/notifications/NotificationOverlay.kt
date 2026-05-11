package com.ailife.clox.overlay.notifications

import android.content.ComponentName
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import kotlinx.coroutines.launch

class NotificationOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val mediaCard: MediaPlayerCard
    private val notifList: RecyclerView
    private val blurBg: ImageView
    private val adapter: NotificationListAdapter

    init {
        LayoutInflater.from(ctx).inflate(R.layout.overlay_notifications, this, true)
        mediaCard = findViewById(R.id.mediaPlayerCard)
        notifList = findViewById(R.id.notifList)
        blurBg = findViewById<ImageView>(R.id.notifBlurBg).apply { tag = "blur_bg" }

        adapter = NotificationListAdapter { sbn -> ReplyPopup.show(ctx, sbn) }
        notifList.layoutManager = LinearLayoutManager(ctx)
        notifList.adapter = adapter
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch {
            CloXNotificationListenerService.notifications.collect { notifs ->
                adapter.submitList(notifs)
            }
        }
        val listenerComponent = ComponentName(context, CloXNotificationListenerService::class.java)
        mediaCard.bindActiveSession(context, listenerComponent)
    }
}
