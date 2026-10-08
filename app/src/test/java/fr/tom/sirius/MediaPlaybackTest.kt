package fr.tom.sirius

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaPlaybackTest {
    @Test fun everyNewSegmentUsesMediaVolumeAndSavedGain() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = Settings(context)
        assertEquals(.6f, settings.siriusVolume, .001f)
        settings.siriusVolume = .23f
        val engine = VoiceEngine(context, Settings(context))
        val first = MediaPlayer()
        val second = MediaPlayer()
        try {
            for (player in listOf(first, second)) {
                engine.configurePlayback(player)
                val output = shadowOf(player)
                assertEquals(AudioAttributes.USAGE_MEDIA, output.audioAttributes.usage)
                assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, output.audioAttributes.contentType)
                assertEquals(AudioManager.STREAM_MUSIC, output.audioAttributes.volumeControlStream)
                assertEquals(.23f, output.leftVolume, .001f)
                assertEquals(.23f, output.rightVolume, .001f)
            }
            val focus = VoiceEngine::class.java.getDeclaredField("focus").apply { isAccessible = true }.get(engine) as AudioFocusRequest
            assertEquals(AudioManager.STREAM_MUSIC, focus.audioAttributes.volumeControlStream)
            val playerField = VoiceEngine::class.java.getDeclaredField("player").apply { isAccessible = true }
            playerField.set(engine, first)
            engine.setSiriusVolume(.12f)
            assertEquals(.12f, shadowOf(first).leftVolume, .001f)
            assertEquals(.12f, Settings(context).siriusVolume, .001f)
            engine.configurePlayback(second)
            assertEquals(.12f, shadowOf(second).leftVolume, .001f)
            playerField.set(engine, null)
        } finally { first.release(); second.release() }
    }
    @Test fun gainIsBoundedAndCanMute() {
        val settings = Settings(ApplicationProvider.getApplicationContext<Application>())
        settings.siriusVolume = -1f
        assertEquals(0f, settings.siriusVolume, .001f)
        settings.siriusVolume = 2f
        assertEquals(1f, settings.siriusVolume, .001f)
        settings.siriusVolume = Float.NaN
        assertEquals(.6f, settings.siriusVolume, .001f)
    }
    @Test fun callModeIsRefusedWithoutChangingTheGlobalMode() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val engine = VoiceEngine(context, Settings(context))
        val audio = context.getSystemService(AudioManager::class.java)
        val check = VoiceEngine::class.java.getDeclaredMethod("checkMediaRoute", MediaPlayer::class.java).apply { isAccessible = true }
        assertEquals(true, check.invoke(engine, null))
        for (mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            audio.mode = mode
            assertEquals(false, check.invoke(engine, null))
            assertEquals(mode, audio.mode)
            assertTrue(engine.state.value.status.contains("Sortie d'appel"))
        }
    }
}
