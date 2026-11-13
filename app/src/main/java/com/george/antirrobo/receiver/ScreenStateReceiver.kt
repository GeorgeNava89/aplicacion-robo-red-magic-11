package com.george.antirrobo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.george.antirrobo.util.EventLog

/**
 * Recibe SCREEN_OFF / SCREEN_ON. Estos broadcasts no se pueden declarar
 * en el manifiesto (a partir de Android 8); hay que registrarlos en runtime,
 * desde el FGS y/o el AccessibilityService.
 */
class ScreenStateReceiver(
    private val onAction: (String) -> Unit,
) : BroadcastReceiver() {

    @Volatile
    private var registered = false

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        EventLog.i(TAG, "broadcast $action")
        onAction(action)
    }

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
        registered = true
    }

    fun unregister(context: Context) {
        if (!registered) return
        try {
            context.unregisterReceiver(this)
        } catch (_: Throwable) {
        }
        registered = false
    }

    companion object {
        private const val TAG = "ScreenRx"
    }
}
