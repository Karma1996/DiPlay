package com.shilapi.xcertplay.autoconnect

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-001…TC-008: the automatic decision point (FR-002/004/008/012/013). */
class AutoConnectGateTest {

    private fun environment(
        master: Boolean = true,
        openOnLaunch: Boolean = true,
        bluetooth: Boolean = true,
        boot: Boolean = true,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
        preferred: String? = "AA:BB:CC:DD:EE:01",
        bonded: List<BondedIphone> = listOf(BondedIphone("AA:BB:CC:DD:EE:01", "iPhone")),
        bluetoothPermission: Boolean = true,
        overlay: Boolean = true,
        autoHotspot: Boolean = true,
        hotspot: HotspotState = HotspotState.ON,
        active: Boolean = false,
        now: Long = 0L,
        lastTrigger: AutoConnectTrigger? = null,
        lastAt: Long? = null,
    ) = AutoConnectEnvironment(
        masterEnabled = master,
        openOnLaunch = openOnLaunch,
        connectOnPhoneBluetooth = bluetooth,
        autoStartOnBoot = boot,
        wirelessMode = mode,
        preferredAddress = preferred,
        preferredLastSuccessAt = null,
        bondedIphones = bonded,
        connectedIphoneCount = bonded.size,
        bluetoothPermission = bluetoothPermission,
        overlayPermission = overlay,
        writeSettingsOrAdbHotspot = autoHotspot,
        hotspotState = hotspot,
        sessionActive = active,
        nowElapsedMillis = now,
        lastTriggerAtElapsedMillis = lastAt,
        lastTrigger = lastTrigger,
    )

    @Test fun fourAutomaticTriggersAndRetryIsUserAction() {
        assertEquals(
            listOf(
                AutoConnectTrigger.COLD_START,
                AutoConnectTrigger.APP_OPENED,
                AutoConnectTrigger.PHONE_BLUETOOTH,
                AutoConnectTrigger.SLEEP_WAKE,
            ),
            AutoConnectTrigger.automaticTriggers,
        )
        for (trigger in AutoConnectTrigger.automaticTriggers + AutoConnectTrigger.RETRY_BUTTON) {
            assertTrue(
                "$trigger must start with every switch on",
                AutoConnectGate.evaluate(environment(), trigger) is AutoConnectDecision.Start,
            )
        }
        assertEquals(4, AutoConnectTrigger.automaticTriggers.size)
        assertTrue(AutoConnectTrigger.RETRY_BUTTON !in AutoConnectTrigger.automaticTriggers)
    }

    @Test fun masterOffIgnoresAllTriggers() {
        for (trigger in AutoConnectTrigger.entries) {
            val decision = AutoConnectGate.evaluate(environment(master = false), trigger)
            assertEquals(
                "master off must ignore $trigger",
                AutoConnectDecision.Ignore(IgnoreReason.OFF),
                decision,
            )
        }
    }

    @Test fun noPreferredNeedsChoiceNeverSelects() {
        for (bonded in listOf(listOf(BondedIphone("AA:BB:CC:DD:EE:01")), listOf(
            BondedIphone("AA:BB:CC:DD:EE:01"), BondedIphone("AA:BB:CC:DD:EE:02"),
        ))) {
            val decision = AutoConnectGate.evaluate(environment(preferred = null, bonded = bonded), AutoConnectTrigger.APP_OPENED)
            assertTrue(decision is AutoConnectDecision.NeedsChoice)
            assertTrue((decision as AutoConnectDecision.NeedsChoice).candidates == bonded.size)
        }
    }

    @Test fun invalidPreferredNeverFallsBack() {
        val env = environment(
            preferred = "AA:BB:CC:DD:EE:99",
            bonded = listOf(BondedIphone("AA:BB:CC:DD:EE:01"), BondedIphone("AA:BB:CC:DD:EE:02")),
        )
        assertEquals(TargetResolution.PREFERRED_INVALID, AutoConnectGate.resolveTarget(env))
        val decision = AutoConnectGate.evaluate(env, AutoConnectTrigger.PHONE_BLUETOOTH)
        assertTrue(decision is AutoConnectDecision.NeedsChoice)
        assertEquals(2, (decision as AutoConnectDecision.NeedsChoice).candidates)
    }

    @Test fun multiCandidateNeedsChoice() {
        val env = environment(
            preferred = null,
            bonded = listOf(BondedIphone("AA:BB:CC:DD:EE:01"), BondedIphone("AA:BB:CC:DD:EE:02")),
        )
        val decision = AutoConnectGate.evaluate(env, AutoConnectTrigger.APP_OPENED)
        assertEquals(AutoConnectDecision.NeedsChoice(ChoiceReason.MULTIPLE_CANDIDATES, 2), decision)
    }

    @Test fun tierMatrix() {
        // A: hotspot can be opened automatically and background starts are allowed.
        assertEquals(AutoConnectTier.A, AutoConnectGate.tier(environment(autoHotspot = true, overlay = true)))
        // B: no automatic hotspot, but background start is allowed.
        assertEquals(AutoConnectTier.B, AutoConnectGate.tier(environment(autoHotspot = false, overlay = true)))
        // C: no overlay permission, still usable when the driver opens DiPlay.
        assertEquals(AutoConnectTier.C, AutoConnectGate.tier(environment(autoHotspot = false, overlay = false)))
        // D: no Bluetooth permission at all.
        assertEquals(AutoConnectTier.D, AutoConnectGate.tier(environment(bluetoothPermission = false)))

        val tierC = environment(autoHotspot = false, overlay = false)
        assertTrue(AutoConnectGate.evaluate(tierC, AutoConnectTrigger.APP_OPENED) is AutoConnectDecision.Start)

        val decision = AutoConnectGate.evaluate(environment(bluetoothPermission = false), AutoConnectTrigger.APP_OPENED)
        assertEquals(AutoConnectDecision.Blocked(BlockReason.BLUETOOTH_PERMISSION), decision)
    }

    @Test fun dedupeWindows() {
        val cases = mapOf(
            AutoConnectTrigger.APP_OPENED to 3_000L,
            AutoConnectTrigger.PHONE_BLUETOOTH to 15_000L,
            AutoConnectTrigger.SLEEP_WAKE to 60_000L,
        )
        for ((trigger, window) in cases) {
            val inside = environment(now = window - 1, lastTrigger = trigger, lastAt = 0L)
            assertEquals(
                "$trigger inside its window",
                AutoConnectDecision.Ignore(IgnoreReason.DUPLICATE),
                AutoConnectGate.evaluate(inside, trigger),
            )
            val outside = environment(now = window, lastTrigger = trigger, lastAt = 0L)
            assertTrue(
                "$trigger at the edge of its window",
                AutoConnectGate.evaluate(outside, trigger) is AutoConnectDecision.Start,
            )
        }
    }

    @Test fun firstPairAndLaterReconnectAreExclusive() {
        assertEquals(TargetResolution.PREFERRED, AutoConnectGate.resolveTarget(environment()))
        assertEquals(TargetResolution.NO_PREFERRED, AutoConnectGate.resolveTarget(environment(preferred = null)))
        assertTrue(
            AutoConnectGate.evaluate(environment(preferred = null), AutoConnectTrigger.APP_OPENED)
                is AutoConnectDecision.NeedsChoice,
        )
    }

    @Test fun runningSessionIgnoresNewOffers() {
        assertEquals(
            AutoConnectDecision.Ignore(IgnoreReason.RUNNING),
            AutoConnectGate.evaluate(environment(active = true), AutoConnectTrigger.APP_OPENED),
        )
    }
}
