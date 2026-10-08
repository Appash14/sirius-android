package fr.tom.sirius

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SiriusApp::class)
class PilotVoiceSessionTest {
    private fun event(engine: VoiceEngine, id: String, text: String, utterance: String, cancelled: Boolean = false) {
        VoiceEngine::class.java.getDeclaredMethod("handleEvent", JSONObject::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(engine, JSONObject().put("type", "message").put("role", "tom")
                .put("id", id).put("text", text).put("enonce", utterance).put("cancelled", cancelled), null)
    }
    @Test fun onlyANewAcceptedUtteranceReopensAndQuotedCodeDoesNotStop() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        app.pilot.setEnabled(true)
        VoiceEngine::class.java.getDeclaredField("listening").apply { isAccessible = true }.set(app.engine, true)
        app.pilot.emergencyStop("notification")
        app.pilot.questionStarted("new")
        event(app.engine, "old-id", "Ouvre Telegram", "old")
        event(app.engine, "cancelled-id", "Ouvre Telegram", "new", cancelled = true)
        assertTrue(app.settings.pilotStopped)
        event(app.engine, "new-id", "Pourquoi Sirius halte a arrêté la session ?", "new")
        assertFalse(app.settings.pilotStopped); assertTrue(app.settings.pilotEnabled)
        app.engine.pilotSession = true
        event(app.engine, "code-id", "Sirius halte", "code")
        assertTrue(app.settings.pilotStopped)
        assertTrue(app.pilot.state.value.log.any { it.source == "mot_code_serveur" && it.recognized == "Sirius halte" })
        app.pilot.questionStarted("after")
        event(app.engine, "code-id", "Sirius halte", "code")
        assertTrue(app.settings.pilotStopped)
        event(app.engine, "after-id", "Envoie un message à Sirius", "after")
        assertFalse(app.settings.pilotStopped)
        app.engine.endConversation()
    }
}
