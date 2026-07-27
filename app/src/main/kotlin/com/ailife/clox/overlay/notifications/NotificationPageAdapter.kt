package com.ailife.clox.overlay.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.google.android.material.button.MaterialButton

class NotificationPageAdapter(
    private val onReply: (StatusBarNotification) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_MEDIA = 0
        private const val TYPE_NOTIF = 1
    }

    private var items: List<StatusBarNotification> = emptyList()
    private val expanded = mutableSetOf<String>()
    private var listenerComponent: ComponentName? = null
    private var hasMediaSession = false
    private var mediaDismissed = false

    /** Whether the media page should currently occupy slot 0. */
    private fun showMedia() = hasMediaSession && !mediaDismissed

    fun bindMediaSession(context: Context, component: ComponentName) {
        listenerComponent = component
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as android.media.session.MediaSessionManager
            val newHas = msm.getActiveSessions(component).isNotEmpty()
            if (!newHas) mediaDismissed = false   // session gone — allow a future session to show
            if (newHas != hasMediaSession) { hasMediaSession = newHas; notifyDataSetChanged() }
        } catch (e: Exception) {}
    }

    fun submitNotifList(list: List<StatusBarNotification>) {
        items = list
        expanded.retainAll(list.map { it.key }.toSet())
        notifyDataSetChanged()
    }

    // ViewPager2 requires page root to be MATCH_PARENT × MATCH_PARENT.
    // The card is inflated inside a FrameLayout wrapper so its wrap_content height is respected.
    inner class MediaPageVH(wrapper: FrameLayout, val card: MediaPlayerCard) : RecyclerView.ViewHolder(wrapper)

    inner class PageVH(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView       = view.findViewById(R.id.pageNotifAppIcon)
        val largeIcon: com.google.android.material.imageview.ShapeableImageView = view.findViewById(R.id.pageNotifLargeIcon)
        val appIconBadge: ImageView  = view.findViewById(R.id.pageNotifAppIconBadge)
        val appName: TextView        = view.findViewById(R.id.pageNotifAppName)
        val time: TextView           = view.findViewById(R.id.pageNotifTime)
        val title: TextView          = view.findViewById(R.id.pageNotifTitle)
        val body: TextView           = view.findViewById(R.id.pageNotifBody)
        val bigPicture: ImageView    = view.findViewById(R.id.pageNotifBigPicture)
        val actionsContainer: LinearLayout = view.findViewById(R.id.pageNotifActions)
        val btnShowMore: MaterialButton  = view.findViewById(R.id.btnShowMore)
        val btnOpenNotif: MaterialButton = view.findViewById(R.id.btnOpenNotif)
        val btnDismiss: ImageButton      = view.findViewById(R.id.pageNotifDismiss)
    }

    override fun getItemViewType(position: Int) =
        if (showMedia() && position == 0) TYPE_MEDIA else TYPE_NOTIF

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_MEDIA) {
            val wrapper = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
            }
            // Inflate from XML — card gets all its styling (stroke, bg, radius) from the layout
            val cardView = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_player, wrapper, false)
            wrapper.addView(cardView)
            return MediaPageVH(wrapper, MediaPlayerCard(cardView, onVolumeClick = {}, onClose = {
                // User closed the card: drop the page so it no longer takes a full-screen slot.
                mediaDismissed = true
                notifyDataSetChanged()
            }))
        }
        return PageVH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification_page, parent, false)
        )
    }

    override fun getItemCount() = items.size + if (showMedia()) 1 else 0

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is MediaPageVH) {
            val ctx = holder.itemView.context
            listenerComponent?.let { comp -> holder.card.bindActiveSession(ctx, comp) }
            return
        }
        val h = holder as PageVH
        val notifPos = if (showMedia()) position - 1 else position
        val sbn = items[notifPos]
        val notif = sbn.notification
        val extras = notif.extras
        val ctx = h.itemView.context
        val pm = ctx.packageManager

        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getInt(NotificationPrefs.NOTIF_TEXT_SIZE, 14).toFloat()
        h.title.textSize = sp + 2f
        h.body.textSize = sp

        h.appName.text = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0))
        } catch (e: Exception) { sbn.packageName }
        h.time.text = DateUtils.getRelativeTimeSpanString(
            sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )

        val largeBitmap = extractLargeIcon(notif, ctx)
        if (largeBitmap != null) {
            h.largeIcon.setImageBitmap(largeBitmap)
            h.largeIcon.visibility    = View.VISIBLE
            h.appIcon.visibility      = View.GONE
            h.appIconBadge.visibility = View.VISIBLE
            try { h.appIconBadge.setImageDrawable(pm.getApplicationIcon(sbn.packageName)) }
            catch (e: Exception) {}
        } else {
            h.largeIcon.visibility    = View.GONE
            h.appIconBadge.visibility = View.GONE
            h.appIcon.visibility      = View.VISIBLE
            try { h.appIcon.setImageDrawable(pm.getApplicationIcon(sbn.packageName)) }
            catch (e: Exception) { h.appIcon.setImageResource(R.drawable.ic_notifications) }
        }

        h.title.text = extras.getCharSequence(Notification.EXTRA_TITLE)
        h.body.text  = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)

        val isExpanded = sbn.key in expanded
        bindExpanded(h, notif, sbn, isExpanded)

        h.btnShowMore.setOnClickListener {
            if (sbn.key in expanded) expanded.remove(sbn.key) else expanded.add(sbn.key)
            notifyItemChanged(position)
        }

        val isPersistent = (sbn.notification.flags and
                (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        h.btnDismiss.visibility = if (isPersistent) View.GONE else View.VISIBLE
        h.btnDismiss.setOnClickListener {
            CloXNotificationListenerService.dismissNotification(sbn.key)
        }

        if (NotificationListAdapter.hasOpenAction(sbn)) {
            h.btnOpenNotif.visibility = View.VISIBLE
            h.btnOpenNotif.setOnClickListener { NotificationListAdapter.openNotification(sbn, ctx) }
        } else {
            h.btnOpenNotif.visibility = View.GONE
        }
    }

    private fun bindExpanded(h: PageVH, notif: Notification, sbn: StatusBarNotification, expanded: Boolean) {
        h.btnShowMore.text = h.itemView.context.getString(
            if (expanded) R.string.notif_action_show_less else R.string.notif_action_show_more)

        if (!expanded) {
            h.bigPicture.visibility = View.GONE
            h.actionsContainer.visibility = View.GONE
            return
        }

        val bigText = notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        if (bigText != null) h.body.text = bigText

        @Suppress("DEPRECATION")
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            notif.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        else notif.extras.getParcelable(Notification.EXTRA_PICTURE)

        if (bitmap != null) {
            h.bigPicture.setImageBitmap(bitmap)
            h.bigPicture.visibility = View.VISIBLE
        } else {
            h.bigPicture.visibility = View.GONE
        }

        h.actionsContainer.removeAllViews()
        val actions = notif.actions
        if (!actions.isNullOrEmpty()) {
            h.actionsContainer.visibility = View.VISIBLE
            actions.take(3).forEach { action ->
                val btn = MaterialButton(h.itemView.context).apply {
                    text = action.title
                    val hasRemoteInput = action.remoteInputs?.isNotEmpty() == true
                    if (hasRemoteInput) setIconResource(R.drawable.ic_reply)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8 }
                    setOnClickListener {
                        if (hasRemoteInput) onReply(sbn)
                        else try {
                            NotificationListAdapter.sendPendingIntent(action.actionIntent, h.itemView.context)
                        } catch (e: Exception) {}
                    }
                }
                h.actionsContainer.addView(btn)
            }
        } else {
            h.actionsContainer.visibility = View.GONE
        }
    }

    private fun extractLargeIcon(notif: Notification, ctx: android.content.Context): Bitmap? {
        val extras = notif.extras
        fun resolve(key: String): Bitmap? {
            val p: android.os.Parcelable? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                extras.getParcelable(key, android.os.Parcelable::class.java)
            else @Suppress("DEPRECATION") extras.getParcelable(key)
            return when {
                p is Bitmap -> p
                p != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    try {
                        val icon = p as android.graphics.drawable.Icon
                        val d = icon.loadDrawable(ctx) ?: return null
                        val w = d.intrinsicWidth.coerceIn(1, 256)
                        val h = d.intrinsicHeight.coerceIn(1, 256)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bm ->
                            val c = android.graphics.Canvas(bm)
                            d.setBounds(0, 0, w, h); d.draw(c)
                        }
                    } catch (e: Exception) { null }
                else -> null
            }
        }
        return resolve(Notification.EXTRA_LARGE_ICON_BIG) ?: resolve(Notification.EXTRA_LARGE_ICON)
    }
}
