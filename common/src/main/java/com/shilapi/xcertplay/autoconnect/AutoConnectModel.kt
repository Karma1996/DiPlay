package com.shilapi.xcertplay.autoconnect

import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** What the adapter layer knows about the car's own hotspot. */
internal enum class HotspotState { ON, OFF, UNKNOWN, NOT_APPLICABLE }

/** One bonded iPhone. Only the address and name matter for target resolution. */
internal data class BondedIphone(val address: String, val name: String? = null)

/**
 * Snapshot taken by the Android adapter before every gate evaluation.
 *
 * The gate is a pure function of this snapshot: it never reads a clock, a preference or a
 * Bluetooth adapter itself, so the policy can be unit-tested without Android.
 */
internal data class AutoConnectEnvironment(
    val masterEnabled: Boolean,
    val openOnLaunch: Boolean,
    val connectOnPhoneBluetooth: Boolean,
    val autoStartOnBoot: Boolean,
    val wirelessMode: WirelessHotspotMode,
    val preferredAddress: String?,
    val preferredLastSuccessAt: Long?,
    val bondedIphones: List<BondedIphone> = emptyList(),
    val connectedIphoneCount: Int = 0,
    val bluetoothPermission: Boolean = true,
    val overlayPermission: Boolean = true,
    val writeSettingsOrAdbHotspot: Boolean = false,
    val hotspotState: HotspotState = HotspotState.UNKNOWN,
    val sessionActive: Boolean = false,
    val nowElapsedMillis: Long = 0L,
    val lastTriggerAtElapsedMillis: Long? = null,
    /** The trigger of [lastTriggerAtElapsedMillis]; de-duplication is per trigger. */
    val lastTrigger: AutoConnectTrigger? = null,
)

internal enum class IgnoreReason { OFF, RUNNING, DUPLICATE }

internal enum class ChoiceReason { NO_PREFERRED, PREFERRED_INVALID, MULTIPLE_CANDIDATES, PREFERRED_UNREACHABLE }

internal enum class BlockReason { BLUETOOTH_PERMISSION, BLUETOOTH_UNUSABLE }

internal enum class TargetResolution { PREFERRED, NO_PREFERRED, PREFERRED_INVALID }

/** The request handed to the connector once the gate says yes. */
internal data class AutoConnectRequest(
    val sessionId: Long,
    val trigger: AutoConnectTrigger,
    val mode: WirelessHotspotMode,
    val preferredAddress: String,
    val tier: AutoConnectTier,
)

internal sealed interface AutoConnectDecision {
    data class Start(val request: AutoConnectRequest) : AutoConnectDecision
    data class Ignore(val reason: IgnoreReason) : AutoConnectDecision
    data class NeedsChoice(val reason: ChoiceReason, val candidates: Int = 0) : AutoConnectDecision
    data class Blocked(val reason: BlockReason) : AutoConnectDecision
}

/** The single state machine's running stages (design §7.1). */
internal enum class AutoConnectStage { PREPARING_NETWORK, RESOLVING_TARGET, HANDSHAKE, AWAITING_JOIN, CONNECTING }

internal enum class AutoConnectOutcome { SUCCEEDED, FAILED, NEEDS_CHOICE, CANCELLED, BLOCKED }

internal enum class CancelReason {
    OFF,
    FORGOTTEN,
    MANUAL_TAKEOVER,
    USB_TAKEOVER,
    USER_CANCELLED,
    FIRST_PAIR_CANCELLED,
}

/**
 * Failure vocabulary of the automatic session, mapped one-to-one onto the classes the existing
 * connection stack already reports (design §6.4 table 6-2).
 */
internal enum class AutoConnectFailure(private val retryableValue: Boolean) {
    CAR_HOTSPOT_OFF(false),
    CAR_HOTSPOT_DETAILS_DIFFER(false),
    HOTSPOT_INTERFACE_NOT_FOUND(true),
    HOTSPOT_NOT_READY(true),
    HOTSPOT_CONFIGURATION(false),
    CAR_HOTSPOT_START_TIMED_OUT(true),
    CAR_HOTSPOT_START_FAILED(true),
    FIRST_TCP_TIMEOUT(true),
    CHOSEN_IPHONE_NOT_PAIRED(false),
    NO_PAIRED_IPHONE(false),
    IPHONE_NOT_CHOSEN(false),
    BLUETOOTH_UNUSABLE(false),
    WIFI_DIRECT_BESIDE_HOTSPOT(false),
    WIFI_DIRECT_CHANNEL_UNAVAILABLE(false),
    AUTHENTICATION_FAILED(false),
    STREAM_LOST(true),
    UNKNOWN(true),
    ;

    val retryable: Boolean get() = retryableValue

    companion object {
        fun of(failure: WirelessStartupFailure): AutoConnectFailure = when (failure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> HOTSPOT_NOT_READY
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> FIRST_TCP_TIMEOUT
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> HOTSPOT_CONFIGURATION
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> BLUETOOTH_UNUSABLE
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT -> WIFI_DIRECT_BESIDE_HOTSPOT
        }

        fun of(cause: ConnectionFailureCause): AutoConnectFailure = when (cause) {
            ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED -> CHOSEN_IPHONE_NOT_PAIRED
            ConnectionFailureCause.NO_PAIRED_IPHONE -> NO_PAIRED_IPHONE
            ConnectionFailureCause.IPHONE_NOT_CHOSEN -> IPHONE_NOT_CHOSEN
            ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE -> WIFI_DIRECT_CHANNEL_UNAVAILABLE
            ConnectionFailureCause.CAR_HOTSPOT_OFF -> CAR_HOTSPOT_OFF
            ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER -> CAR_HOTSPOT_DETAILS_DIFFER
            ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND -> HOTSPOT_INTERFACE_NOT_FOUND
            ConnectionFailureCause.CAR_HOTSPOT_START_TIMED_OUT -> CAR_HOTSPOT_START_TIMED_OUT
            ConnectionFailureCause.CAR_HOTSPOT_START_FAILED -> CAR_HOTSPOT_START_FAILED
        }
    }
}

internal sealed interface AutoConnectState {
    data object Idle : AutoConnectState
    data class Running(
        val stage: AutoConnectStage,
        val attempt: Int,
        val maxAttempts: Int,
        val elapsedMillis: Long,
    ) : AutoConnectState

    data class Succeeded(val mode: WirelessHotspotMode) : AutoConnectState
    data class Failed(val failure: AutoConnectFailure, val attempt: Int, val elapsedMillis: Long) : AutoConnectState
    data class NeedsChoice(val reason: ChoiceReason, val candidates: Int) : AutoConnectState
    data class Blocked(val reason: BlockReason) : AutoConnectState
    data class Cancelled(val reason: CancelReason) : AutoConnectState

    val terminal: Boolean get() = this !is Idle && this !is Running
}
