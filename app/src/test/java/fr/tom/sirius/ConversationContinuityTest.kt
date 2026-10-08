package fr.tom.sirius

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SiriusApp::class)
class ConversationContinuityTest {
    @Test fun networkOutageDuringIdleListeningCannotExpireTheConversation() {
        val (engine, socket) = open()
        set(engine, "awaiting", false)
        val timing = get(engine, "timing") as ConversationTiming
        val now = android.os.SystemClock.elapsedRealtime()
        timing.listen(now - ConversationTiming.FOLLOW_UP_MS - 1)
        assertTrue(timing.sleepDue(now, false, false, false))
        VoiceEngine::class.java.getDeclaredMethod("connectionLost", WebSocket::class.java, Integer::class.java)
            .apply { isAccessible = true }.invoke(engine, socket, null)
        assertFalse("An outage must suspend the follow-up timeout", timing.sleepDue(now, false, false, false))
        assertEquals("Question", engine.state.value.hudMessages.single().text)
        assertTrue(engine.state.value.conversation)
        assertEquals(VoicePhase.RECONNECTING, engine.state.value.phase)
        assertEquals("Je retrouve ton serveur", engine.state.value.status)
        engine.endConversation()
    }
    @Test fun rebootedServerWithResetEpochRecoversWithoutClosingVoiceMode() {
        val (engine, socket) = open()
        VoiceEngine::class.java.getDeclaredMethod("connectionLost", WebSocket::class.java, Integer::class.java)
            .apply { isAccessible = true }.invoke(engine, socket, null)
        @Suppress("UNCHECKED_CAST")
        val flow = get(engine, "mutable") as MutableStateFlow<VoiceState>
        flow.value = flow.value.copy(connected = true, connectionInterrupted = false)
        event(engine, """{"type":"hello","epoch":0,"audio_resume":{"status":"none"},"messages":[]}""")
        assertTrue(engine.state.value.conversation)
        assertFalse(get(engine, "awaiting") as Boolean)
        assertEquals(VoicePhase.LISTENING, engine.state.value.phase)
        assertEquals("Question", engine.state.value.hudMessages.single().text)
        assertEquals(0, socket.closes)
        engine.endConversation()
    }
    @Test fun pilotActionDoesNotDiscardAnOverlappingTomUtterance() {
        val (engine, socket) = open()
        set(engine, "speaking", true); set(engine, "liveEnonce", "u1")
        engine.pilotActivity(true)
        assertTrue(get(engine, "speaking") as Boolean)
        assertEquals("u1", get(engine, "liveEnonce"))
        assertTrue(get(engine, "awaiting") as Boolean)
        assertTrue(socket.sent.isEmpty())
        engine.endConversation()
    }
    @Test fun waitingClipAndIgnoredNoiseKeepOriginalQuestionAliveAfter70Seconds() {
        val (engine, _) = open()
        event(engine, """{"type":"reply_start","id":"relance-q1","reply_to":"q1","epoch":7}""")
        event(engine, """{"type":"reply_end","id":"relance-q1","reply_to":"q1","epoch":7}""")
        event(engine, """{"type":"ignored","reply_to":null}""")
        assertEquals(true, get(engine, "awaiting"))
        val timing = get(engine, "timing") as ConversationTiming
        val later = android.os.SystemClock.elapsedRealtime() + 70_000
        assertFalse(timing.sleepDue(later, false, false, true))
        assertFalse(timing.responseDue(later, false))
        assertTrue(engine.state.value.conversation)
        event(engine, """{"type":"reply_start","id":"r1","reply_to":"q1","epoch":7}""")
        event(engine, """{"type":"reply_end","id":"r1","reply_to":"q1","epoch":7}""")
        assertEquals(false, get(engine, "awaiting"))
        engine.endConversation()
    }
    @Test fun legacyServerFinalWithDistinctIdMustMergeThePartialUsingThePostQuestion() {
        val (engine, _) = open()
        @Suppress("UNCHECKED_CAST")
        val flow = get(engine, "mutable") as MutableStateFlow<VoiceState>
        flow.value = withLiveTranscript(beginHudUtterance(flow.value, "phone-2"), "Phrase provisoire", true)
        expect(engine, "phone-2")
        event(engine, """{"type":"fin_de_tour","enonce":"phone-2","id":"voix-2"}""")
        event(engine, """{"type":"message","role":"tom","id":"voix-2","enonce":"e2","text":"Phrase finale","epoch":7}""")
        assertEquals(2, engine.state.value.hudMessages.size) // q1 and q2, no third partial bubble.
        assertEquals("phone-2", engine.state.value.hudMessages.last().id)
        assertEquals("Phrase finale", engine.state.value.hudMessages.last().text)
        engine.endConversation()
    }
    @Test fun postQuestionArrivingAfterFinalAndReplyEndStillMergesAndSettlesTheTurn() {
        val (engine, _) = open()
        @Suppress("UNCHECKED_CAST")
        val flow = get(engine, "mutable") as MutableStateFlow<VoiceState>
        flow.value = withLiveTranscript(beginHudUtterance(flow.value, "phone-2"), "Phrase provisoire", true)
        expect(engine, "phone-2")
        event(engine, """{"type":"message","role":"tom","id":"voix-2","enonce":"e2","text":"Phrase finale","epoch":7}""")
        event(engine, """{"type":"reply_start","id":"r2","reply_to":"voix-2","epoch":7}""")
        event(engine, """{"type":"reply_end","id":"r2","reply_to":"voix-2","epoch":7}""")
        VoiceEngine::class.java.getDeclaredMethod("bindQuestion", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(engine, "phone-2", "voix-2")
        assertEquals(2, engine.state.value.hudMessages.size)
        assertEquals("phone-2", engine.state.value.hudMessages.last().id)
        event(engine, """{"type":"reply_start","id":"r1","reply_to":"q1","epoch":7}""")
        event(engine, """{"type":"reply_end","id":"r1","reply_to":"q1","epoch":7}""")
        assertEquals(false, get(engine, "awaiting"))
        engine.endConversation()
    }
    private class Socket : WebSocket {
        val sent = mutableListOf<JSONObject>()
        var closes = 0
        override fun request() = Request.Builder().url("https://example.test/voix/ws").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += JSONObject(text); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { closes++; return true }
        override fun cancel() { closes++ }
    }
    private fun field(engine: VoiceEngine, name: String) = VoiceEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(engine: VoiceEngine, name: String, value: Any?) { field(engine, name).set(engine, value) }
    private fun get(engine: VoiceEngine, name: String) = field(engine, name).get(engine)
    @Suppress("UNCHECKED_CAST")
    private fun open(): Pair<VoiceEngine, Socket> {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        val engine = app.engine
        val socket = Socket()
        set(engine, "socket", socket); set(engine, "listening", true); set(engine, "awaiting", true)
        set(engine, "epoch", 7)
        val flow = get(engine, "mutable") as MutableStateFlow<VoiceState>
        flow.value = VoiceState(conversation = true, connected = true, phase = VoicePhase.THINKING,
            historyReady = true, hudMessages = listOf(HudMessage("u1", "tom", "Question", true, "q1")),
            messages = listOf(ChatMessage("q1", "tom", "Question")))
        expect(engine, "u1")
        event(engine, """{"type":"message","role":"tom","id":"q1","enonce":"u1","text":"Question","epoch":7}""")
        return engine to socket
    }
    private fun expect(engine: VoiceEngine, enonce: String) {
        VoiceEngine::class.java.getDeclaredMethod("expectReply", String::class.java).apply { isAccessible = true }.invoke(engine, enonce)
    }
    private fun event(engine: VoiceEngine, json: String) {
        VoiceEngine::class.java.getDeclaredMethod("handleEvent", JSONObject::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(engine, JSONObject(json), null)
    }
    @Test fun repeatedShowHideReplacementAndActivityStopKeepSocketAnswerAndThread() {
        val (engine, socket) = open()
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        val before = engine.state.value
        val first = SiriusSession(app)
        first.onShow(null, 0); first.onHide(); first.onDestroy()
        val replacement = SiriusSession(app)
        replacement.onShow(null, 0)
        val stop = android.app.Activity::class.java.getDeclaredMethod("onStop").apply { isAccessible = true }
        stop.invoke(Robolectric.buildActivity(MainActivity::class.java).get())
        assertSame(socket, get(engine, "socket"))
        assertEquals(before, engine.state.value)
        assertEquals(true, get(engine, "awaiting"))
        assertEquals(0, socket.closes)
        assertTrue(socket.sent.isEmpty())
        replacement.onHide(); replacement.onDestroy()
        engine.endConversation()
    }
    @Test fun explicitCloseClosesConversationAndDoesNotSendSpeechInterrupt() {
        val (engine, socket) = open()
        engine.endConversation()
        assertFalse(engine.state.value.conversation)
        assertEquals(1, socket.closes)
        assertFalse(socket.sent.any { it.optString("type") == "interrupt" })
    }
    @Test fun speechCutRequires500msAndPreservesThePendingUtterance() {
        val (engine, socket) = open()
        engine.interruptForSpeech(6000)
        assertTrue(socket.sent.isEmpty())
        assertEquals(true, get(engine, "awaiting"))
        set(engine, "playing", true)
        engine.interruptForSpeech(499)
        assertTrue(socket.sent.isEmpty())
        engine.interruptForSpeech(500)
        assertEquals(false, get(engine, "playing"))
        engine.interruptForSpeech(800)
        assertTrue("The server applies the epoch change when it receives speech", socket.sent.isEmpty())
        assertEquals(true, get(engine, "awaiting"))
        engine.endConversation()
    }
    @Test fun secondQuestionKeepsFirstAnswerAndBothResponsesInThread() {
        val (engine, socket) = open()
        set(engine, "activeQuestion", "q1")
        expect(engine, "u2")
        event(engine, """{"type":"message","role":"tom","id":"q2","enonce":"u2","text":"Autre question","epoch":7}""")
        event(engine, """{"type":"reply_start","id":"r1","reply_to":"q1","epoch":7}""")
        assertEquals("r1", get(engine, "activeReply"))
        event(engine, """{"type":"message","role":"sirius","id":"r1","reply_to":"q1","text":"Première réponse","epoch":7}""")
        event(engine, """{"type":"reply_end","id":"r1","reply_to":"q1","epoch":7}""")
        assertEquals(true, get(engine, "awaiting"))
        event(engine, """{"type":"reply_start","id":"r2","reply_to":"q2","epoch":7}""")
        assertEquals("r2", get(engine, "activeReply"))
        event(engine, """{"type":"message","role":"sirius","id":"r2","reply_to":"q2","text":"Deuxième réponse","epoch":7}""")
        event(engine, """{"type":"reply_end","id":"r2","reply_to":"q2","epoch":7}""")
        assertEquals(false, get(engine, "awaiting"))
        assertEquals(listOf("Première réponse", "Deuxième réponse"), engine.state.value.hudMessages.filter { it.role == "sirius" }.map { it.text })
        assertFalse(socket.sent.any { it.optString("type") == "interrupt" })
        engine.endConversation()
    }
    @Test fun transportLossAndRepeatedHelloPreserveAwaitingAndReconcileHistoryOnce() {
        val (engine, socket) = open()
        VoiceEngine::class.java.getDeclaredMethod("connectionLost", WebSocket::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(engine, socket, null)
        assertEquals(true, get(engine, "awaiting"))
        assertTrue(engine.state.value.connectionInterrupted)
        assertEquals(VoicePhase.RECONNECTING, engine.state.value.phase)
        assertEquals("Je retrouve ton serveur", engine.state.value.status)
        assertEquals(false, get(engine, "rejectAudio"))
        // Reconnect callbacks never interpret the server's sticky lost flag as a new cancellation.
        val hello = """{"type":"hello","epoch":7,"audio_resume":{"status":"lost"},"messages":[{"role":"tom","id":"q1","enonce":"u1","text":"Question","epoch":7},{"role":"sirius","id":"r1","reply_to":"q1","text":"Réponse","epoch":7}]}"""
        event(engine, hello); event(engine, hello)
        assertEquals(true, get(engine, "awaiting"))
        assertEquals(1, engine.state.value.hudMessages.count { it.role == "tom" })
        assertEquals(1, engine.state.value.hudMessages.count { it.role == "sirius" })
        assertEquals(0, socket.closes)
        assertTrue(socket.sent.isEmpty())
        engine.endConversation()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun networkLossDoesNotStopAlreadyBufferedPlayback() {
        val (engine, socket) = open()
        set(engine, "playing", true)
        VoiceEngine::class.java.getDeclaredMethod("connectionLost", WebSocket::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(engine, socket, null)
        assertEquals(true, get(engine, "playing"))
        assertEquals(true, get(engine, "awaiting"))
        assertTrue(socket.sent.isEmpty())
        engine.endConversation()
    }
    @Test fun confirmedServerLossStillRecoversCommittedTextFromPreviousEpochOnce() {
        val (engine, _) = open()
        val hello = """{"type":"hello","epoch":8,"audio_resume":{"status":"lost"},"messages":[{"role":"tom","id":"q1","enonce":"u1","text":"Question","epoch":7},{"role":"sirius","id":"r1","reply_to":"q1","text":"Réponse conservée","epoch":7}]}"""
        event(engine, hello); event(engine, hello)
        assertEquals(1, engine.state.value.hudMessages.count { it.role == "sirius" })
        assertEquals("Réponse conservée", engine.state.value.hudMessages.last().text)
        assertEquals(false, get(engine, "awaiting")) // This cancellation was confirmed by the server.
        expect(engine, "u2")
        event(engine, """{"type":"error","reply_to":"q1","epoch":7}""")
        assertEquals(true, get(engine, "awaiting")) // An error from the lost generation cannot cancel q2.
        engine.endConversation()
    }
}
