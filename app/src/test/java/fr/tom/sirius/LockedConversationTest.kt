package fr.tom.sirius

import android.app.Application
import android.app.KeyguardManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LockedConversationTest {
    private fun message(id: String, role: String, text: String) = JSONObject()
        .put("type", "message").put("id", id).put("role", role).put("text", text)
    private fun event(engine: VoiceEngine, json: JSONObject) {
        VoiceEngine::class.java.getDeclaredMethod("handleEvent", JSONObject::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(engine, json, null)
    }

    @Test fun lockFiltersOldTurnsButKeepsNewPartialsFinalsAndAudioSynchronized() {
        var state = VoiceState(conversation = true)
        state = withServerMessage(state, ChatMessage("old-tom", "tom", "Ancien Tom"), true)
        state = withServerMessage(state, ChatMessage("old-sirius", "sirius", "Ancien Sirius"), true)
        val history = state.messages
        state = withLockState(state, true)
        assertTrue(visibleHudMessages(state).isEmpty())
        assertEquals(history, state.messages)
        state = beginHudUtterance(state, "u1")
        state = withLiveTranscript(state, "Nouveau", true)
        assertEquals("Nouveau", visibleHudMessages(state).single().text)
        state = withServerMessage(state.copy(speechActive = false), ChatMessage("q1", "tom", "Nouveau Tom"), true, "u1")
        state = withHudAudio(state, "r1", "Nouveau Sirius", "0", "q1")
        state = withServerMessage(state, ChatMessage("s1", "sirius", "Nouveau Sirius"), true, replyTo = "q1")
        val lockedIds = visibleHudMessages(state).map { it.id }
        assertEquals(listOf("u1", "r1"), lockedIds)
        assertEquals(listOf("Nouveau Tom", "Nouveau Sirius"), visibleHudMessages(state).map { it.text })
        // Replays and reconciliation update the shared messages without exposing pre-lock turns.
        state = withServerMessage(state, ChatMessage("old-tom", "tom", "Ancien Tom corrigé"), false)
        state = withServerMessage(state, ChatMessage("q1", "tom", "Nouveau Tom"), false, "u1")
        state = withServerMessage(state, ChatMessage("s1", "sirius", "Nouveau Sirius"), false, replyTo = "q1")
        state = withHudAudio(state, "r1", "Nouveau Sirius", "0", "q1")
        assertEquals(lockedIds, visibleHudMessages(state).map { it.id })
        assertEquals(4, state.hudMessages.size)
        assertEquals(listOf("old-tom", "old-sirius", "q1", "s1"), state.messages.map { it.id })
        val unlocked = withLockState(state, false)
        assertEquals(state.messages, unlocked.messages)
        assertEquals(4, visibleHudMessages(unlocked).size)
        assertTrue(visibleHudMessages(withLockState(unlocked, true)).isEmpty())
    }

    @Test fun preLockPartialAndReplyStayHiddenWhenTheirTextFinishesAfterLock() {
        var state = withLiveTranscript(beginHudUtterance(VoiceState(conversation = true), "before"), "Avant", true)
        state = withHudAudio(state, "r-before", "Réponse avant", "0")
        state = withLockState(state, true)
        state = withServerMessage(state, ChatMessage("q-before", "tom", "Phrase terminée"), true, "before")
        state = withHudAudio(state, "r-before", "Suite après", "1")
        assertTrue(visibleHudMessages(state).isEmpty())
        assertEquals(2, state.hudMessages.size)
        state = withHudAudio(state, "r-new", "Nouvelle réponse après", "0")
        assertEquals("r-new", visibleHudMessages(state).single().id)
    }

    @Test fun engineDetectsLockBeforeFirstNewMessageAndReconnectNeverRevealsOldHistory() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val engine = VoiceEngine(context, Settings(context))
        VoiceEngine::class.java.getDeclaredField("listening").apply { isAccessible = true }.set(engine, true)
        @Suppress("UNCHECKED_CAST")
        val flow = VoiceEngine::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(engine) as MutableStateFlow<VoiceState>
        flow.value = VoiceState(conversation = true)
        val old = message("old", "tom", "Avant verrouillage")
        event(engine, old)
        event(engine, JSONObject().put("type", "hello").put("messages", JSONArray().put(old)))
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        // No broadcast or refresh call: the next thread mutation must notice the lock first.
        val tom = message("new-tom", "tom", "Après verrouillage")
        val sirius = message("new-sirius", "sirius", "Je suis là").put("reply_to", "new-tom")
        event(engine, tom)
        event(engine, sirius)
        assertTrue(engine.state.value.locked)
        assertEquals(listOf("new-tom", "new-sirius"), visibleHudMessages(engine.state.value).map { it.id })
        val hello = JSONObject().put("type", "hello").put("messages", JSONArray().put(old).put(tom).put(sirius))
        event(engine, hello)
        event(engine, hello)
        assertEquals(3, engine.state.value.messages.size)
        assertEquals(3, engine.state.value.hudMessages.size)
        assertEquals(listOf("new-tom", "new-sirius"), visibleHudMessages(engine.state.value).map { it.id })
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        engine.refreshLockState()
        assertEquals(3, visibleHudMessages(engine.state.value).size)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        engine.refreshLockState()
        assertTrue(visibleHudMessages(engine.state.value).isEmpty())
        engine.endConversation()
    }
}
