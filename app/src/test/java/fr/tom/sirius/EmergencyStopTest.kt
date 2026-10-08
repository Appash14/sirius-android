package fr.tom.sirius

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ARRÊTER must work on the phone alone: no server is configured here, so nothing can go through the network
 * or through Sirius. The tap uses the real PendingIntent of the ongoing notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SiriusApp::class)
class EmergencyStopTest {
    private lateinit var app: SiriusApp
    private fun command(action: String, id: String, args: JSONObject = JSONObject()) =
        JSONObject().put("type", "action").put("id", id).put("action", action).put("args", args)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun notifications() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(app.settings.read().configured)
        app.pilot.setEnabled(true)
        app.pilot.attachAccessibility(Robolectric.setupService(SiriusAccessibilityService::class.java))
        // Robolectric does not deliver to receivers declared in the manifest here ("Matching wrappers: []").
        // A registered instance receives the explicit intents aimed at PilotStopReceiver, exactly as Android
        // would; the manifest entry itself (present, not exported) is checked by scripts/check_repo.py.
        app.registerReceiver(PilotStopReceiver(), IntentFilter(PilotStopReceiver.ACTION), Context.RECEIVER_NOT_EXPORTED)
    }

    @Test fun notificationCancelsSessionAndKeepsPermissionUntilNewVoiceQuestion() {
        val results = mutableListOf<JSONObject>()
        // The action waits for the assistant sheet to leave (150 ms) when Tom taps: it is still running.
        app.pilot.receive(command("defiler", "agir-en-cours", JSONObject().put("direction", "bas"))) { results.add(it); true }
        assertTrue(results.isEmpty())
        assertTrue(app.pilot.state.value.session)
        assertTrue(app.engine.pilotSession)
        val ongoing = notifications().single { it.extras.getCharSequence("android.title")?.toString() == "Sirius pilote ton téléphone" }
        val stop = ongoing.actions.single { it.title.toString() == "ARRÊTER" }

        stop.actionIntent.send()
        idle()

        assertEquals(STOPPED_BY_TOM, results.single().getJSONObject("data").getString("raison"))
        assertFalse(results.single().getBoolean("ok"))
        assertTrue(app.settings.pilotStopped); assertTrue(app.settings.pilotEnabled)
        assertTrue(app.pilot.state.value.stopped); assertFalse(app.pilot.state.value.session); assertFalse(app.engine.pilotSession)
        assertTrue(notifications().none { it.extras.getCharSequence("android.title")?.toString() == "Sirius pilote ton téléphone" })
        assertTrue(notifications().any { it.extras.getCharSequence("android.title")?.toString() == "Pilotage arrêté" })
        assertTrue(app.pilot.state.value.log.any { it.action == "arrêt de session" && it.source == "notification" })

        // Every later command is refused locally, reads included, and nothing starts a new session.
        for ((index, action) in listOf("ecran", "capture", "notifications", "defiler", "glisser", "toucher").withIndex()) {
            val args = when (action) {
                "defiler", "glisser" -> JSONObject().put("direction", "bas")
                "toucher" -> JSONObject().put("texte", "Messages")
                else -> JSONObject()
            }
            var result: JSONObject? = null
            app.pilot.receive(command(action, "apres-arret-$index", args)) { result = it; true }
            idle()
            assertEquals(action, STOPPED_BY_TOM, result!!.getJSONObject("data").getString("raison"))
        }
        assertFalse(app.pilot.state.value.session)
        // A restart keeps the stop: it lives in the settings, not in memory.
        assertTrue(Settings(app).pilotStopped)

        // A delayed old transcript and a cancelled/noisy utterance cannot give the hand back.
        app.pilot.questionAccepted("old")
        assertTrue(app.settings.pilotStopped)
        app.pilot.questionStarted("new")
        app.pilot.questionAccepted("old")
        assertTrue(app.settings.pilotStopped)
        app.pilot.questionAccepted("new")
        assertFalse(app.settings.pilotStopped); assertFalse(app.pilot.state.value.stopped)
        var after: JSONObject? = null
        app.pilot.receive(command("notifications", "apres-reactivation")) { after = it; true }
        idle()
        assertEquals("notifications_absentes", after!!.getJSONObject("data").getString("raison"))
        app.pilot.endSession()
    }

    @Test fun pillAndVoiceStopTheSameWay() {
        val results = mutableListOf<JSONObject>()
        app.pilot.receive(command("glisser", "agir-glisser", JSONObject().put("direction", "haut"))) { results.add(it); true }
        assertTrue(app.engine.pilotSession)
        // Voice path: VoiceEngine calls onStopWord, wired by SiriusApp to the same local stop.
        app.pilot.emergencyStop("mot_code_local", "sirius halte")
        idle()
        assertEquals(STOPPED_BY_TOM, results.single().getJSONObject("data").getString("raison"))
        assertTrue(app.settings.pilotStopped)
        assertTrue(app.pilot.state.value.log.any { it.source == "mot_code_local" && it.recognized == "sirius halte" })

        app.pilot.setEnabled(true)
        app.pilot.receive(command("ecran", "agir-pastille")) { results.add(it); true }
        app.pilot.emergencyStop("pastille")
        idle()
        assertEquals(STOPPED_BY_TOM, results.last().getJSONObject("data").getString("raison"))
        assertFalse(app.pilot.state.value.session)
    }

    @Test fun serverStopCancelsAnActiveActionAndRepeatedIdsRemainSuccessful() {
        val running = mutableListOf<JSONObject>()
        app.pilot.receive(command("defiler", "running", JSONObject().put("direction", "bas"))) { running += it; true }
        assertTrue(app.pilot.state.value.session)
        val stops = mutableListOf<JSONObject>()
        repeat(2) { app.pilot.receive(command("arreter_session", "server-stop")) { stops += it; true } }
        idle()
        assertEquals(2, stops.size)
        assertTrue(stops.all { it.getString("type") == "action_result" && it.getString("id") == "server-stop" && it.getBoolean("ok") })
        assertEquals(STOPPED_BY_TOM, running.single().getJSONObject("data").getString("raison"))
        assertFalse(app.pilot.state.value.session)
        assertFalse(app.engine.pilotSession)
        assertTrue(app.settings.pilotStopped)
        assertTrue(app.settings.pilotEnabled)
        assertTrue(app.pilot.state.value.log.any { it.action == "arrêt de session" && it.source == "serveur" })
    }

    @Test fun serverStopAlsoWorksWhileLockedDisabledAndWithoutAccessibility() {
        app.pilot.setEnabled(false)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val controller = PilotController(app, app.settings, app.engine)
        repeat(2) { index ->
            var result: JSONObject? = null
            controller.receive(command("arreter_session", "inactive-$index")) { result = it; true }
            assertTrue(result!!.getBoolean("ok"))
            assertEquals("inactive-$index", result!!.getString("id"))
        }
        assertFalse(controller.state.value.session)
        assertTrue(app.settings.pilotStopped)
        assertFalse(app.settings.pilotEnabled)
    }

    @Test fun serverStopRejectsArgumentsAndCannotBeNestedInASequence() {
        for (args in listOf(JSONObject().put("raison", "serveur"), JSONObject().put("code", "test"))) {
            var result: JSONObject? = null
            app.pilot.receive(command("arreter_session", "bad-stop", args)) { result = it; true }
            assertEquals("arguments_invalides", result!!.getJSONObject("data").getString("raison"))
            assertFalse(app.settings.pilotStopped)
        }
        val steps = org.json.JSONArray().put(JSONObject().put("action", "arreter_session").put("args", JSONObject()))
        var result: JSONObject? = null
        app.pilot.receive(command("sequence", "nested-stop", JSONObject().put("actions", steps))) { result = it; true }
        assertEquals("arguments_invalides", result!!.getJSONObject("data").getString("raison"))
        assertFalse(app.settings.pilotStopped)
    }

    @Test fun stopReceiverOnlyReactsToItsOwnAction() {
        app.sendBroadcast(Intent(app, PilotStopReceiver::class.java).setAction("autre.action"))
        idle()
        assertFalse(app.settings.pilotStopped)
        app.sendBroadcast(Intent(app, PilotStopReceiver::class.java).setAction(PilotStopReceiver.ACTION))
        idle()
        assertTrue(app.settings.pilotStopped)
    }

    @Test fun aNewQuestionCannotOverrideTheExplicitPermissionSwitch() {
        app.pilot.emergencyStop("pastille")
        app.pilot.setEnabled(false)
        app.pilot.questionStarted("new"); app.pilot.questionAccepted("new")
        assertFalse(app.settings.pilotEnabled)
        assertTrue(app.settings.pilotStopped)
    }
}
