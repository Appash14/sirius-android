package fr.tom.sirius

import android.app.Application
import android.app.KeyguardManager
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
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
class BriefingPlaybackTest {
    private lateinit var engine: VoiceEngine
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun field(name: String) = VoiceEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun player() = field("player").get(engine) as MediaPlayer
    @Before @Suppress("UNCHECKED_CAST") fun open() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(10_000, -1) }
        engine = VoiceEngine(context, Settings(context))
        field("listening").set(engine, true)
        (field("mutable").get(engine) as MutableStateFlow<VoiceState>).value = VoiceState(conversation = true)
    }
    @After fun close() { engine.endConversation() }

    @Test fun fallbackIsSynthesizedDisplayedAndPlayedWithNoServerAudioAcknowledgement() {
        var spoken = ""
        engine.beginBriefing(fetch = { BRIEFING_UNAVAILABLE }, synthesize = { spoken = it; byteArrayOf(1, 2, 3) })
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(BRIEFING_UNAVAILABLE, spoken)
        assertEquals(BRIEFING_UNAVAILABLE, engine.state.value.hudMessages.single().text)
        val output = player()
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, shadowOf(output).audioAttributes.usage)
        shadowOf(output).invokePreparedListener()
        assertEquals(VoicePhase.SPEAKING, engine.state.value.phase)
        shadowOf(output).invokeCompletionListener()
        assertEquals(VoicePhase.LISTENING, engine.state.value.phase)
        assertEquals(false, field("awaiting").get(engine))
        assertTrue((field("playedAudio").get(engine) as Collection<*>).isEmpty())
    }

    @Test fun lockedEntryNeverFetchesOrSynthesizesBriefing() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        var requests = 0
        engine.beginBriefing(fetch = { requests++; "Texte privé" }, synthesize = { error("Must not synthesize") })
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, requests)
        assertTrue(engine.state.value.status.contains("Déverrouille"))
        assertTrue(engine.state.value.hudMessages.isEmpty())
    }

    @Test fun lockingDuringFetchCancelsItAndIgnoresItsLateResult() {
        val response = CompletableDeferred<String>()
        var synthesis = 0
        engine.beginBriefing(fetch = { response.await() }, synthesize = { synthesis++; byteArrayOf(1) })
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        engine.refreshLockState()
        response.complete("Texte privé arrivé trop tard")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, synthesis)
        assertTrue(engine.state.value.locked)
        assertTrue(engine.state.value.hudMessages.isEmpty())
        assertNull(field("player").get(engine))
    }

    @Test fun lockingDuringPlaybackStopsThePrivateReading() {
        engine.beginBriefing(fetch = { "Bonjour Tom, ton rendez-vous privé est à neuf heures." }, synthesize = { byteArrayOf(1) })
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val output = player()
        shadowOf(output).invokePreparedListener()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        engine.refreshLockState()
        assertNull(field("player").get(engine))
        assertTrue(engine.state.value.locked)
        assertEquals(false, field("awaiting").get(engine))
    }

    @Test fun closingDuringFetchDoesNotReopenTheConversation() {
        val response = CompletableDeferred<String>()
        var synthesis = 0
        engine.beginBriefing(fetch = { response.await() }, synthesize = { synthesis++; byteArrayOf(1) })
        shadowOf(android.os.Looper.getMainLooper()).idle()
        engine.endConversation()
        response.complete("Bonjour")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, synthesis)
        assertFalse(engine.state.value.conversation)
        assertTrue(engine.state.value.hudMessages.isEmpty())
    }
}
