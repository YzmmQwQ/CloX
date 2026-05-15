package com.ailife.clox.overlay.notifications

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.ailife.clox.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class NotificationListAdapter(
    private val onReply: (StatusBarNotification) -> Unit
) : ListAdapter<StatusBarNotification, NotificationListAdapter.VH>(DIFF) {

    private val expanded = mutableSetOf<String>()

    inner class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        val iconSlot: ViewGroup      = card.findViewById(R.id.iconSlot)
        val appIcon: ImageView       = card.findViewById(R.id.notifAppIcon)
        val largeIcon: com.google.android.material.imageview.ShapeableImageView = card.findViewById(R.id.notifLargeIcon)
        val appIconBadge: ImageView  = card.findViewById(R.id.notifAppIconBadge)
        val appName: TextView        = card.findViewById(R.id.notifAppName)
        val time: TextView           = card.findViewById(R.id.notifTime)
        val btnExpand: ImageButton   = card.findViewById(R.id.btnExpand)
        val title: TextView          = card.findViewById(R.id.notifTitle)
        val body: TextView           = card.findViewById(R.id.notifBody)
        val bigPicture: ImageView    = card.findViewById(R.id.notifBigPicture)
        val actionsRow: LinearLayout = card.findViewById(R.id.notifActionsRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false) as MaterialCardView
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sbn = getItem(position)
        val notif = sbn.notification
        val extras = notif.extras
        val ctx = holder.itemView.context
        val pm = ctx.packageManager

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        // Card appearance style
        val solidStyle = prefs.getString(NotificationPrefs.NOTIF_CARD_STYLE, NotificationPrefs.CARD_STYLE_GLASS) == NotificationPrefs.CARD_STYLE_SOLID
        if (solidStyle) {
            holder.card.setCardBackgroundColor(0xFF2A2A2A.toInt())
            holder.card.strokeWidth = 0
        } else {
            holder.card.setCardBackgroundColor(0xCC1A1A1A.toInt())
            holder.card.strokeWidth = (1f * ctx.resources.displayMetrics.density).toInt()
            holder.card.setStrokeColor(android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt()))
        }

        // Text size
        val sp = prefs.getInt(NotificationPrefs.NOTIF_TEXT_SIZE, 14).toFloat()
        holder.title.textSize = sp
        holder.body.textSize  = sp - 1f

        // App name + time
        holder.appName.text = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0))
        } catch (e: Exception) { sbn.packageName }
        holder.time.text = DateUtils.getRelativeTimeSpanString(
            sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )

        // Large icon handling
        val largeBitmap = extractLargeIcon(notif, ctx)
        if (largeBitmap != null) {
            holder.largeIcon.setImageBitmap(largeBitmap)
            holder.largeIcon.visibility = View.VISIBLE
            holder.appIcon.visibility = View.GONE
            holder.appIconBadge.visibility = View.VISIBLE
            try { holder.appIconBadge.setImageDrawable(pm.getApplicationIcon(sbn.packageName)) }
            catch (e: Exception) {}
        } else {
            holder.largeIcon.visibility = View.GONE
            holder.appIconBadge.visibility = View.GONE
            holder.appIcon.visibility = View.VISIBLE
            try { holder.appIcon.setImageDrawable(pm.getApplicationIcon(sbn.packageName)) }
            catch (e: Exception) { holder.appIcon.setImageResource(R.drawable.ic_notifications) }
        }

        // Title + body — check for messaging style first
        val msgStyle = notif.extras.getString(Notification.EXTRA_TEMPLATE)
        if (msgStyle == Notification.MessagingStyle::class.java.name) {
            bindMessagingStyle(holder, extras, sbn)
        } else {
            holder.title.text = extras.getCharSequence(Notification.EXTRA_TITLE)
            holder.body.text  = extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        }

        // Expand / collapse
        val isExpanded = sbn.key in expanded
        holder.body.maxLines  = if (isExpanded) Int.MAX_VALUE else 2
        holder.body.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END
        holder.btnExpand.setImageResource(if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)

        bindExpandedContent(holder, notif, isExpanded)

        holder.btnExpand.setOnClickListener {
            TransitionManager.beginDelayedTransition(holder.card, AutoTransition().apply { duration = 200 })
            if (sbn.key in expanded) expanded.remove(sbn.key) else expanded.add(sbn.key)
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        // Tap to open — only if the notification has a contentIntent
        if (hasOpenAction(sbn)) {
            holder.card.setOnClickListener { openNotification(sbn, ctx) }
        } else {
            holder.card.setOnClickListener(null)
        }

        // Long-press context menu: block app / open app notification settings
        holder.itemView.setOnLongClickListener {
            showContextMenu(holder, sbn)
            true
        }
    }

    private fun bindMessagingStyle(holder: VH, extras: Bundle, sbn: StatusBarNotification) {
        holder.title.text = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val last = messages.last() as? Bundle
            holder.body.text = last?.getCharSequence("text")
        } else {
            holder.body.text = extras.getCharSequence(Notification.EXTRA_TEXT)
        }

        // Try to show conversation/person icon in the large icon slot
        val personIcon = extras.getParcelable<android.graphics.drawable.Icon>(Notification.EXTRA_LARGE_ICON)
        if (personIcon != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                holder.largeIcon.setImageIcon(personIcon)
                holder.largeIcon.visibility = View.VISIBLE
                holder.appIcon.visibility = View.GONE
                holder.appIconBadge.visibility = View.VISIBLE
                val pm = holder.itemView.context.packageManager
                try { holder.appIconBadge.setImageDrawable(pm.getApplicationIcon(sbn.packageName)) }
                catch (e: Exception) {}
            } catch (e: Exception) {}
        }
    }

    private fun bindExpandedContent(holder: VH, notif: Notification, isExpanded: Boolean) {
        val extras = notif.extras
        if (!isExpanded) {
            holder.bigPicture.visibility = View.GONE
            holder.actionsRow.visibility = View.GONE
            return
        }

        // Big picture
        @Suppress("DEPRECATION")
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        else extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)

        if (bitmap != null) {
            holder.bigPicture.setImageBitmap(bitmap)
            holder.bigPicture.visibility = View.VISIBLE
        } else {
            holder.bigPicture.visibility = View.GONE
        }

        // Action buttons
        holder.actionsRow.removeAllViews()
        val actions = notif.actions
        if (!actions.isNullOrEmpty()) {
            holder.actionsRow.visibility = View.VISIBLE
            actions.take(3).forEach { action ->
                val btn = MaterialButton(holder.itemView.context,null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = action.title
                    textSize = 12f
                    val hasRemoteInput = action.remoteInputs?.isNotEmpty() == true
                    if (hasRemoteInput) setIconResource(R.drawable.ic_reply)
                    setOnClickListener {
                        if (hasRemoteInput) {
                            onReply(getItem(holder.bindingAdapterPosition))
                        } else {
                            try { action.actionIntent.send() } catch (e: Exception) {}
                        }
                    }
                }
                holder.actionsRow.addView(btn)
            }
        } else {
            holder.actionsRow.visibility = View.GONE
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

    private fun showContextMenu(holder: VH, sbn: StatusBarNotification) {
        val ctx = holder.itemView.context
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val isBlocked = CloXNotificationListenerService.blockedApps(prefs).contains(sbn.packageName)
        val blockLabel = if (isBlocked) "Unblock app" else "Block app"

        MaterialAlertDialogBuilder(ctx)
            .setTitle(holder.appName.text)
            .setItems(arrayOf(blockLabel, "App notification settings")) { _, which ->
                when (which) {
                    0 -> {
                        CloXNotificationListenerService.setAppBlocked(prefs, sbn.packageName, !isBlocked)
                    }
                    1 -> {
                        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, sbn.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
            }
            .show()
    }

    // Keys of notifications the user has swiped — filtered out locally until service confirms removal
    private val pendingDismissKeys = mutableSetOf<String>()

    override fun submitList(list: List<StatusBarNotification>?) {
        val filtered = list?.filter { it.key !in pendingDismissKeys }
        // Clean up keys that the service has now confirmed removed
        if (list != null) {
            val liveKeys = list.map { it.key }.toSet()
            pendingDismissKeys.retainAll(liveKeys)
        }
        super.submitList(filtered)
    }

    /** Attach swipe-to-dismiss (right swipe) to the RecyclerView. Persistent notifications are blocked. */
    fun attachSwipeToDismiss(rv: RecyclerView) {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_ID.toInt()) return 0
                val sbn = try { getItem(pos) } catch (e: Exception) { return 0 }
                val flags = sbn.notification.flags
                val isPersistent = (flags and (android.app.Notification.FLAG_ONGOING_EVENT or
                        android.app.Notification.FLAG_FOREGROUND_SERVICE)) != 0
                return if (isPersistent) 0 else super.getSwipeDirs(rv, vh)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_ID.toInt()) return
                val sbn = getItem(pos)
                pendingDismissKeys.add(sbn.key)
                expanded.remove(sbn.key)
                // Re-submit current list minus the dismissed key for immediate visual removal
                super@NotificationListAdapter.submitList(currentList.filter { it.key !in pendingDismissKeys })
                CloXNotificationListenerService.dismissNotification(sbn.key)
            }
        }).attachToRecyclerView(rv)
    }

    companion object {
        val CONTENT_CHANGED = Any()   // non-null payload → in-place bind, no animation

        fun hasOpenAction(sbn: StatusBarNotification) = sbn.notification.contentIntent != null

        fun openNotification(sbn: StatusBarNotification, ctx: android.content.Context) {
            val intent = sbn.notification.contentIntent ?: return
            try { intent.send(ctx, 0, null) } catch (e: Exception) { return }
            if ((sbn.notification.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0) {
                CloXNotificationListenerService.dismissNotification(sbn.key)
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<StatusBarNotification>() {
            override fun areItemsTheSame(a: StatusBarNotification, b: StatusBarNotification) =
                a.key == b.key

            override fun areContentsTheSame(a: StatusBarNotification, b: StatusBarNotification): Boolean {
                val ae = a.notification.extras; val be = b.notification.extras
                return ae.getCharSequence(Notification.EXTRA_TITLE) == be.getCharSequence(Notification.EXTRA_TITLE) &&
                       ae.getCharSequence(Notification.EXTRA_TEXT)  == be.getCharSequence(Notification.EXTRA_TEXT) &&
                       ae.getCharSequence(Notification.EXTRA_BIG_TEXT) == be.getCharSequence(Notification.EXTRA_BIG_TEXT)
            }

            // Non-null payload tells RecyclerView to call onBindViewHolder(payloads) → no fade/blink
            override fun getChangePayload(a: StatusBarNotification, b: StatusBarNotification): Any = CONTENT_CHANGED
        }
    }
}
