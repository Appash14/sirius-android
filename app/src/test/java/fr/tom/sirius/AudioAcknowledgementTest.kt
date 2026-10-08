package fr.tom.sirius

import android.app.Application
import android.media.MediaPlayer
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioAcknowledgementTest {
    private class Socket(var accepts: Boolean = true) : WebSocket {
        val sent = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("https://example.test/voix/ws").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (accepts) sent += JSONObject(text)
            return accepts
        }
        override fun send(bytes: ByteString) = accepts
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
        fun acks() = sent.filter { it.optString("type") == "audio_ack" }
    }
    private lateinit var engine: VoiceEngine
    private lateinit var socket: Socket
    private fun field(name: String) = VoiceEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) { field(name).set(engine, value) }
    private fun get(name: String) = field(name).get(engine)
    private fun invoke(name: String) { VoiceEngine::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(engine) }
    private fun event(json: JSONObject, bytes: ByteArray? = null) {
        VoiceEngine::class.java.getDeclaredMethod("handleEvent", JSONObject::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(engine, json, bytes)
    }
    private fun audio(index: Int, text: String = "Phrase $index.", id: String = "r1", epoch: Int = 7) = JSONObject()
        .put("type", "audio").put("id", id).put("epoch", epoch).put("index", index)
        .put("mime", "audio/mpeg").put("text", text).put("reply_to", "q1")
    private fun receive(index: Int, text: String = "Phrase $index.") { event(audio(index, text), byteArrayOf(1, 2, 3)) }
    private fun hello(epoch: Int = 7) = JSONObject().put("type", "hello").put("epoch", epoch)
        .put("audio_resume", JSONObject().put("status", "pending").put("replay", true))
        .put("messages", JSONArray())
    private fun player() = get("player") as MediaPlayer
    private fun start() { shadowOf(player()).invokePreparedListener() }
    private fun complete() { shadowOf(player()).invokeCompletionListener() }
    private fun lost() {
        VoiceEngine::class.java.getDeclaredMethod("connectionLost", WebSocket::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(engine, socket, null)
        (get("reconnect") as? Job)?.cancel()
        set("reconnect", null)
    }
    private fun reconnect() {
        socket = Socket(); set("socket", socket)
        VoiceEngine::class.java.getDeclaredMethod("connectionOpened", WebSocket::class.java)
            .apply { isAccessible = true }.invoke(engine, socket)
        assertFalse("ready must wait for hello", socket.sent.any { it.optString("type") == "ready" })
    }
    private fun assertAck(ack: JSONObject, index: Int, epoch: Int = 7) {
        assertEquals(setOf("type", "id", "epoch", "index"), ack.keys().asSequence().toSet())
        assertEquals("audio_ack", ack.getString("type")); assertEquals("r1", ack.getString("id"))
        assertEquals(epoch, ack.getInt("epoch")); assertEquals(index, ack.getInt("index"))
    }
    @Before @Suppress("UNCHECKED_CAST") fun open() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(10_000, -1) }
        val context = ApplicationProvider.getApplicationContext<Application>()
        engine = VoiceEngine(context, Settings(context)); socket = Socket()
        set("socket", socket); set("listening", true); set("awaiting", true); set("epoch", 7)
        set("socketHelloReceived", true)
        (get("mutable") as MutableStateFlow<VoiceState>).value = VoiceState(conversation = true, connected = true)
        event(JSONObject().put("type", "reply_start").put("id", "r1").put("epoch", 7))
    }
    @After fun close() { engine.endConversation() }

    @Test fun everySocketOpeningAnnouncesTheCurrentStopCodeInHello() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = Settings(context)
        reconnect()
        assertEquals(StopWords.DEFAULT_CODE, socket.sent.single { it.optString("type") == "hello" }.getString("code_arret"))
        settings.pilotStopCode = "Sirius repos"
        reconnect()
        val hello = socket.sent.single { it.optString("type") == "hello" }
        assertEquals(setOf("type", "code_arret"), hello.keys().asSequence().toSet())
        assertEquals("Sirius repos", hello.getString("code_arret"))
        assertEquals("hello", socket.sent.first().getString("type"))
        assertFalse(socket.sent.any { it.optString("type") == "ready" })
    }

    @Test fun delayedShortUtteranceInterruptionPreservesNextQuestionAndItsVoice() {
        receive(0); start()
        set("activeQuestion", "old-question")
        @Suppress("UNCHECKED_CAST")
        val waiting = get("waitingQuestions") as MutableMap<String, String?>
        waiting["old"] = "old-question"; waiting["yes"] = null
        event(JSONObject("""{"type":"interrupted","epoch":8,"reason":"new_question","reply_to":null}"""))
        assertEquals(true, get("awaiting")); assertEquals(false, get("rejectAudio"))
        assertEquals(mapOf("yes" to null), waiting)
        event(JSONObject("""{"type":"reply_start","id":"r2","reply_to":"q2","epoch":8}"""))
        event(audio(0, "Oui, je continue.", "r2", 8).put("reply_to", "q2"), byteArrayOf(1, 2, 3))
        assertNotNull(get("player"))
        assertEquals("r2", get("activeReply"))
    }
    @Test fun unrelatedScopedInterruptionCannotMuteThePendingReply() {
        set("activeQuestion", "q1")
        event(JSONObject("""{"type":"interrupted","epoch":8,"reason":"new_question","reply_to":"other"}"""))
        assertEquals(7, get("epoch")); assertEquals(true, get("awaiting")); assertEquals(false, get("rejectAudio"))
        receive(0); assertNotNull(get("player"))
    }
    @Test fun interruptionOlderThanTheStartedReplacementDoesNotCutItsPlayer() {
        set("activeQuestion", "q1")
        event(JSONObject("""{"type":"reply_start","id":"r2","reply_to":"q2","epoch":8}"""))
        event(audio(0, "Oui, je continue.", "r2", 8).put("reply_to", "q2"), byteArrayOf(1, 2, 3))
        start(); val replacement = player()
        event(JSONObject("""{"type":"interrupted","epoch":7,"reason":"new_question","reply_to":"q1"}"""))
        event(JSONObject("""{"type":"interrupted","epoch":8,"reason":"new_question","reply_to":null}"""))
        assertSame(replacement, player())
        assertEquals("q2", get("activeQuestion")); assertEquals(false, get("rejectAudio"))
    }
    @Test fun boundShortQuestionSurvivesInterruptionBeforeItsReplyStarts() {
        @Suppress("UNCHECKED_CAST")
        val waiting = get("waitingQuestions") as MutableMap<String, String?>
        waiting["yes"] = "q2"
        set("activeQuestion", null); set("activeReply", null)
        event(JSONObject("""{"type":"interrupted","epoch":8,"reason":"new_question","reply_to":null}"""))
        assertEquals(mapOf("yes" to "q2"), waiting)
        event(JSONObject("""{"type":"reply_start","id":"r2","reply_to":"q2","epoch":8}"""))
        event(audio(0, "Oui.", "r2", 8).put("reply_to", "q2"), byteArrayOf(1, 2, 3))
        assertNotNull(get("player"))
    }
    @Test fun localCutRejectsMoreOldAudioUntilTheReplacementArrives() {
        receive(0); start()
        engine.interruptForSpeech(500)
        assertNull(get("player"))
        receive(1)
        assertNull("Late audio from the cut reply must not restart the speaker", get("player"))
        assertTrue(get("awaiting") as Boolean)
        event(JSONObject("""{"type":"interrupted","epoch":8,"reason":"new_question","reply_to":null}"""))
        set("awaiting", true); set("rejectAudio", false)
        event(JSONObject("""{"type":"reply_start","id":"r2","reply_to":"q2","epoch":8}"""))
        event(audio(0, "Nouvelle réponse.", "r2", 8), byteArrayOf(1, 2, 3))
        assertNotNull(get("player"))
    }
    @Test fun authoritativeInterruptedStopsPlaybackAndKeepsSpeechTransport() {
        receive(0); start()
        event(JSONObject("""{"type":"interrupted","epoch":8,"reason":"new_question","reply_to":null}"""))
        assertNull(get("player")); assertFalse(get("playing") as Boolean)
        assertTrue(engine.state.value.conversation)
    }
    @Test fun thirtyEightSentencesPlayToCompletionWithEveryAcknowledgement() {
        repeat(38) { receive(it, "Une phrase de cette réponse longue numéro $it.") }
        assertEquals(true, get("awaiting"))
        event(JSONObject("""{"type":"reply_end","id":"r1","reply_to":"q1","epoch":7}"""))
        repeat(38) { start(); complete() }
        assertEquals((0..37).toList(), socket.acks().map { it.getInt("index") })
        assertNull(get("player"))
        assertFalse(engine.state.value.status.contains("Réponse trop longue"))
    }

    @Test fun notificationDucksAndRestoresPlaybackWithoutCancellingItsAcknowledgement() {
        fun focus(change: Int) {
            VoiceEngine::class.java.getDeclaredMethod("audioFocusChanged", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(engine, change)
        }
        receive(0); start()
        val active = player()
        val saved = engine.state.value.siriusVolume
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertSame(active, player())
        assertEquals(true, get("playing"))
        assertEquals(true, get("awaiting"))
        assertEquals(saved * .2f, shadowOf(active).leftVolume, .001f)
        engine.setSiriusVolume(.4f)
        assertEquals(.4f, engine.state.value.siriusVolume, .001f)
        assertEquals(.08f, shadowOf(active).leftVolume, .001f)
        focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(.4f, shadowOf(active).leftVolume, .001f)
        complete()
        assertAck(socket.acks().single(), 0)
    }

    @Test fun normalPlaybackAcknowledgesEachSentenceOnlyAfterCompletion() {
        receive(0); receive(1)
        event(JSONObject().put("type", "reply_end").put("id", "r1").put("epoch", 7))
        assertTrue(socket.acks().isEmpty()) // Neither receipt nor preparation means Tom heard it.
        start(); assertTrue(socket.acks().isEmpty())
        complete(); assertAck(socket.acks().single(), 0)
        start(); assertEquals(1, socket.acks().size)
        complete(); assertEquals(2, socket.acks().size); assertAck(socket.acks().last(), 1)
        assertNull(get("player"))
        assertEquals(false, get("awaiting"))
        receive(0)
        assertNull(get("player")); assertAck(socket.acks().last(), 0)
    }

    @Test fun disconnectMidSentenceResendsOnlyCompletedAcksBeforeReadyAndKeepsBufferedAudio() {
        // A send() returning false still needs to be remembered locally.
        socket.accepts = false
        receive(0); start(); complete()
        receive(1); start(); receive(2)
        val playing = player()
        lost(); reconnect(); event(hello())
        val controls = socket.sent.filter { it.optString("type") in listOf("audio_ack", "ready") }
        assertEquals(listOf("audio_ack", "ready"), controls.map { it.getString("type") })
        assertAck(controls.first(), 0); assertTrue(controls.last().getBoolean("ready"))
        // Replay while phrase 1 is playing and phrase 2 is queued must not duplicate either.
        receive(1); receive(2)
        assertSame(playing, player()); assertEquals(1, (get("queue") as Collection<*>).size)
        assertEquals(1, engine.state.value.hudMessages.size)
        assertEquals("Phrase 0. Phrase 1. Phrase 2.", engine.state.value.hudMessages.single().text)
        complete(); assertAck(socket.acks().last(), 1)
        start(); complete(); assertAck(socket.acks().last(), 2)
        assertEquals(3, socket.acks().size)
    }

    @Test fun sentenceCompletedOfflineIsResentBeforeReady() {
        receive(0); start()
        event(JSONObject().put("type", "reply_end").put("id", "r1").put("reply_to", "q1").put("epoch", 7))
        lost(); complete()
        assertTrue(socket.acks().isEmpty())
        val timing = get("timing") as ConversationTiming
        assertFalse("Finishing buffered audio offline must keep the reconnect window open",
            timing.sleepDue(android.os.SystemClock.elapsedRealtime() + 30_000, false, false, false))
        reconnect(); event(hello())
        val controls = socket.sent.filter { it.optString("type") in listOf("audio_ack", "ready") }
        assertEquals(listOf("audio_ack", "ready"), controls.map { it.getString("type") })
        assertAck(controls.first(), 0)
    }

    @Test fun readyAnnouncementDuringReconnectWaitsForHello() {
        receive(0); start(); complete(); lost(); reconnect()
        invoke("announceReady")
        assertTrue(socket.acks().isEmpty())
        assertFalse(socket.sent.any { it.optString("type") == "ready" })
        event(hello())
        assertAck(socket.acks().single(), 0)
        assertTrue(socket.sent.last().getBoolean("ready"))
    }

    @Test fun stoppedSentenceHasNoAckAndCanReplayFromStartWithoutDuplicatingBubble() {
        receive(0); start()
        val stopped = player()
        lost(); invoke("stopPlayback")
        // A late callback from the released player cannot acknowledge the unfinished phrase.
        shadowOf(stopped).invokeCompletionListener()
        reconnect(); event(hello())
        assertTrue(socket.acks().isEmpty())
        receive(0)
        assertNotSame(stopped, player())
        assertEquals(0, player().currentPosition)
        assertEquals(1, engine.state.value.hudMessages.size)
        assertEquals("Phrase 0.", engine.state.value.hudMessages.single().text)
        start(); complete(); assertAck(socket.acks().single(), 0)
    }

    @Test fun duplicateFramesBeforeDuringAndAfterPlaybackNeverPlayOrAppendTwice() {
        receive(0); val first = player(); receive(0)
        assertSame(first, player()); assertTrue((get("queue") as Collection<*>).isEmpty())
        start(); receive(0); receive(1); receive(1)
        assertSame(first, player()); assertEquals(1, (get("queue") as Collection<*>).size)
        complete(); val second = player(); receive(0)
        assertSame(second, player())
        assertAck(socket.acks().last(), 0) // A played replay is acknowledged again, without playback.
        start(); complete(); receive(1)
        assertNull(get("player")); assertTrue((get("queue") as Collection<*>).isEmpty())
        assertEquals(1, engine.state.value.hudMessages.size)
        assertEquals("Phrase 0. Phrase 1.", engine.state.value.hudMessages.single().text)
    }

    @Test fun blankTextFramesStillDeduplicateAndAcknowledge() {
        receive(0, ""); val first = player(); receive(0, "")
        assertSame(first, player()); assertTrue((get("queue") as Collection<*>).isEmpty())
        start(); complete(); receive(0, "")
        assertNull(get("player")); assertEquals(2, socket.acks().size)
        assertAck(socket.acks().first(), 0)
        assertTrue(engine.state.value.hudMessages.isEmpty())
    }

    @Test fun playbackErrorAndExplicitInterruptNeverAcknowledgeUnfinishedAudio() {
        receive(0); start(); val failed = player()
        shadowOf(failed).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        shadowOf(failed).invokeCompletionListener()
        assertTrue(socket.acks().isEmpty())
        set("awaiting", true); set("rejectAudio", false)
        receive(0); start(); val interrupted = player()
        engine.interruptForSpeech(600)
        shadowOf(interrupted).invokeCompletionListener()
        assertTrue(socket.acks().isEmpty())
        assertNull(get("player"))
    }

    @Test fun newEpochReleasesOldPlaybackAndNeverResendsOldAcks() {
        receive(0); start(); complete(); receive(1); start()
        val stale = player()
        lost(); reconnect(); event(hello(8))
        shadowOf(stale).invokeCompletionListener()
        assertNull(get("player")); assertTrue(socket.acks().isEmpty())
        event(audio(0, epoch = 7), byteArrayOf(1))
        assertNull(get("player"))
        event(audio(0, epoch = 8), byteArrayOf(1))
        start(); complete(); assertAck(socket.acks().single(), 0, 8)
    }
}
