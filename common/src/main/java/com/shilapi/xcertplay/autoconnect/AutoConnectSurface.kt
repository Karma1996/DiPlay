package com.shilapi.xcertplay.autoconnect

import android.content.Context
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R

/**
 * Maps automatic-session state onto text and a single action (design §7.9, §12.3).
 *
 * Everything here is displayed inside DiPlay's own Home or CarPlay start screen as an inline
 * notice. Nothing in this object can create a dialog: the automatic path must never block the
 * driver with a modal window (ADR-004, DA-023).
 */
internal object AutoConnectSurface {

    @StringRes
    fun stageLabel(stage: AutoConnectStage): Int = when (stage) {
        AutoConnectStage.PREPARING_NETWORK -> R.string.settings_auto_connect_stage_network
        AutoConnectStage.RESOLVING_TARGET -> R.string.looking_for_your_paired_iphone
        AutoConnectStage.HANDSHAKE -> R.string.settings_auto_connect_stage_network
        AutoConnectStage.AWAITING_JOIN -> R.string.settings_auto_connect_stage_join
        AutoConnectStage.CONNECTING -> R.string.settings_auto_connect_stage_connect
    }

    @StringRes
    fun failureLabel(failure: AutoConnectFailure): Int = when (failure) {
        AutoConnectFailure.CAR_HOTSPOT_OFF -> R.string.connection_help_car_hotspot_off
        AutoConnectFailure.CAR_HOTSPOT_DETAILS_DIFFER -> R.string.hotspot_network_not_ready
        AutoConnectFailure.HOTSPOT_INTERFACE_NOT_FOUND -> R.string.hotspot_network_not_ready
        AutoConnectFailure.HOTSPOT_NOT_READY -> R.string.hotspot_network_not_ready
        AutoConnectFailure.HOTSPOT_CONFIGURATION -> R.string.hotspot_network_not_ready
        AutoConnectFailure.CAR_HOTSPOT_START_TIMED_OUT -> R.string.hotspot_network_not_ready
        AutoConnectFailure.CAR_HOTSPOT_START_FAILED -> R.string.hotspot_network_not_ready
        AutoConnectFailure.FIRST_TCP_TIMEOUT -> R.string.first_tcp_timeout
        AutoConnectFailure.CHOSEN_IPHONE_NOT_PAIRED,
        AutoConnectFailure.NO_PAIRED_IPHONE,
        AutoConnectFailure.IPHONE_NOT_CHOSEN,
        -> R.string.choose_your_iphone
        AutoConnectFailure.BLUETOOTH_UNUSABLE -> R.string.connection_help_bluetooth_unusable
        AutoConnectFailure.WIFI_DIRECT_BESIDE_HOTSPOT -> R.string.wifi_direct_refused_beside_hotspot
        AutoConnectFailure.WIFI_DIRECT_CHANNEL_UNAVAILABLE -> R.string.connection_help_iphone_wifi
        AutoConnectFailure.AUTHENTICATION_FAILED,
        AutoConnectFailure.STREAM_LOST,
        AutoConnectFailure.UNKNOWN,
        -> R.string.connection_help_iphone_wifi
    }

    @StringRes
    fun choiceLabel(reason: ChoiceReason): Int = when (reason) {
        ChoiceReason.NO_PREFERRED -> R.string.choose_your_iphone
        ChoiceReason.MULTIPLE_CANDIDATES -> R.string.choose_your_iphone
        ChoiceReason.PREFERRED_INVALID -> R.string.chosen_iphone_not_paired
        ChoiceReason.PREFERRED_UNREACHABLE -> R.string.settings_auto_connect_needs_choice
    }

    /** "第 n/N 次 · 已用 x 秒" — a single resource with three placeholders, never concatenated. */
    fun attemptProgress(context: Context, attempt: Int, maxAttempts: Int, elapsedMillis: Long): String =
        context.getString(
            R.string.settings_auto_connect_attempt_progress,
            attempt,
            maxAttempts,
            (elapsedMillis / 1000L).toInt(),
        )
}
