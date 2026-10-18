package com.yzmm.clox.cocos.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives data writes from third-party apps via broadcast.
 *
 * Action : com.yzmm.clox.WRITE_DATA
 * Extras :
 *   slot (int)    — target slot, 1–10
 *   data (String) — payload string to store
 *   key  (String) — optional label / identifier for this slot
 *
 * On receipt the slot is persisted to SharedPreferences and
 * ExternalDataStore.slotUpdated emits so WatchfaceBridgeManager can
 * immediately push the updated thirdParty category to Lua.
 */
class ExternalDataReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_WRITE) return

        val slot = intent.getIntExtra(EXTRA_SLOT, 0)
        if (slot < 1 || slot > ExternalDataStore.SLOT_COUNT) {
            Log.w(TAG, "Rejected write: slot=$slot out of range 1–${ExternalDataStore.SLOT_COUNT}")
            return
        }

        val data = intent.getStringExtra(EXTRA_DATA) ?: run {
            Log.w(TAG, "Rejected write: missing 'data' extra")
            return
        }
        val key = intent.getStringExtra(EXTRA_KEY) ?: ""

        ExternalDataStore.write(ctx, slot, data, key)
        Log.d(TAG, "Slot $slot written — key=$key data=${data.take(40)}")
    }

    companion object {
        const val ACTION_WRITE = "com.yzmm.clox.WRITE_DATA"
        const val EXTRA_SLOT   = "slot"
        const val EXTRA_DATA   = "data"
        const val EXTRA_KEY    = "key"
        private const val TAG  = "ExternalDataReceiver"
    }
}
