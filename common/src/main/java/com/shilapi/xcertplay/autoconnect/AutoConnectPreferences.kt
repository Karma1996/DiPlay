package com.shilapi.xcertplay.autoconnect

import android.content.Context
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Who opened the master switch, so a first automatic success never overrides a deliberate "off". */
internal enum class AutoConnectOptIn { NEVER_ASKED, ENABLED_AUTO, ENABLED_USER, DISABLED_USER }

internal data class PreferredIphone(
    val address: String,
    val name: String,
    val lastMode: WirelessHotspotMode?,
    val lastSuccessAt: Long?,
)

/**
 * The only writer of the automatic-connection preferences (design §5.2, §6.3).
 *
 * Every key here is a Boolean, an enum name or a Long. Hotspot credentials stay in
 * `xcertplay_airplay`; this object never reads or copies them.
 */
internal object AutoConnectPreferences {
    const val PREFS = "diplay"
    const val KEY_MASTER = "auto_connect_master"
    const val KEY_OPT_IN = "auto_connect_opt_in"
    const val KEY_NOTICE_SHOWN = "auto_connect_notice_shown"
    const val KEY_LAST_MODE = "phone_last_mode"
    const val KEY_LAST_SUCCESS_AT = "phone_last_success_at"
    private const val KEY_MIGRATED = "auto_connect_migrated_v1"

    /**
     * Emergency release switch (design table 13-1): false means a first success no longer enables
     * the master switch by itself. Everything else is unchanged.
     */
    const val AUTO_CONNECT_DEFAULT_ON = true

    /** Every key this feature may write into the `diplay` preferences (DA-017). */
    val newKeys: Set<String> = setOf(KEY_MASTER, KEY_OPT_IN, KEY_NOTICE_SHOWN, KEY_LAST_MODE, KEY_LAST_SUCCESS_AT, KEY_MIGRATED)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureMigrated(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val hasPreferred = DiPlayPreferences.phoneAddress(context) != null
        val explicitAutomation = DiPlayPreferences.autoConnect(context) ||
            DiPlayPreferences.connectOnPhoneBluetooth(context)
        val editor = prefs.edit().putBoolean(KEY_MIGRATED, true)
        if (hasPreferred && explicitAutomation) {
            // This user already opted into automation before the master switch existed.
            editor.putBoolean(KEY_MASTER, true).putString(KEY_OPT_IN, AutoConnectOptIn.ENABLED_USER.name)
        } else {
            editor.putBoolean(KEY_MASTER, false).putString(KEY_OPT_IN, AutoConnectOptIn.NEVER_ASKED.name)
        }
        editor.commit()
    }

    fun masterEnabled(context: Context): Boolean {
        ensureMigrated(context)
        return prefs(context).getBoolean(KEY_MASTER, false)
    }

    fun optIn(context: Context): AutoConnectOptIn = runCatching {
        AutoConnectOptIn.valueOf(prefs(context).getString(KEY_OPT_IN, null) ?: AutoConnectOptIn.NEVER_ASKED.name)
    }.getOrDefault(AutoConnectOptIn.NEVER_ASKED)

    fun setMaster(context: Context, enabled: Boolean, byUser: Boolean) {
        ensureMigrated(context)
        val editor = prefs(context).edit().putBoolean(KEY_MASTER, enabled)
        if (byUser) {
            editor.putString(
                KEY_OPT_IN,
                if (enabled) AutoConnectOptIn.ENABLED_USER.name else AutoConnectOptIn.DISABLED_USER.name,
            )
        }
        editor.commit()
    }

    fun noticeShown(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTICE_SHOWN, false)

    fun markNoticeShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTICE_SHOWN, true).commit()
    }

    /**
     * SC-005: true exactly once, on the first Home render after the automatic opt-in. Taking it
     * marks it shown, so the driver is told the switch was turned on without being nagged.
     */
    fun takeOptInNotice(context: Context): Boolean {
        if (noticeShown(context)) return false
        if (optIn(context) != AutoConnectOptIn.ENABLED_AUTO) return false
        markNoticeShown(context)
        return true
    }

    fun preferred(context: Context): PreferredIphone? {
        val address = DiPlayPreferences.phoneAddress(context) ?: return null
        val prefs = prefs(context)
        val mode = prefs.getString(KEY_LAST_MODE, null)
            ?.let { runCatching { WirelessHotspotMode.valueOf(it) }.getOrNull() }
        val at = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L).takeIf { it > 0L }
        return PreferredIphone(address, DiPlayPreferences.phoneName(context), mode, at)
    }

    /**
     * Called after the first frame renders (DA-014). Only then are the last-mode and last-success
     * fields written, and only a session that has never been asked opts itself in (ADR-001).
     */
    fun recordSuccess(context: Context, mode: WirelessHotspotMode, nowMillis: Long): Boolean {
        ensureMigrated(context)
        val prefs = prefs(context)
        var optedIn = false
        val editor = prefs.edit()
            .putString(KEY_LAST_MODE, mode.name)
            .putLong(KEY_LAST_SUCCESS_AT, nowMillis)
        if (AUTO_CONNECT_DEFAULT_ON && optIn(context) == AutoConnectOptIn.NEVER_ASKED) {
            editor.putBoolean(KEY_MASTER, true).putString(KEY_OPT_IN, AutoConnectOptIn.ENABLED_AUTO.name)
            optedIn = true
        }
        editor.commit()
        return optedIn
    }

    fun forgetPreferred(context: Context) {
        context.getSharedPreferences(DiPlayPreferences.PREFS, Context.MODE_PRIVATE).edit()
            .remove(DiPlayPreferences.KEY_PHONE_ADDRESS)
            .remove(DiPlayPreferences.KEY_PHONE_NAME)
            .commit()
        prefs(context).edit().remove(KEY_LAST_MODE).remove(KEY_LAST_SUCCESS_AT).commit()
    }

    internal fun resetForTest(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
