package fr.tom.sirius

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServerEndOfTurnTest {
    private fun get(engine: VoiceEngine, name: String): Any? = VoiceEngine::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(engine)
    private fun set(engine: VoiceEngine, name: String, value: Any?) = VoiceEngine::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(engine, value)
    @Suppress("UNCHECKED_CAST")
    private fun flow(engine: VoiceEngine) = get(engine, "mutable") as MutableStateFlow<VoiceState>
    private fun engine(): VoiceEngine {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return VoiceEngine(context, Settings(context)).also {
            set(it, "listening", true); set(it, "liveEnonce", "turn1")
            flow(it).value = withLiveTranscript(beginHudUtterance(VoiceState(conversation = true,
                connected = true, phase = VoicePhase.LISTENING, speechActive = true), "turn1"), "Bonjour", true)
        }
    }
    private fun chunk(id: String = "turn1", last: Boolean = false) = LiveChunk(id, 0, ByteArray(640), 1.0, 1.02, 20, false, last)
    private fun send(engine: VoiceEngine, chunk: LiveChunk) = VoiceEngine::class.java.getDeclaredMethod("sendChunk", LiveChunk::class.java)
        .apply { isAccessible = true }.invoke(engine, chunk)
    private fun event(engine: VoiceEngine, json: String) = VoiceEngine::class.java.getDeclaredMethod("handleEvent", JSONObject::class.java, ByteArray::class.java)
        .apply { isAccessible = true }.invoke(engine, JSONObject(json), null)
    @Suppress("UNCHECKED_CAST")
    private fun chunks(engine: VoiceEngine) = get(engine, "chunkQueue") as ArrayDeque<LiveChunk>
    @Suppress("UNCHECKED_CAST")
    private fun questions(engine: VoiceEngine) = get(engine, "waitingQuestions") as Map<String, String?>

    @Test fun serverEndCancelsInflightDropsQueuedAndLatePiecesAndKeepsOneBubble() {
        val engine = engine()
        send(engine, chunk()); send(engine, chunk())
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.test/").build())
        set(engine, "chunkCall", call)
        event(engine, """{"type":"fin_de_tour","enonce":"turn1","id":"q1"}""")
        assertTrue(call.isCanceled())
        assertNull(get(engine, "chunkCall"))
        assertTrue(chunks(engine).isEmpty())
        assertEquals(VoicePhase.THINKING, engine.state.value.phase)
        assertFalse(engine.state.value.speechActive)
        assertEquals(false, get(engine, "speaking"))
        assertEquals(true, get(engine, "awaiting"))
        assertEquals("q1", questions(engine)["turn1"])
        send(engine, chunk(last = true))
        event(engine, """{"type":"fin_de_tour","enonce":"turn1","id":"q1"}""")
        VoiceEngine::class.java.getDeclaredMethod("chunkDone", Call::class.java, LiveChunk::class.java, Int::class.javaObjectType, String::class.java)
            .apply { isAccessible = true }.invoke(engine, call, chunk(), null, "")
        assertTrue(chunks(engine).isEmpty())
        assertEquals(VoicePhase.THINKING, engine.state.value.phase)
        event(engine, """{"type":"message","role":"tom","id":"q1","enonce":"turn1","text":"Bonjour Sirius"}""")
        assertEquals(1, engine.state.value.hudMessages.size)
        assertEquals("Bonjour Sirius", engine.state.value.hudMessages.single().text)
        assertTrue(engine.state.value.hudMessages.single().final)
    }
    @Test fun unrelatedMalformedAndOldEventsHaveNoEffect() {
        val engine = engine(); val before = engine.state.value
        set(engine, "epoch", 2)
        for (json in listOf("""{"type":"fin_de_tour"}""", """{"type":"fin_de_tour","enonce":"other"}""",
            """{"type":"fin_de_tour","enonce":"turn1","epoch":1}""")) event(engine, json)
        assertEquals(before, engine.state.value)
        assertTrue(questions(engine).isEmpty())
    }
    @Test fun delayedEndDropsOnlyEarlierTurnAndKeepsNewSpeech() {
        val engine = engine()
        send(engine, chunk()); send(engine, chunk("turn2"))
        set(engine, "liveEnonce", "turn2")
        flow(engine).value = beginHudUtterance(engine.state.value, "turn2").copy(speechActive = true)
        event(engine, """{"type":"fin_de_tour","enonce":"turn1","id":"q1"}""")
        assertEquals(listOf("turn2"), chunks(engine).map { it.enonce })
        assertEquals(VoicePhase.LISTENING, engine.state.value.phase)
        assertTrue(engine.state.value.speechActive)
        assertNull(get(engine, "serverEndedEnonce"))
    }
    @Test fun endDoesNotInterruptPlaybackAndNextUtteranceCanUpload() {
        val engine = engine(); set(engine, "playing", true)
        flow(engine).value = engine.state.value.copy(phase = VoicePhase.SPEAKING)
        event(engine, """{"type":"fin_de_tour","enonce":"turn1"}""")
        assertEquals(true, get(engine, "playing"))
        assertEquals(VoicePhase.SPEAKING, engine.state.value.phase)
        send(engine, chunk("turn2"))
        assertEquals(listOf("turn2"), chunks(engine).map { it.enonce })
    }
    @Test fun withoutServerEndLocalFinalPieceStillStartsThinking() {
        val engine = engine()
        send(engine, chunk())
        assertEquals(false, get(engine, "awaiting"))
        flow(engine).value = engine.state.value.copy(speechActive = false)
        send(engine, chunk(last = true))
        assertEquals(2, chunks(engine).size)
        assertEquals(true, get(engine, "awaiting"))
        assertEquals(VoicePhase.THINKING, engine.state.value.phase)
        assertNull(get(engine, "serverEndedEnonce"))
    }
    @Test fun captureDropsTailAndRearmsAfterPauseWithoutFinalUpload() {
        val detector = PhraseDetector().apply { live = true }
        val pcm = ByteArray(640)
        repeat(30) { detector.feed(pcm, 1000.0, false) }
        assertTrue(detector.speaking)
        detector.finishByServer()
        assertFalse(detector.speaking)
        repeat(80) { assertNull(detector.feed(pcm, 1000.0, false)) }
        assertFalse(detector.speaking)
        repeat(6) { assertNull(detector.feed(pcm, 0.0, false)) }
        repeat(30) { detector.feed(pcm, 1000.0, false) }
        assertTrue(detector.speaking)
    }
}
