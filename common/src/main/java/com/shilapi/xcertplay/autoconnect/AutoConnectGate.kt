package com.shilapi.xcertplay.autoconnect

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * The single decision point for every automatic trigger (design §6.1, DG-1).
 *
 * Pure: the same [AutoConnectEnvironment] always produces the same [AutoConnectDecision]. It never
 * reads a clock, a preference or the Bluetooth adapter, never starts a connection and never writes
 * storage. The Android adapter only collects the snapshot and carries out the decision.
 */
internal object AutoConnectGate {

    fun evaluate(env: AutoConnectEnvironment, trigger: AutoConnectTrigger): AutoConnectDecision {
        if (!env.masterEnabled) return AutoConnectDecision.Ignore(IgnoreReason.OFF)
        if (env.sessionActive) return AutoConnectDecision.Ignore(IgnoreReason.RUNNING)
        if (trigger.automatic && !subSwitchOn(env, trigger)) return AutoConnectDecision.Ignore(IgnoreReason.OFF)
        if (isDuplicate(env, trigger)) return AutoConnectDecision.Ignore(IgnoreReason.DUPLICATE)

        val tier = tier(env)
        // Without BLUETOOTH_CONNECT nothing can be enumerated or reached: the automatic path is off.
        if (tier == AutoConnectTier.D) return AutoConnectDecision.Blocked(BlockReason.BLUETOOTH_PERMISSION)

        when (val target = resolveTarget(env)) {
            TargetResolution.PREFERRED -> Unit
            TargetResolution.NO_PREFERRED -> {
                val candidates = env.bondedIphones.size
                val reason = if (candidates >= 2) ChoiceReason.MULTIPLE_CANDIDATES else ChoiceReason.NO_PREFERRED
                return AutoConnectDecision.NeedsChoice(reason, candidates)
            }
            TargetResolution.PREFERRED_INVALID -> {
                // Automatic mode never falls back to another iPhone (R-005, DA-011).
                val others = env.bondedIphones.count { !it.address.equals(env.preferredAddress, ignoreCase = true) }
                return AutoConnectDecision.NeedsChoice(
                    if (others > 0) ChoiceReason.PREFERRED_UNREACHABLE else ChoiceReason.PREFERRED_INVALID,
                    others,
                )
            }
        }

        val address = env.preferredAddress ?: return AutoConnectDecision.NeedsChoice(ChoiceReason.NO_PREFERRED)
        return AutoConnectDecision.Start(
            AutoConnectRequest(
                sessionId = 0L,
                trigger = trigger,
                mode = env.wirelessMode,
                preferredAddress = address,
                tier = tier,
            ),
        )
    }

    /** Design §12.2 table 12-1; recomputed on every evaluation because permissions can change. */
    fun tier(env: AutoConnectEnvironment): AutoConnectTier {
        if (!env.bluetoothPermission) return AutoConnectTier.D
        return when {
            env.writeSettingsOrAdbHotspot && env.overlayPermission -> AutoConnectTier.A
            env.overlayPermission -> AutoConnectTier.B
            else -> AutoConnectTier.C
        }
    }

    fun resolveTarget(env: AutoConnectEnvironment): TargetResolution {
        val preferred = env.preferredAddress ?: return TargetResolution.NO_PREFERRED
        val stillBonded = env.bondedIphones.any { it.address.equals(preferred, ignoreCase = true) }
        return if (stillBonded) TargetResolution.PREFERRED else TargetResolution.PREFERRED_INVALID
    }

    private fun subSwitchOn(env: AutoConnectEnvironment, trigger: AutoConnectTrigger): Boolean = when (trigger) {
        AutoConnectTrigger.COLD_START -> env.autoStartOnBoot
        AutoConnectTrigger.APP_OPENED -> env.openOnLaunch
        AutoConnectTrigger.PHONE_BLUETOOTH -> env.connectOnPhoneBluetooth
        // A sleep/wake event has no separate switch: the master switch owns it (design table 7-2).
        AutoConnectTrigger.SLEEP_WAKE -> true
        AutoConnectTrigger.RETRY_BUTTON -> true
    }

    private fun isDuplicate(env: AutoConnectEnvironment, trigger: AutoConnectTrigger): Boolean {
        if (!trigger.automatic) return false
        val window = AutoConnectDedupe.windowMillis(trigger) ?: return false
        if (env.lastTrigger != trigger) return false
        val previous = env.lastTriggerAtElapsedMillis ?: return false
        val elapsed = env.nowElapsedMillis - previous
        return elapsed in 0 until window
    }
}
