package com.ailife.clox.overlay.notifications

import android.service.notification.StatusBarNotification
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.google.android.material.button.MaterialButton

class NotificationListAdapter(
    private val onReply: (StatusBarNotification) -> Unit
) : ListAdapter<StatusBarNotification, NotificationListAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView = view.findViewById(R.id.notifAppIcon)
        val appName: TextView = view.findViewById(R.id.notifAppName)
        val title: TextView = view.findViewById(R.id.notifTitle)
        val body: TextView = view.findViewById(R.id.notifBody)
        val actionsRow: LinearLayout = view.findViewById(R.id.notifActionsRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sbn = getItem(position)
        val notif = sbn.notification
        val extras = notif.extras

        val pm = holder.itemView.context.packageManager
        try {
            holder.appIcon.setImageDrawable(pm.getApplicationIcon(sbn.packageName))
            holder.appName.text = pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0))
        } catch (e: Exception) { /* ignore */ }

        holder.title.text = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
        holder.body.text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
        holder.body.maxLines = 2

        holder.actionsRow.removeAllViews()
        notif.actions?.take(3)?.forEach { action ->
            val btn = MaterialButton(holder.itemView.context).apply {
                text = action.title
                setOnClickListener {
                    if (action.remoteInputs?.isNotEmpty() == true) {
                        onReply(sbn)
                    } else {
                        try { action.actionIntent.send() } catch (e: Exception) {}
                    }
                }
            }
            holder.actionsRow.addView(btn)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StatusBarNotification>() {
            override fun areItemsTheSame(a: StatusBarNotification, b: StatusBarNotification) =
                a.key == b.key
            override fun areContentsTheSame(a: StatusBarNotification, b: StatusBarNotification) =
                a.key == b.key && a.postTime == b.postTime
        }
    }
}
