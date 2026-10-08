package fr.tom.sirius

import android.app.KeyguardManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SiriusApp::class)
class PilotGuardTest {
    private fun command(action: String, id: String = "test-$action") = JSONObject().put("type", "action").put("id", id).put("action", action).put("args", JSONObject())
    @Test fun realDispatcherCannotReadAnythingBeforeOptInOrWhileLocked() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        assertFalse(app.settings.pilotEnabled)
        for (action in listOf("ecran", "capture", "notifications")) {
            var result: JSONObject? = null
            app.pilot.receive(command(action)) { result = it; true }
            assertEquals("pilotage_desactive", result!!.getJSONObject("data").getString("raison"))
        }
        app.pilot.setEnabled(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        for (action in listOf("ecran", "capture", "notifications")) {
            var result: JSONObject? = null
            app.pilot.receive(command(action, "locked-$action")) { result = it; true }
            assertFalse(result!!.getBoolean("ok")); assertEquals("verrouille", result!!.getJSONObject("data").getString("raison"))
        }
    }
    @Test fun historyIsBoundedAndExcludesArgumentsAndPhoneContent() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        repeat(110) { index ->
            app.pilot.receive(JSONObject().put("type", "action").put("id", "test-$index").put("action", "ecrire").put("args", JSONObject().put("texte", "contenu-prive"))) { true }
        }
        assertEquals(100, app.pilot.state.value.log.size)
        assertFalse(app.settings.readActionLog().contains("contenu-prive"))
    }
    @Test fun keyguardBroadcastsRefreshTheLockStateOutsideAConversation() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.engine.state.value.locked)
        keyguard.setKeyguardLocked(false)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.engine.state.value.locked)
    }
    @Test fun lateUnknownApprovalDoesNotEnablePilotageOrExecuteAnything() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        app.pilot.decide("untrusted-token", true)
        assertFalse(app.settings.pilotEnabled)
        assertTrue(app.pilot.state.value.log.isEmpty())
    }
    @Test fun sessionOwnersCanBeShownHiddenReusedAndDestroyed() {
        val owners = SessionOwners()
        owners.show(); owners.hide(); owners.show()
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, owners.lifecycle.currentState)
        owners.hide(); owners.destroy()
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, owners.lifecycle.currentState)
    }
}
