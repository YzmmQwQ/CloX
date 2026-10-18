package com.yzmm.clox.rtos

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.yzmm.clox.MainActivity

object RtosAccessibilityHelper {

    private const val TAG = "RtosA11y"
    private val handler = Handler(Looper.getMainLooper())

    // Cached via reflection at first use; null = unavailable on this device
    private var statusBarService: Any? = null
    private var collapseMethod: java.lang.reflect.Method? = null
    private var statusBarResolved = false

    private fun tryResolveStatusBarService() {
        if (statusBarResolved) return
        statusBarResolved = true
        runCatching {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "statusbar") ?: return
            val stubClass = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val svc = asInterfaceMethod.invoke(null, binder) ?: return
            val methodName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "collapsePanels" else "animateCollapsePanels"
            collapseMethod = svc.javaClass.getMethod(methodName)
            statusBarService = svc
            Log.d(TAG, "IStatusBarService resolved via reflection, method=$methodName")
        }.onFailure {
            Log.d(TAG, "IStatusBarService not available: ${it.message}")
        }
    }

    fun handleEvent(event: AccessibilityEvent?, service: AccessibilityService) {
        if (!RtosManager.isActive) return
        if (!RtosPrefs.blockApps(service)) {
            Log.d(TAG, "blockApps pref is OFF — skipping")
            return
        }

        val eventType = event?.eventType ?: return
        val pkg = event.packageName?.toString() ?: return

        Log.d(TAG, "event type=0x${eventType.toString(16)} pkg=$pkg className=${event.className} text=${event.text}")

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg == service.packageName) return

        // Always allow the default input method (keyboard) so typing works in RTOS mode.
        if (pkg == defaultImePackage(service)) {
            Log.d(TAG, "default IME pkg=$pkg — allowed")
            return
        }

        val shouldBlock = when {
            RtosManager.isWatchfaceOnly() -> true
            RtosManager.isFullMode() -> {
                if (pkg == "com.android.systemui") true
                else isBlockedInFullMode(pkg, service)
            }
            else -> false
        }

        Log.d(TAG, "shouldBlock=$shouldBlock mode=watchfaceOnly:${RtosManager.isWatchfaceOnly()} fullMode:${RtosManager.isFullMode()}")

        if (!shouldBlock) return

        if (pkg == "com.android.systemui") {
            Log.d(TAG, "systemui detected — collapsing panel")
            collapseSystemPanel(service)
        } else {
            Log.d(TAG, "non-systemui blocked pkg=$pkg — bringing launcher to front")
            bringLauncherToFront(service)
        }
    }

    /** The package of the current default input method (keyboard), or null. */
    private fun defaultImePackage(service: AccessibilityService): String? {
        val v = android.provider.Settings.Secure.getString(
            service.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        return v?.substringBefore('/')?.takeIf { it.isNotEmpty() }
    }

    private fun isBlockedInFullMode(pkg: String, service: AccessibilityService): Boolean {
        val whitelist = RtosPrefs.whitelist(service)
        if (whitelist.isNotEmpty()) {
            // Custom whitelist: block anything not in it
            return !whitelist.any { it.startsWith("$pkg/") }
        }
        // Default: block intent-detected capability apps (browser/camera/settings)
        val pm = service.packageManager
        if (pkg in RtosAppGuard.blockedPackages(pm)) return true
        // Also block non-system apps (user-installed)
        val flags = runCatching { pm.getApplicationInfo(pkg, 0).flags }.getOrDefault(0)
        val isSystem = (flags and (ApplicationInfo.FLAG_SYSTEM or
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        return !isSystem
    }

    // Exact translation of ActionPerformer.java collapse logic:
    // 1. IStatusBarService.collapsePanels() / animateCollapsePanels()
    // 2. if fails → API 31+: performGlobalAction(15) directly
    //              pre-31:  100ms delay → sendBroadcast(CLOSE_SYSTEM_DIALOGS, noRespond=true)
    //                                   → catch → performGlobalAction(BACK)
    /** Public entry point so other subsystems (e.g. global-QS auto-replace) can reuse the
     *  same IStatusBarService + fallback collapse logic. */
    fun collapseSystemShade(service: AccessibilityService) = collapseSystemPanel(service)

    private fun collapseSystemPanel(service: AccessibilityService) {
        tryResolveStatusBarService()
        val svc = statusBarService
        val method = collapseMethod
        if (svc != null && method != null) {
            runCatching {
                method.invoke(svc)
                Log.d(TAG, "IStatusBarService.collapsePanels() succeeded")
                return
            }.onFailure {
                Log.w(TAG, "IStatusBarService failed, using fallback method")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val result = service.performGlobalAction(15)
            Log.d(TAG, "DISMISS_NOTIFICATION_SHADE result=$result")
        } else {
            handler.postDelayed({
                runCatching {
                    @Suppress("DEPRECATION")
                    service.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).apply {
                        putExtra("noRespond", true)
                    })
                    Log.d(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS sent")
                }.onFailure {
                    Log.w(TAG, "broadcast failed, using GLOBAL_ACTION_BACK")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }, 100L)
        }
    }

    private fun bringLauncherToFront(service: AccessibilityService) {
        handler.post {
            runCatching {
                service.startActivity(Intent(service, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                })
                Log.d(TAG, "launcher brought to front")
            }.onFailure { Log.e(TAG, "bringLauncherToFront failed", it) }
        }
    }
}
