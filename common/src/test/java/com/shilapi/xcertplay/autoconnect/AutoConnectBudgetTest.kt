package com.shilapi.xcertplay.autoconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-009…TC-014: the 5-attempt / 150-second budget (FR-006, NFR-002, ADR-003). */
class AutoConnectBudgetTest {
    private var now = 0L
    private fun budget() = AutoConnectBudget({ now })

    @Test fun sixthPermitIsNull() {
        val budget = budget()
        assertNotNull(budget.begin())
        val permits = (1..6).map { budget.attemptPermit() }
        assertTrue("first five attempts must be permitted", permits.take(5).all { it != null })
        assertNull("the sixth attempt must be refused", permits[5])
        assertEquals(5, budget.attemptCount)
    }

    @Test fun deadlineIsHard() {
        // The hard deadline and the 20 s minimum window are two different guards: at 149 999 ms
        // less than 20 s remains, so no new attempt may start even before the deadline itself.
        now = 0
        val nearlyOut = budget()
        nearlyOut.begin()
        now = 149_999
        assertNull("less than 20 s left means no new attempt", nearlyOut.attemptPermit())
        now = 0
        val exactlyWindow = budget()
        exactlyWindow.begin()
        now = 130_000
        assertNotNull("exactly 20 s left still allows the last attempt", exactlyWindow.attemptPermit())
        now = 0
        val atDeadline = budget()
        atDeadline.begin()
        now = 150_000
        assertNull("150 000 ms is the hard deadline", atDeadline.attemptPermit())
        now = 0
        val afterDeadline = budget()
        afterDeadline.begin()
        now = 150_001
        assertNull(afterDeadline.attemptPermit())
        repeat(10) {
            now += 1_000
            assertNull(afterDeadline.attemptPermit())
        }
    }

    @Test fun backoffSequence() {
        val budget = budget()
        budget.begin()
        val observed = mutableListOf<Long>()
        repeat(4) {
            assertNotNull(budget.attemptPermit())
            observed += budget.backoffMillis() ?: error("backoff must be available inside the budget")
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), observed)
        for (value in observed) assertTrue(value >= 2_000L)
    }

    @Test fun nonRetryableDoesNotConsumeBudget() {
        val budget = budget()
        budget.begin()
        assertNotNull(budget.attemptPermit())
        assertEquals(AutoConnectOutcome.FAILED, budget.outcome(AutoConnectFailure.CAR_HOTSPOT_OFF))
        // A non-retryable failure ends the session; the budget object itself is never asked again.
        assertEquals(1, budget.displayedAttempts())
    }

    @Test fun slowFailureStaysUnderDeadline() {
        val budget = budget()
        budget.begin()
        var attempts = 0
        while (true) {
            val permit = budget.attemptPermit() ?: break
            attempts += 1
            assertTrue(permit > 0)
            now += 45_000
        }
        assertTrue("at most three 45 s attempts", attempts <= 3)
        assertTrue("total time stays below the 150 s deadline", now <= 150_000)
    }

    @Test fun fastFailureUsesFiveAttempts() {
        val budget = budget()
        budget.begin()
        var attempts = 0
        var elapsed = 0L
        while (true) {
            budget.attemptPermit() ?: break
            attempts += 1
            now += 5_000
            elapsed += 5_000
            val backoff = budget.backoffMillis() ?: break
            now += backoff
            elapsed += backoff
        }
        assertEquals(5, attempts)
        assertTrue("fast failures finish within 55 s", elapsed <= 55_000)
    }

    @Test fun cancelIsIdempotentAndSilent() {
        val budget = budget()
        budget.begin()
        budget.attemptPermit()
        budget.cancel(CancelReason.OFF)
        budget.cancel(CancelReason.USER_CANCELLED)
        assertEquals(CancelReason.OFF, budget.cancelledReason())
        assertNull(budget.attemptPermit())
        assertEquals(AutoConnectOutcome.CANCELLED, budget.outcome(AutoConnectFailure.FIRST_TCP_TIMEOUT))
    }

    @Test fun retryableFailureConsumesTheAttemptPermit() {
        val budget = budget()
        budget.begin()
        budget.attemptPermit()
        assertEquals(1, budget.attemptCount)
        assertTrue(budget.canRetry())
        // G-07: the host's permit call is the only counting point.
        budget.attemptPermit()
        assertEquals(2, budget.attemptCount)
    }
}
