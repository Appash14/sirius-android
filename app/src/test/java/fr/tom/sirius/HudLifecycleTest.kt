package fr.tom.sirius

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HudLifecycleTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun closingKeepsHistoryButRejectedWakeClearsTheThreadSilently() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val engine = VoiceEngine(context, Settings(context))
        val flow = VoiceEngine::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(engine) as MutableStateFlow<VoiceState>
        val initial = VoiceState(conversation = true, hudTomId = "u1", hudMessages = listOf(HudMessage("u1", "tom", "Texte privé")))
        flow.value = initial
        engine.endConversation()
        assertFalse(engine.state.value.conversation)
        assertEquals(initial.hudMessages, engine.state.value.hudMessages)
        assertNull(engine.state.value.hudTomId)
        flow.value = initial
        VoiceEngine::class.java.getDeclaredField("listening").apply { isAccessible = true }.setBoolean(engine, true)
        VoiceEngine::class.java.getDeclaredField("wakeConversation").apply { isAccessible = true }.setBoolean(engine, true)
        val handle = VoiceEngine::class.java.getDeclaredMethod("handleEvent", JSONObject::class.java, ByteArray::class.java).apply { isAccessible = true }
        handle.invoke(engine, JSONObject().put("type", "reveil_rejete"), null)
        assertFalse(engine.state.value.conversation)
        assertTrue(engine.state.value.hudMessages.isEmpty())
        assertEquals(VoicePhase.IDLE, engine.state.value.phase)
    }
    @Test fun mediumIsTheFreshDefaultAndExplicitChoiceSurvivesRestart() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = Settings(context)
        assertEquals(WakeSensitivity.MOYENNE, settings.wakeSensitivity)
        settings.wakeSensitivity = WakeSensitivity.FAIBLE
        assertEquals(WakeSensitivity.FAIBLE, Settings(context).wakeSensitivity)
    }
}
