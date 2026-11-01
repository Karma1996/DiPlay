package com.shilapi.xcertplay.autoconnect

import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** TC-015: where the automatic hotspot control renders (FR-011, ADR-009, G-12). */
class AutoHotspotPlacementTest {

    @Test fun routesMatchCapabilities() {
        // BYD with ADB: the existing switch, moved into the automatic card.
        assertEquals(
            AutoHotspotControl.ADB_TOGGLE,
            AutoHotspotPlacement.route(WirelessHotspotMode.MANUAL, true, LocalAdb.Access.READY, true),
        )
        // BYD without ADB approval still shows the opt-in so the driver can ask for it.
        assertEquals(
            AutoHotspotControl.ADB_TOGGLE,
            AutoHotspotPlacement.route(WirelessHotspotMode.MANUAL, true, LocalAdb.Access.NOT_APPROVED, true),
        )
        // BYD without any reachable ADB: no control to offer.
        assertEquals(
            AutoHotspotControl.HIDDEN,
            AutoHotspotPlacement.route(WirelessHotspotMode.MANUAL, true, LocalAdb.Access.UNREACHABLE, true),
        )
        // Another head unit with system tethering support.
        assertEquals(
            AutoHotspotControl.SYSTEM_SETTINGS_TOGGLE,
            AutoHotspotPlacement.route(WirelessHotspotMode.MANUAL, false, LocalAdb.Access.READY, true),
        )
        // Another head unit that cannot start tethering at all: explain instead of offering a dead switch.
        assertEquals(
            AutoHotspotControl.READ_ONLY,
            AutoHotspotPlacement.route(WirelessHotspotMode.MANUAL, false, LocalAdb.Access.READY, false),
        )
    }

    @Test fun nonManualBackendsHideTheControl() {
        for (mode in WirelessHotspotMode.entries.filter { it != WirelessHotspotMode.MANUAL }) {
            assertEquals(
                "mode $mode must hide the automatic hotspot control",
                AutoHotspotControl.HIDDEN,
                AutoHotspotPlacement.route(mode, true, LocalAdb.Access.READY, true),
            )
        }
    }
}
