package fr.tom.sirius

import android.app.Application
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HudProtocolTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun field(engine: VoiceEngine, name: String, value: Any?) {
        VoiceEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
    }
    @Suppress("UNCHECKED_CAST")
    private fun flow(engine: VoiceEngine) = VoiceEngine::class.java.getDeclaredField("mutable")
        .apply { isAccessible = true }.get(engine) as MutableStateFlow<VoiceState>
    private fun event(engine: VoiceEngine, json: JSONObject, bytes: ByteArray? = null) {
        VoiceEngine::class.java.getDeclaredMethod("handleEvent", JSONObject::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(engine, json, bytes)
    }
    private fun message(id: String, role: String, text: String) = JSONObject()
        .put("type", "message").put("id", id).put("role", role).put("text", text)
    private fun hello(vararg messages: JSONObject) = JSONObject().put("type", "hello")
        .put("capabilities", JSONObject().put("partiels", true)).put("messages", JSONArray(messages.toList()))

    @Test fun wireFinalAndLatePartialThenReconnectKeepOneTomBubble() {
        val engine = VoiceEngine(context, Settings(context))
        field(engine, "listening", true)
        field(engine, "liveEnonce", "u1")
        val state = flow(engine)
        state.value = beginHudUtterance(VoiceState(conversation = true), "u1")
        event(engine, JSONObject().put("type", "partiel").put("enonce", "u1").put("text", "Ouais"))
        val final = message("m1", "tom", "Ouais, l'apparition est tough.").put("enonce", "u1")
        event(engine, final)
        event(engine, JSONObject().put("type", "partiel").put("enonce", "u1").put("text", "Ouais tardif"))
        event(engine, hello(message("old", "tom", "Ancien historique"), final))
        event(engine, final)
        assertEquals(HudMessage("u1", "tom", "Ouais, l'apparition est tough.", true, "m1"), state.value.hudMessages.single())
        assertEquals("Ouais, l'apparition est tough.", state.value.tomTranscript)
    }

    @Test fun reconnectCompletesMissedFinalWithoutEnonceAndNeverImportsInitialHistory() {
        val engine = VoiceEngine(context, Settings(context))
        field(engine, "listening", true)
        val state = flow(engine)
        state.value = VoiceState(conversation = true)
        val old = message("old", "tom", "Ancien historique")
        event(engine, hello(old))
        assertTrue(state.value.hudMessages.isEmpty())
        state.value = withLiveTranscript(beginHudUtterance(state.value, "u1"), "Phrase", true)
        val final = message("m1", "tom", "Phrase finale")
        val reply = message("r1", "sirius", "Réponse retrouvée").put("reply_to", "m1")
        event(engine, hello(old, final, reply))
        event(engine, hello(old, final, reply))
        event(engine, final)
        event(engine, reply)
        assertEquals(listOf("u1", "r1"), state.value.hudMessages.map { it.id })
        assertEquals("Phrase finale", state.value.hudMessages.first().text)
        assertEquals("Réponse retrouvée", state.value.hudMessages.last().text)
        assertTrue(state.value.hudMessages.all { it.final })
    }

    @Test fun wireAudioIsVisibleBeforePlaybackAndMergesWithSiriusMessageInEitherOrder() {
        for (messageFirst in listOf(false, true)) {
            val engine = VoiceEngine(context, Settings(context))
            val state = flow(engine)
            state.value = VoiceState(conversation = true)
            field(engine, "listening", true)
            field(engine, "awaiting", true)
            field(engine, "activeReply", "audio-r1")
            // Keep playback pending: receipt of text alone must be enough to show it.
            val player = MediaPlayer()
            field(engine, "player", player)
            try {
                val final = message("message-r1", "sirius", "Une réponse. Et la suite.").put("reply_to", "q1")
                if (messageFirst) event(engine, final)
                val first = JSONObject().put("type", "audio").put("id", "audio-r1").put("epoch", 0)
                    .put("mime", "audio/mpeg").put("text", "Une réponse.").put("index", 0).put("reply_to", "q1")
                event(engine, first, byteArrayOf(1))
                assertEquals(1, state.value.hudMessages.size)
                assertEquals(if (messageFirst) final.getString("text") else "Une réponse.", state.value.hudMessages.single().text)
                event(engine, first, byteArrayOf(1))
                event(engine, JSONObject(first.toString()).put("index", 1).put("text", "Et la suite."), byteArrayOf(2))
                if (!messageFirst) event(engine, final)
                event(engine, final)
                event(engine, hello(final))
                assertEquals("Une réponse. Et la suite.", state.value.hudMessages.single().text)
                assertEquals("sirius", state.value.hudMessages.single().role)
                assertEquals(2, state.value.hudMessages.single().audioParts.size)
            } finally { field(engine, "player", null); player.release() }
        }
    }

    @Test fun cancelledAndOldEpochMessagesCannotEnterOrChangeTheLiveThread() {
        val engine = VoiceEngine(context, Settings(context))
        val state = flow(engine)
        state.value = beginHudUtterance(VoiceState(conversation = true), "u1")
        event(engine, message("m1", "tom", "Annulé").put("enonce", "u1").put("cancelled", true))
        field(engine, "epoch", 2)
        event(engine, message("m2", "tom", "Ancienne génération").put("enonce", "u1").put("epoch", 1))
        assertEquals("", state.value.hudMessages.single().text)
        assertFalse(state.value.hudMessages.single().final)
    }
}
