package fr.tom.sirius

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PilotStopCodeTest {
    @Test fun onlyTheWholeConfiguredCodeStopsAndItSurvivesRestart() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = Settings(context)
        assertEquals("Sirius halte", settings.pilotStopCode)
        assertTrue(StopWords.matchesCode("SIRIUS HALTE !", settings.pilotStopCode))
        for (phrase in listOf("stop", "arrête", "pourquoi ça s'est arrêté", "Sirius halte pourquoi", "halte",
            "Sirius halte ne marche pas", "dis Sirius halte", "Sirius halte puis ouvre Telegram", "Sirius halter", "[unk] sirius halte",
            "le pilotage s'arrête tout seul", "pourquoi tu arrêtes Sirius", "Sirius halte " + "une phrase normale ".repeat(50))) {
            assertFalse(phrase, StopWords.matchesCode(phrase, settings.pilotStopCode))
        }
        assertFalse(StopWords.validCode("stop"))
        settings.pilotStopCode = "Sirius pause téléphone"
        assertEquals(settings.pilotStopCode, Settings(context).pilotStopCode)
        assertFalse(StopWords.matchesCode("Sirius halte", settings.pilotStopCode))
        assertTrue(StopWords.matchesCode("Sirius pause téléphone.", settings.pilotStopCode))
    }
}
