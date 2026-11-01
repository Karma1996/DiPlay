package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.autoconnect.AutoConnectConnector
import com.shilapi.xcertplay.autoconnect.AutoConnectDecision
import com.shilapi.xcertplay.autoconnect.AutoConnectDiagnostics
import com.shilapi.xcertplay.autoconnect.AutoConnectEnvironment
import com.shilapi.xcertplay.autoconnect.AutoConnectFailure
import com.shilapi.xcertplay.autoconnect.AutoConnectOptIn
import com.shilapi.xcertplay.autoconnect.AutoConnectPreferences
import com.shilapi.xcertplay.autoconnect.AutoConnectRequest
import com.shilapi.xcertplay.autoconnect.AutoConnectSession
import com.shilapi.xcertplay.autoconnect.AutoConnectState
import com.shilapi.xcertplay.autoconnect.AutoConnectTrigger
import com.shilapi.xcertplay.autoconnect.BondedIphone
import com.shilapi.xcertplay.autoconnect.CancelReason
import com.shilapi.xcertplay.autoconnect.HotspotState
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TC-020…TC-032, TC-043…TC-045: preferences, opt-in, the session state machine and diagnostics.
 * The process singleton is reset in @Before/@After (G-08).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class AutoConnectSessionTest {
    private lateinit var app: Context
    private lateinit var connector: RecordingConnector

    private class RecordingConnector : AutoConnectConnector {
        val starts = mutableListOf<AutoConnectRequest>()
        val cancels = mutableListOf<Long>()
        override fun start(request: AutoConnectRequest): Boolean {
            starts += request
            return true
        }

        override fun cancelSession(sessionId: Long) {
            cancels += sessionId
        }
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay", "diplay_auto_connect_diag", "diplay_last_problem", "xcertplay_airplay")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AutoConnectPreferences.resetForTest(app)
        AutoConnectDiagnostics.resetForTest(app)
        AutoConnectSession.resetForTest()
        connector = RecordingConnector()
        AutoConnectSession.initialize(app, connector)
    }

    @After fun tearDown() {
        AutoConnectSession.resetForTest()
    }

    private fun environment(
        trigger: AutoConnectTrigger = AutoConnectTrigger.APP_OPENED,
        active: Boolean = AutoConnectSession.state() is AutoConnectState.Running,
    ) = AutoConnectEnvironment(
        masterEnabled = AutoConnectPreferences.masterEnabled(app),
        openOnLaunch = DiPlayPreferences.autoConnect(app),
        connectOnPhoneBluetooth = DiPlayPreferences.connectOnPhoneBluetooth(app),
        autoStartOnBoot = true,
        wirelessMode = WirelessHotspotMode.MANUAL,
        preferredAddress = DiPlayPreferences.phoneAddress(app),
        preferredLastSuccessAt = null,
        bondedIphones = listOfNotNull(DiPlayPreferences.phoneAddress(app)?.let { BondedIphone(it) }),
        connectedIphoneCount = 1,
        bluetoothPermission = true,
        overlayPermission = true,
        writeSettingsOrAdbHotspot = true,
        hotspotState = HotspotState.ON,
        sessionActive = active,
        nowElapsedMillis = 0L,
    )

    private fun savePreferred() = DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:01", "iPhone")

    /** Turns on the gate inputs the session tests need; migration tests write their own state. */
    private fun enableAutomation() {
        savePreferred()
        AutoConnectPreferences.ensureMigrated(app)
        AutoConnectPreferences.setMaster(app, true, byUser = true)
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
    }

    // --- migration and opt-in -------------------------------------------------------------

    @Test fun migratesFourUpgradeStates() {
        savePreferred()
        DiPlayPreferences.saveAutoConnect(app, true)
        AutoConnectPreferences.ensureMigrated(app)
        assertTrue(AutoConnectPreferences.masterEnabled(app))
        assertEquals(AutoConnectOptIn.ENABLED_USER, AutoConnectPreferences.optIn(app))

        // Idempotent: a second migration must not rewrite the state.
        AutoConnectPreferences.ensureMigrated(app)
        assertTrue(AutoConnectPreferences.masterEnabled(app))
        assertEquals(AutoConnectOptIn.ENABLED_USER, AutoConnectPreferences.optIn(app))

        // Preferred iPhone, no automation: keep the driver's behaviour exactly as it was.
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        savePreferred()
        AutoConnectPreferences.ensureMigrated(app)
        assertFalse(AutoConnectPreferences.masterEnabled(app))
        assertEquals(AutoConnectOptIn.NEVER_ASKED, AutoConnectPreferences.optIn(app))

        // No preferred iPhone.
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        AutoConnectPreferences.ensureMigrated(app)
        assertFalse(AutoConnectPreferences.masterEnabled(app))
        assertEquals(AutoConnectOptIn.NEVER_ASKED, AutoConnectPreferences.optIn(app))
    }

    @Test fun optInHappensOnce() {
        savePreferred()
        AutoConnectPreferences.ensureMigrated(app)
        assertTrue(AutoConnectPreferences.recordSuccess(app, WirelessHotspotMode.MANUAL, 1_000L))
        assertTrue(AutoConnectPreferences.masterEnabled(app))
        assertEquals(AutoConnectOptIn.ENABLED_AUTO, AutoConnectPreferences.optIn(app))
        assertFalse("a second success must not opt in again",
            AutoConnectPreferences.recordSuccess(app, WirelessHotspotMode.MANUAL, 2_000L))
        assertEquals(AutoConnectOptIn.ENABLED_AUTO, AutoConnectPreferences.optIn(app))
    }

    @Test fun disabledUserNeverAutoReenables() {
        savePreferred()
        AutoConnectPreferences.ensureMigrated(app)
        AutoConnectPreferences.setMaster(app, false, byUser = true)
        assertEquals(AutoConnectOptIn.DISABLED_USER, AutoConnectPreferences.optIn(app))
        AutoConnectPreferences.recordSuccess(app, WirelessHotspotMode.MANUAL, 3_000L)
        assertFalse(AutoConnectPreferences.masterEnabled(app))
        assertEquals(AutoConnectOptIn.DISABLED_USER, AutoConnectPreferences.optIn(app))
    }

    @Test fun successPersistsPreferredFacts() {
        savePreferred()
        AutoConnectPreferences.recordSuccess(app, WirelessHotspotMode.WIFI_P2P, 42L)
        val preferred = AutoConnectPreferences.preferred(app)!!
        assertEquals("AA:BB:CC:DD:EE:01", preferred.address)
        assertEquals("iPhone", preferred.name)
        assertEquals(WirelessHotspotMode.WIFI_P2P, preferred.lastMode)
        assertEquals(42L, preferred.lastSuccessAt)
    }

    @Test fun failureDoesNotRecordSuccess() {
        enableAutomation()
        AutoConnectSession.offer(AutoConnectTrigger.APP_OPENED, environment())
        val id = connector.starts.single().sessionId
        AutoConnectSession.onFailure(id, AutoConnectFailure.CAR_HOTSPOT_OFF)
        val preferred = AutoConnectPreferences.preferred(app)!!
        assertNull(preferred.lastMode)
        assertNull(preferred.lastSuccessAt)
    }

    @Test fun newKeysHoldNoCredentials() {
        savePreferred()
        AutoConnectPreferences.ensureMigrated(app)
        AutoConnectPreferences.recordSuccess(app, WirelessHotspotMode.MANUAL, 5L)
        val keys = app.getSharedPreferences("diplay", Context.MODE_PRIVATE).all.keys
            .filter { it in AutoConnectPreferences.newKeys }.toSet()
        assertTrue(AutoConnectPreferences.newKeys.containsAll(keys))
        val credentials = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).all.keys
        assertTrue("automatic keys must not overlap credential keys", keys.intersect(credentials).isEmpty())
    }

    // --- session state machine -------------------------------------------------------------

    @Test fun fiveOffersMakeOneSession() {
        enableAutomation()
        val ids = mutableSetOf<Long>()
        repeat(5) {
            val decision = AutoConnectSession.offer(AutoConnectTrigger.APP_OPENED, environment())
            if (decision is AutoConnectDecision.Start) ids += decision.request.sessionId
        }
        assertEquals(1, connector.starts.size)
        assertEquals(1, ids.size)
    }

    @Test fun runningIgnoresNewOffer() {
        enableAutomation()
        AutoConnectSession.offer(AutoConnectTrigger.APP_OPENED, environment())
        val decision = AutoConnectSession.offer(AutoConnectTrigger.APP_OPENED, environment())
        assertEquals(AutoConnectDecision.Ignore(com.shilapi.xcertplay.autoconnect.IgnoreReason.RUNNING), decision)
        assertEquals(1, connector.starts.size)
    }

    @Test fun duplicateCallbacksApplyOnce() {
        enableAutomation()
        AutoConnectSession.offer(AutoConnectTrigger.APP_OPENED, environment())
        val id = connector.starts.single().sessionId
        AutoConnectSession.onConnected(id, WirelessHotspotMode.MANUAL)
        val afterSuccess = AutoConnectSession.state()
        AutoConnectSession.onConnected(id, WirelessHotspotMode.MANUAL)
        AutoConnectSession.onFailure(id, AutoConnectFailure.FIRST_TCP_TIMEOUT)
        assertEquals(afterSuccess, AutoConnectSession.state())
        assertNull("a finished session cannot be asked for another attempt", AutoConnectSession.attemptPermit(id))
    }

    @Test fun cancelIsIdempotentAndSilent() {
        enableAutomation()
        AutoConnectSession.offer(AutoConnectTrigger.APP_OPENED, environment())
        val id = connector.starts.single().sessionId
        val states = mutableListOf<AutoConnectState>()
        val listener: (AutoConnectState) -> Unit = { states += it }
        AutoConnectSession.addListener(listener)
        AutoConnectSession.cancel(CancelReason.OFF)
        AutoConnectSession.cancel(CancelReason.USER_CANCELLED)
        assertEquals(AutoConnectState.Cancelled(CancelReason.OFF), AutoConnectSession.state())
        assertEquals(listOf(AutoConnectState.Cancelled(CancelReason.OFF)), states)
        assertEquals(listOf(id), connector.cancels)
        AutoConnectSession.removeListener(listener)
    }

    @Test fun retryStartsFreshSession() {
        enableAutomation()
        AutoConnectSession.offer(AutoConnectTrigger.APP_OPENED, environment())
        val first = connector.starts.single().sessionId
        AutoConnectSession.onFailure(first, AutoConnectFailure.CAR_HOTSPOT_OFF)
        assertTrue(AutoConnectSession.state() is AutoConnectState.Failed)
        val decision = AutoConnectSession.retryFromUi()
        assertTrue(decision is AutoConnectDecision.Start)
        assertEquals(2, connector.starts.size)
        assertNotEquals("a retry is a new session", first, connector.starts.last().sessionId)
    }

    // --- diagnostics -----------------------------------------------------------------------

    @Test fun diagnosticsHasEightFixedFields() {
        enableAutomation()
        AutoConnectSession.offer(AutoConnectTrigger.APP_OPENED, environment())
        val id = connector.starts.single().sessionId
        // A non-retryable failure reaches the terminal state and writes the diagnostic record.
        AutoConnectSession.onFailure(id, AutoConnectFailure.CAR_HOTSPOT_OFF)
        val line = AutoConnectDiagnostics.report(app)
        val expected = listOf(
            "AutoConnect: v=1",
            "trigger=", "backend=", "stage=", "attempts=", "elapsedMs=", "outcome=", "failure=", "tier=",
        )
        for (part in expected) assertTrue("missing $part in $line", line.contains(part))
        val order = listOf("trigger=", "backend=", "stage=", "attempts=", "elapsedMs=", "outcome=", "failure=", "tier=")
            .map { line.indexOf(it) }
        assertEquals("fields keep their fixed order", order.sorted(), order)
        assertTrue(line.contains("outcome=FAILED"))
    }

    @Test fun diagnosticsLeaksNoIdentifiers() {
        enableAutomation()
        AutoConnectSession.offer(AutoConnectTrigger.PHONE_BLUETOOTH, environment())
        val id = connector.starts.single().sessionId
        AutoConnectSession.onFailure(id, AutoConnectFailure.CAR_HOTSPOT_OFF)
        val line = AutoConnectDiagnostics.report(app)
        assertFalse(line.contains("AA:BB:CC:DD:EE:01"))
        assertFalse(line.contains("iPhone"))
        assertFalse(Regex("""(?i)(ssid|pass|\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b)""").containsMatchIn(line))
    }
}
