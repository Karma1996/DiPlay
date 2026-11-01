package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.autoconnect.AutoConnectPreferences
import com.shilapi.xcertplay.autoconnect.AutoConnectSession
import com.shilapi.xcertplay.autoconnect.SleepWakeTrigger
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** TC-051, TC-053: the automatic card's single home and the wake receiver lifecycle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class AutoConnectUiTest {
    private lateinit var activity: DiPlayActivity

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay", "diplay_auto_connect_diag", "diplay_last_problem", "xcertplay_airplay")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AutoConnectPreferences.resetForTest(app)
        AutoConnectSession.resetForTest()
        SleepWakeTrigger.resetForTest()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @After fun tearDown() {
        AutoConnectSession.resetForTest()
        SleepWakeTrigger.resetForTest()
    }

    private fun automaticCard() = LinearLayout(activity).also {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "automaticConnectionSettings",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, it))
    }

    @Test fun masterAndChildrenLiveInOneSection() {
        val card = automaticCard()
        val all = switches(card)
        val master = all.single { it.contentDescription == activity.getString(R.string.settings_auto_connect_title) }
        val child = all.single { it.contentDescription == activity.getString(R.string.connect_when_diplay_opens) }
        assertFalse("the master switch is off until the first success", master.isChecked)
        assertFalse("a child is disabled while the master switch is off", child.isEnabled)

        AutoConnectPreferences.ensureMigrated(activity)
        AutoConnectPreferences.setMaster(activity, true, byUser = true)
        val enabledChild = switches(automaticCard()).single {
            it.contentDescription == activity.getString(R.string.connect_when_diplay_opens)
        }
        assertTrue(enabledChild.isEnabled)
    }

    @Test fun wakeReceiverRegistersOnceAndUnregisters() {
        val app = RuntimeEnvironment.getApplication()
        SleepWakeTrigger.ensureRegistered(app) {}
        SleepWakeTrigger.ensureRegistered(app) {}
        assertTrue(SleepWakeTrigger.isRegistered())
        SleepWakeTrigger.unregister(app)
        assertFalse(SleepWakeTrigger.isRegistered())
    }

    private fun switches(view: View): List<Switch> = buildList {
        if (view is Switch) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(switches(view.getChildAt(index)))
    }
}
