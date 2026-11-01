package com.shilapi.xcertplay.autoconnect

import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** The four shapes the "open the car hotspot automatically" control can take (design §11.1). */
internal enum class AutoHotspotControl {
    /** BYD head unit with ADB access: the existing switch, moved into the automatic card. */
    ADB_TOGGLE,

    /** Non-BYD head unit with Android's tethering support: the WRITE_SETTINGS switch. */
    SYSTEM_SETTINGS_TOGGLE,

    /** Explicitly unsupported on this firmware; shown read-only so the driver knows why. */
    READ_ONLY,

    /** Not applicable to this backend or hidden because the car cannot be asked at all. */
    HIDDEN,
}

/**
 * Pure placement policy for the automatic hotspot control (DA-022, G-12).
 *
 * The control has exactly one owner: this function decides where it renders, so the settings card
 * and the ADB card can never both draw it.
 */
internal object AutoHotspotPlacement {
    fun route(
        mode: WirelessHotspotMode,
        bydHeadUnit: Boolean,
        adbAccess: LocalAdb.Access,
        appStartSupported: Boolean,
    ): AutoHotspotControl {
        if (mode != WirelessHotspotMode.MANUAL) return AutoHotspotControl.HIDDEN
        if (bydHeadUnit) {
            return when (adbAccess) {
                LocalAdb.Access.READY, LocalAdb.Access.NOT_APPROVED -> AutoHotspotControl.ADB_TOGGLE
                // No ADB pairing and no reachable daemon: the car cannot be asked, so do not offer it.
                LocalAdb.Access.UNREACHABLE, LocalAdb.Access.UNSUPPORTED -> AutoHotspotControl.HIDDEN
            }
        }
        // Other head units start tethering through the system settings permission, never ADB.
        return if (appStartSupported) AutoHotspotControl.SYSTEM_SETTINGS_TOGGLE else AutoHotspotControl.READ_ONLY
    }
}
