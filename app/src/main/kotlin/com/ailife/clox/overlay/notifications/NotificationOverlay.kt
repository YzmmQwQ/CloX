package com.ailife.clox.overlay.notifications

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.ailife.clox.R
import com.ailife.clox.overlay.quicksettings.VolumeDialogFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class NotificationOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val permissionScreen: LinearLayout
    private val permTitle: TextView
    private val permMessage: TextView
    private val btnGrant: Button
    private val btnTryAnyway: Button

    private val contentScreen: FrameLayout
    private val notifList: RecyclerView
    private val notifPager: ViewPager2
    private val emptyText: TextView
    private val fabDismissAll: FloatingActionButton

    private val mediaAdapter: MediaSingleAdapter
    private val listAdapter: NotificationListAdapter
    private val pageAdapter: NotificationPageAdapter
    private val concatAdapter: ConcatAdapter

    private var fragmentManager: FragmentManager? = null

    init {
        LayoutInflater.from(ctx).inflate(R.layout.overlay_notifications, this, true)

        permissionScreen = findViewById(R.id.notifPermissionScreen)
        permTitle        = findViewById(R.id.notifPermTitle)
        permMessage      = findViewById(R.id.notifPermMessage)
        btnGrant         = findViewById(R.id.btnNotifPermGrant)
        btnTryAnyway     = findViewById(R.id.btnNotifPermTryAnyway)

        contentScreen  = findViewById(R.id.notifContentScreen)
        notifList      = findViewById(R.id.notifList)
        notifPager     = findViewById(R.id.notifPager)
        emptyText      = findViewById(R.id.notifEmptyText)
        fabDismissAll  = findViewById(R.id.fabDismissAll)

        mediaAdapter = MediaSingleAdapter { showVolumeDialog() }
        listAdapter  = NotificationListAdapter { sbn -> ReplyPopup.show(ctx, sbn) }
        pageAdapter  = NotificationPageAdapter { sbn -> ReplyPopup.show(ctx, sbn) }
        concatAdapter = ConcatAdapter(mediaAdapter, listAdapter)

        notifList.layoutManager = LinearLayoutManager(ctx)
        notifList.adapter = concatAdapter
        listAdapter.attachSwipeToDismiss(notifList)

        notifPager.adapter = pageAdapter

        fabDismissAll.setOnClickListener {
            CloXNotificationListenerService.dismissAllNotifications()
        }

        btnGrant.setOnClickListener { openNotificationListenerSettings() }
        btnTryAnyway.setOnClickListener { openNotificationListenerSettings() }

        PreferenceManager.getDefaultSharedPreferences(ctx)
            .registerOnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    NotificationPrefs.NOTIF_TEXT_SIZE -> {
                        listAdapter.notifyDataSetChanged()
                        pageAdapter.notifyDataSetChanged()
                    }
                    NotificationPrefs.NOTIF_STYLE -> applyViewStyle()
                    NotificationPrefs.NOTIF_ENABLED -> checkAndShow()
                }
            }
    }

    fun init(fm: FragmentManager) {
        fragmentManager = fm
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        checkAndShow()
    }

    fun onPanelOpened() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(NotificationPrefs.NOTIF_ENABLED, true)) return
        checkAndShow()
        tryFixService()
        // Rebind media session in case it changed
        bindMedia()
    }

    private fun checkAndShow() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(NotificationPrefs.NOTIF_ENABLED, true)) {
            permissionScreen.visibility = View.GONE
            contentScreen.visibility    = View.GONE
            return
        }
        if (!isNotificationListenerEnabled()) {
            showPermissionScreen()
            return
        }
        showContentScreen()
        subscribeToNotifications()
        applyViewStyle()
    }

    private fun showPermissionScreen() {
        permissionScreen.visibility = View.VISIBLE
        contentScreen.visibility    = View.GONE

        val isLowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val isOldApi = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q

        if (isLowRam && isOldApi) {
            permTitle.text   = "Not supported"
            permMessage.text = "Notification access may not be available on this device."
            btnGrant.text    = "View notifications"
            btnGrant.setOnClickListener {
                try {
                    context.startActivity(
                        Intent().setComponent(ComponentName("com.android.settings",
                            "com.android.settings.Settings\$NotificationStationActivity"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { openNotificationListenerSettings() }
            }
            btnTryAnyway.visibility = View.VISIBLE
            btnTryAnyway.setOnClickListener { openNotificationListenerSettings() }
        } else {
            permTitle.text   = "Permission required"
            permMessage.text = "Grant notification access so CloX can display your notifications."
            btnGrant.text    = "Grant permission"
            btnGrant.setOnClickListener { openNotificationListenerSettings() }
            btnTryAnyway.visibility = View.GONE
        }
    }

    private fun showContentScreen() {
        permissionScreen.visibility = View.GONE
        contentScreen.visibility    = View.VISIBLE
        bindMedia()
    }

    private fun bindMedia() {
        val comp = ComponentName(context, CloXNotificationListenerService::class.java)
        mediaAdapter.bind(context, comp)
        pageAdapter.bindMediaSession(context, comp)
    }

    private var subscribed = false
    private fun subscribeToNotifications() {
        if (subscribed) return
        val owner = findViewTreeLifecycleOwner() ?: return
        subscribed = true
        owner.lifecycleScope.launch {
            CloXNotificationListenerService.notifications.collect { notifs ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val grouped = if (prefs.getBoolean(NotificationPrefs.NOTIF_GROUP, false)) {
                    groupNotifications(notifs)
                } else notifs

                listAdapter.submitList(grouped)
                pageAdapter.submitNotifList(grouped)

                // "No notifications" shows only when notification list is empty (player doesn't count)
                emptyText.visibility = if (grouped.isEmpty()) View.VISIBLE else View.GONE

                // FAB only when there's at least one clearable (non-persistent) notification
                val hasClearable = grouped.any { sbn ->
                    (sbn.notification.flags and (android.app.Notification.FLAG_ONGOING_EVENT or
                            android.app.Notification.FLAG_FOREGROUND_SERVICE)) == 0
                }
                fabDismissAll.visibility = if (hasClearable) View.VISIBLE else View.GONE
            }
        }
    }

    private fun applyViewStyle() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val style = prefs.getString(NotificationPrefs.NOTIF_STYLE, NotificationPrefs.STYLE_LIST)
        if (style == NotificationPrefs.STYLE_PAGE) {
            notifList.visibility  = View.GONE
            notifPager.visibility = View.VISIBLE
        } else {
            notifList.visibility  = View.VISIBLE
            notifPager.visibility = View.GONE
        }
    }

    private fun groupNotifications(list: List<android.service.notification.StatusBarNotification>): List<android.service.notification.StatusBarNotification> {
        val messagingTemplate = android.app.Notification.MessagingStyle::class.java.name
        val chat    = list.filter { it.notification.extras.getString(android.app.Notification.EXTRA_TEMPLATE) == messagingTemplate }
        val ongoing = list.filter { it.isOngoing }
        val normal  = list.filter { it !in chat && it !in ongoing }
        return chat + normal + ongoing
    }

    private fun tryFixService() {
        if (!isNotificationListenerEnabled()) return
        if (!CloXNotificationListenerService.isRunning()) {
            CloXNotificationListenerService.requestRebind(context)
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat      = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val component = ComponentName(context, CloXNotificationListenerService::class.java).flattenToString()
        return flat?.contains(component) == true
    }

    private fun openNotificationListenerSettings() {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showVolumeDialog() {
        val fm = fragmentManager ?: return
        VolumeDialogFragment().show(fm, "volume")
    }
}
