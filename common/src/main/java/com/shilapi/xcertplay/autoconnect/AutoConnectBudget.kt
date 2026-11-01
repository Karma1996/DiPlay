package com.shilapi.xcertplay.autoconnect

/**
 * The automatic session's retry budget (design §6.2, ADR-003).
 *
 * At most [MAX_ATTEMPTS] attempts inside a hard [SESSION_DEADLINE_MILLIS] window, with a
 * 2-4-8-16 s backoff and no new attempt when less than [MIN_ATTEMPT_WINDOW_MILLIS] remains.
 *
 * G-07: [attemptPermit] is the only place that consumes an attempt. There is deliberately no
 * second counter, so the host and the session cannot drift apart (R-010).
 */
internal class AutoConnectBudget(
    private val nowElapsed: () -> Long,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val deadlineMillis: Long = SESSION_DEADLINE_MILLIS,
) {
    private var started = false
    private var startedAt = 0L
    private var attempts = 0
    private var cancelled: CancelReason? = null

    val attemptCount: Int get() = attempts

    /** Opens the budget. Returns the total window; a session may only begin once. */
    fun begin(): Long? {
        if (started) return null
        started = true
        startedAt = nowElapsed()
        attempts = 0
        cancelled = null
        return deadlineMillis
    }

    /**
     * Consumes one attempt and returns the window available for it, or null when the session must
     * stop. This is the single counting point (G-07).
     */
    fun attemptPermit(): Long? {
        if (!started || cancelled != null) return null
        if (attempts >= maxAttempts) return null
        val remaining = remainingMillis() ?: return null
        if (remaining < MIN_ATTEMPT_WINDOW_MILLIS) return null
        attempts += 1
        return remaining
    }

    /** 2 s after the first attempt, 4 s after the second, and so on; null when no retry is left. */
    fun backoffMillis(): Long? {
        if (!started || cancelled != null) return null
        if (attempts >= maxAttempts) return null
        val remaining = remainingMillis() ?: return null
        val index = (attempts - 1).coerceIn(0, BACKOFF_MILLIS.lastIndex)
        val backoff = BACKOFF_MILLIS[index]
        return if (remaining - backoff < MIN_ATTEMPT_WINDOW_MILLIS) null else backoff
    }

    fun remainingMillis(): Long? {
        if (!started) return null
        val elapsed = nowElapsed() - startedAt
        if (elapsed >= deadlineMillis) return null
        return deadlineMillis - elapsed
    }

    fun elapsedMillis(): Long = if (!started) 0L else (nowElapsed() - startedAt).coerceAtLeast(0L)

    fun cancel(reason: CancelReason) {
        if (cancelled != null) return
        cancelled = reason
    }

    fun cancelledReason(): CancelReason? = cancelled

    /** Terminal outcome for the given failure. A null failure means success. */
    fun outcome(failure: AutoConnectFailure?): AutoConnectOutcome {
        if (cancelled != null) return AutoConnectOutcome.CANCELLED
        return if (failure == null) AutoConnectOutcome.SUCCEEDED else AutoConnectOutcome.FAILED
    }

    /** Read-only probe used by callers that only want the terminal decision, never counting. */
    fun canRetry(): Boolean {
        if (!started || cancelled != null || attempts >= maxAttempts) return false
        val remaining = remainingMillis() ?: return false
        return remaining >= MIN_ATTEMPT_WINDOW_MILLIS
    }

    /** Attempts to show to the driver: an immediate non-retryable failure still says "1". */
    fun displayedAttempts(): Int = attempts.coerceAtLeast(1)

    companion object {
        const val MAX_ATTEMPTS = 5
        const val SESSION_DEADLINE_MILLIS = 150_000L
        const val MIN_ATTEMPT_WINDOW_MILLIS = 20_000L
        val BACKOFF_MILLIS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L)
    }
}
