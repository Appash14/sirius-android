package fr.tom.sirius

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhaseTwoRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bottomFollowsInsertionsAndGrowingBubbleButReaderGetsANewMessageButton() {
        val state = mutableStateOf(VoiceState(conversation = true, hudMessages = (1..20).map {
            HudMessage("m$it", "tom", "Message $it avec plusieurs mots pour remplir le fil.", true)
        }))
        compose.setContent { SiriusTheme(dark = true) {
            LiveHud(state.value, {}, {}, Modifier.fillMaxSize(), animated = false)
        } }
        compose.runOnIdle { state.value = state.value.copy(hudMessages = state.value.hudMessages + HudMessage("r", "sirius", "Début")) }
        compose.onNodeWithText("Début").assertIsDisplayed()
        val grown = "Début " + "la réponse grandit pendant la lecture. ".repeat(8)
        compose.runOnIdle { state.value = state.value.copy(hudMessages = state.value.hudMessages.dropLast(1) + HudMessage("r", "sirius", grown)) }
        compose.onNodeWithText(grown).assertIsDisplayed()
        compose.onNodeWithTag("hud-thread").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { state.value = state.value.copy(hudMessages = state.value.hudMessages + HudMessage("new", "tom", "Nouvelle question")) }
        compose.onNodeWithTag("hud-new-message").assertIsDisplayed().performClick()
        compose.onNodeWithText("Nouvelle question").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(phase = VoicePhase.RECONNECTING) }
        compose.onNodeWithText("Je retrouve ton serveur").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(phase = VoicePhase.LISTENING,
            status = "Pas de réponse après 5 min. Tu peux réessayer.") }
        compose.onNodeWithText("Pas de réponse après 5 min. Tu peux réessayer.").assertIsDisplayed()
    }

    @Test fun tenTurnsUseTheSpaceUnderTheLogoAndEveryTurnCanBeReached() {
        val messages = (1..20).map { HudMessage("turn-$it", if (it % 2 == 0) "sirius" else "tom", "Échange $it. Le fil reste accessible.", true) }
        var density = 1f
        compose.setContent { SiriusTheme(dark = true) {
            density = LocalDensity.current.density
            LiveHud(VoiceState(conversation = true, connected = true, hudMessages = messages), {}, {}, Modifier.fillMaxSize(), animated = false)
        } }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val thread = compose.onNodeWithTag("hud-thread").fetchSemanticsNode().boundsInRoot
        assertTrue(thread.top <= 140 * density)
        assertTrue(thread.height > root.height * .62f)
        val bubbles = compose.onAllNodes(hasTestTag("tom-bubble") or hasTestTag("sirius-bubble"))
            .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.height > 0 }
        assertTrue("At least six visible bubbles after ten exchanges", bubbles.size >= 6)
        assertTrue(bubbles.minOf { it.top } <= thread.top + 20 * density)
        for (message in messages) {
            compose.onNodeWithTag("hud-thread").performScrollToNode(hasText(message.text))
            compose.onNodeWithText(message.text).assertIsDisplayed()
            compose.onAllNodesWithText(message.text).assertCountEquals(1)
        }
    }

    @Test fun veryLongTomAndSiriusBubblesComposeEveryCharacterWithoutEllipsis() {
        val tom = "Début Tom. " + "Je vérifie que cette longue question reste lisible en entier. ".repeat(24) + "Fin Tom."
        val sirius = "Début Sirius. " + "Toute la réponse doit être composée et accessible dans le fil. ".repeat(48) + "Fin Sirius."
        compose.setContent { SiriusTheme(dark = true) {
            LiveHud(VoiceState(conversation = true, hudMessages = listOf(
                HudMessage("u1", "tom", tom, true), HudMessage("r1", "sirius", sirius, true))), {}, {}, animated = false)
        } }
        for (text in listOf(tom, sirius)) {
            compose.onNodeWithTag("hud-thread").performScrollToNode(hasText(text))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("No clipping or ellipsis in the composed text", layout.hasVisualOverflow)
            assertEquals(0, layout.getLineStart(0))
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(layout.lineCount > 20)
            for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
            compose.onAllNodesWithText(text).assertCountEquals(1)
        }
    }
}
