package com.shilapi.xcertplay.autoconnect

import android.content.Context
import com.shilapi.xcertplay.DiagnosticRedactor
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * One record per automatic session, eight fixed enum/integer fields (design §9.3, G-06).
 *
 * The interface takes no free text, so an SSID, passphrase, MAC or paired-device record cannot
 * reach it in the first place. [report] still passes the finished line through
 * [DiagnosticRedactor] as a second line of defence (DA-021).
 */
internal object AutoConnectDiagnostics {
    const val PREFS = "diplay_auto_connect_diag"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_BACKEND = "backend"
    private const val KEY_STAGE = "stage"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_ELAPSED = "elapsed_ms"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_FAILURE = "failure"
    private const val KEY_TIER = "tier"
    private const val KEY_AT = "at"

    val keys: Set<String> = setOf(
        KEY_TRIGGER, KEY_BACKEND, KEY_STAGE, KEY_ATTEMPTS, KEY_ELAPSED, KEY_OUTCOME, KEY_FAILURE, KEY_TIER, KEY_AT,
    )

    fun record(context: Context, record: AutoConnectRecord) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRIGGER, record.trigger.name)
            .putString(KEY_BACKEND, record.backend.name)
            .putString(KEY_STAGE, record.stage.name)
            .putInt(KEY_ATTEMPTS, record.attempts)
            .putLong(KEY_ELAPSED, record.elapsedMillis)
            .putString(KEY_OUTCOME, record.outcome.name)
            .putString(KEY_FAILURE, record.failure?.name ?: "NONE")
            .putString(KEY_TIER, record.tier.name)
            .putLong(KEY_AT, record.atMillis)
            .commit()
    }

    /** Single greppable line, fixed field order, every value an enum name or a number. */
    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) return "AutoConnect: v=1 no session recorded"
        val line = "AutoConnect: v=1 trigger=${prefs.getString(KEY_TRIGGER, "NONE")} " +
            "backend=${prefs.getString(KEY_BACKEND, "NONE")} " +
            "stage=${prefs.getString(KEY_STAGE, "NONE")} " +
            "attempts=${prefs.getInt(KEY_ATTEMPTS, 0)} " +
            "elapsedMs=${prefs.getLong(KEY_ELAPSED, 0L)} " +
            "outcome=${prefs.getString(KEY_OUTCOME, "NONE")} " +
            "failure=${prefs.getString(KEY_FAILURE, "NONE")} " +
            "tier=${prefs.getString(KEY_TIER, "NONE")}"
        // The redactor must never drop an all-enum diagnostic line; if it would, fail closed.
        return DiagnosticRedactor.redact(line) ?: "AutoConnect: v=1 redacted"
    }

    internal fun resetForTest(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

internal data class AutoConnectRecord(
    val trigger: AutoConnectTrigger,
    val backend: WirelessHotspotMode,
    val stage: AutoConnectStage,
    val attempts: Int,
    val elapsedMillis: Long,
    val outcome: AutoConnectOutcome,
    val failure: AutoConnectFailure?,
    val tier: AutoConnectTier,
    val atMillis: Long,
)
