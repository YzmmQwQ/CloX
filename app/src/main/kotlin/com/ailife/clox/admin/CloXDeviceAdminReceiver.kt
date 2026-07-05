package com.ailife.clox.admin

import android.app.admin.DeviceAdminReceiver

/**
 * Only exists to satisfy the Device Admin API — force-lock (screen off) is the sole
 * policy used, as a pre-API-28 fallback for [com.ailife.clox.qs.CloXAccessibilityService]'s
 * GLOBAL_ACTION_LOCK_SCREEN, which isn't available until Android P.
 */
class CloXDeviceAdminReceiver : DeviceAdminReceiver()
