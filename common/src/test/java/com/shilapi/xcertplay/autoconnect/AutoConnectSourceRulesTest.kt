package com.shilapi.xcertplay.autoconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TC-016…TC-019: source and manifest rules that the JVM cannot otherwise observe
 * (NFR-003, NFR-005, NFR-006, DA-001, DA-019).
 */
class AutoConnectSourceRulesTest {
    private val autoconnect = File("src/main/java/com/shilapi/xcertplay/autoconnect")
    private val main = File("src/main/java/com/shilapi/xcertplay")

    private fun sources(dir: File): List<Pair<String, String>> =
        dir.listFiles { file -> file.extension == "kt" }!!
            .map { it.name to it.readText() }

    @Test fun noPeriodicWorkOrWakelock() {
        val forbidden = listOf("scheduleAtFixedRate", "WakeLock", "PARTIAL_WAKE_LOCK")
        for ((name, text) in sources(autoconnect)) {
            for (token in forbidden) {
                assertFalse("$name must not use $token", text.contains(token))
            }
        }
        // A backoff delay is the only scheduled work; it is never a self-rearming tick.
        val session = autoconnect.resolve("AutoConnectSession.kt").readText()
        assertFalse(session.contains("postDelayed"))
    }

    @Test fun noAutomaticConnectBypass() {
        // The automatic package may ask the connector, never the Activity's connect() directly.
        for ((name, text) in sources(autoconnect)) {
            assertFalse("$name must not call connect(", Regex("""\bconnect\s*\(""").containsMatchIn(text))
        }
        val activity = main.resolve("DiPlayActivity.kt").readText()
        // Every automatic trigger is offered through the gate; the connector is the only automatic caller.
        assertTrue(activity.contains("AutoConnectSession.offer("))
        assertTrue(activity.contains("startAutomaticConnection"))
        assertEquals(
            "exactly one automatic entry point",
            1,
            Regex("""fun startAutomaticConnection\s*\(""").findAll(activity).count(),
        )
    }

    @Test fun noAuthBypass() {
        val forbidden = listOf(
            "skipAuthentication", "trustAnyDevice", "bypassAuthentication",
            "trustedDevices", "fastPath", "allowUnpaired",
        )
        for ((name, text) in sources(autoconnect)) {
            for (token in forbidden) {
                assertFalse("$name must not contain $token", text.contains(token, ignoreCase = true))
            }
        }
    }

    @Test fun noNewPermissionsOrComponents() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("""<uses-permission android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.WRITE_SETTINGS",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.REQUEST_INSTALL_PACKAGES",
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                "android.permission.FOREGROUND_SERVICE_LOCATION",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.PACKAGE_USAGE_STATS",
            ),
            permissions,
        )
        val receivers = Regex("""<receiver\s+android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "com.shilapi.xcertplay.NavigationWidget",
                "com.shilapi.xcertplay.BootReceiver",
            ),
            receivers,
        )
        assertFalse("no new foreground service may be declared", manifest.contains("AutoConnect"))
    }

    @Test fun newPreferencesStayInsideTheirWhitelist() {
        val prefs = autoconnect.resolve("AutoConnectPreferences.kt").readText()
        // Every key is a Boolean, an enum name or a Long; the object never touches the credential prefs.
        assertFalse(Regex("""getSharedPreferences\(\s*"xcertplay_airplay"""").containsMatchIn(prefs))
        assertFalse(Regex("""manualHotspot\w*""").containsMatchIn(prefs))
        assertFalse(Regex("""(?i)\bssid\b\s*=""").containsMatchIn(prefs))
    }
}
