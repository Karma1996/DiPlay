package com.shilapi.xcertplay.autoconnect

/**
 * The four automatic triggers of FR-002 plus the user's own retry tap.
 *
 * [RETRY_BUTTON] is a user action, not an automatic trigger: it starts a fresh session with a
 * fresh budget and is not subject to the automatic de-duplication windows (design §7.2 T-5).
 */
internal enum class AutoConnectTrigger {
    COLD_START,
    APP_OPENED,
    PHONE_BLUETOOTH,
    SLEEP_WAKE,
    RETRY_BUTTON,
    ;

    val automatic: Boolean get() = this != RETRY_BUTTON

    companion object {
        /** Exactly the four automatic triggers; [RETRY_BUTTON] is excluded by contract. */
        val automaticTriggers: List<AutoConnectTrigger> = entries.filter { it.automatic }
    }
}

/** The four capability tiers of design §12.2. Diagnostic only; never persisted. */
internal enum class AutoConnectTier { A, B, C, D }

/** How long the same trigger may not repeat itself (design §7.3 D-3). */
internal object AutoConnectDedupe {
    const val APP_OPENED_MILLIS = 3_000L
    const val PHONE_BLUETOOTH_MILLIS = 15_000L
    const val SLEEP_WAKE_MILLIS = 60_000L

    fun windowMillis(trigger: AutoConnectTrigger): Long? = when (trigger) {
        AutoConnectTrigger.APP_OPENED -> APP_OPENED_MILLIS
        AutoConnectTrigger.PHONE_BLUETOOTH -> PHONE_BLUETOOTH_MILLIS
        AutoConnectTrigger.SLEEP_WAKE -> SLEEP_WAKE_MILLIS
        // A boot broadcast happens once by itself, and a retry tap is a deliberate user action.
        AutoConnectTrigger.COLD_START, AutoConnectTrigger.RETRY_BUTTON -> null
    }
}
