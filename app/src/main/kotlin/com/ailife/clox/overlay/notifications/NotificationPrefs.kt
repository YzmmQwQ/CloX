package com.ailife.clox.overlay.notifications

object NotificationPrefs {
    const val NOTIF_ENABLED           = "notif_enabled"
    const val NOTIF_STYLE             = "notif_style"          // "list" | "page"
    const val NOTIF_TEXT_SIZE         = "notif_text_size"      // int sp (SeekBarPreference), default 14
    const val NOTIF_MEDIA_ENABLED     = "notif_media_enabled"
    const val NOTIF_WAKE_SCREEN       = "notif_wake_screen"
    const val NOTIF_OPEN_PANEL        = "notif_open_panel"
    const val NOTIF_VIBRATE           = "notif_vibrate"
    const val NOTIF_GROUP             = "notif_group"
    const val NOTIF_BLOCK_PERSISTENT  = "notif_block_persistent"
    const val NOTIF_BLOCKED_APPS      = "notif_blocked_apps"   // JSON array of package names

    const val STYLE_LIST = "list"
    const val STYLE_PAGE = "page"
}
