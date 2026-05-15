package com.ailife.clox.overlay.notifications

import android.content.ComponentName
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R

class MediaSingleAdapter(
    private val onVolumeClick: () -> Unit
) : RecyclerView.Adapter<MediaSingleAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card = MediaPlayerCard(view, onVolumeClick)
    }

    private var listenerComponent: ComponentName? = null
    private var hasSession = false

    fun bind(context: Context, component: ComponentName) {
        listenerComponent = component
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as android.media.session.MediaSessionManager
            val newHas = msm.getActiveSessions(component).isNotEmpty()
            if (newHas != hasSession) { hasSession = newHas; notifyDataSetChanged() }
        } catch (e: Exception) { hasSession = false }
    }

    override fun getItemCount() = if (hasSession) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_player, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val comp = listenerComponent ?: return
        holder.card.bindActiveSession(holder.itemView.context, comp) { sessionFound ->
            if (hasSession != sessionFound) {
                hasSession = sessionFound
                // Defer — callback fires synchronously during onBind, so we can't notify while
                // the RecyclerView is still computing its layout.
                holder.itemView.post { notifyDataSetChanged() }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.card.detach()
    }
}
