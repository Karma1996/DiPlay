package com.shilapi.xcertplay.autoconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sleep/wake trigger (design §7.8, ADR-007).
 *
 * ACTION_SCREEN_ON cannot be delivered to a manifest receiver, and a foreground service or a poll
 * would break the no-new-component / no-polling constraints. So this is a process-local runtime
 * receiver, registered only while the master switch is on and a preferred iPhone exists, holding
 * the application context and carrying no Activity reference.
 */
internal object SleepWakeTrigger {
    const val DEBOUNCE_MILLIS = 60_000L

    private val registered = AtomicBoolean(false)
    private var appContext: Context? = null
    private var listener: (() -> Unit)? = null
    private var lastEventElapsed = 0L
    private var hasLastEvent = false
    private var wakeAttempts = 0

    internal val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON && intent.action != Intent.ACTION_USER_PRESENT) return
            val now = SystemClock.elapsedRealtime()
            if (hasLastEvent && now >= lastEventElapsed && now - lastEventElapsed < DEBOUNCE_MILLIS) return
            lastEventElapsed = now
            hasLastEvent = true
            wakeAttempts += 1
            listener?.invoke()
        }
    }

    /** Idempotent: re-registering while registered only swaps the listener. */
    fun ensureRegistered(context: Context, onWake: () -> Unit) {
        listener = onWake
        val app = context.applicationContext
        appContext = app
        if (!registered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
        }.onFailure {
            registered.set(false)
            appContext = null
        }
    }

    fun unregister(context: Context? = appContext) {
        if (!registered.compareAndSet(true, false)) return
        val app = context ?: return
        runCatching { app.unregisterReceiver(receiver) }
        listener = null
        appContext = null
    }

    fun isRegistered(): Boolean = registered.get()

    fun resetForTest() {
        registered.set(false)
        listener = null
        appContext = null
        lastEventElapsed = 0L
        hasLastEvent = false
        wakeAttempts = 0
    }

    internal fun wakeAttempts(): Int = wakeAttempts
}
