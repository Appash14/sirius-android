package fr.tom.sirius

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InterfaceCaptureTest {
    @get:Rule val compose = createComposeRule()
    @Before fun directory() { File("build/outputs/screenshots").mkdirs() }

    private fun capture(name: String, state: VoiceState, compact: Boolean = true) {
        compose.setContent {
            SiriusTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    FictionalScreen()
                    if (compact) AssistantSheet(state, {}, {}, animated = false)
                    else LiveHud(state, {}, {}, animated = false)
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/screenshots/hud-$name.png")
    }

    @Test fun tomBubbleIsAbovePillOnTheRightAndShowsOnlyServerText() {
        val phrase = "Voilà la transcription finale du serveur."
        compose.setContent {
            SiriusTheme(dark = true) {
                AssistantSheet(VoiceState(conversation = true, phase = VoicePhase.SPEAKING,
                    tomTranscript = phrase, subtitleRole = "tom", subtitle = "mots locaux aléatoires"), {}, {}, animated = false)
            }
        }
        compose.onNodeWithText(phrase).assertIsDisplayed()
        compose.onNodeWithText("mots locaux aléatoires").assertDoesNotExist()
        val bubble = compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("hud-pill").fetchSemanticsNode().boundsInRoot
        assertTrue(bubble.bottom < pill.top)
        assertTrue(bubble.left > pill.left)
        assertEquals(pill.right, bubble.right, 1f)
        assertTrue(compose.onAllNodes(hasText(phrase) and hasAnyAncestor(hasTestTag("hud-pill")))
            .fetchSemanticsNodes().isEmpty())
    }

    @Test fun speechShowsListeningUntilServerFinalAndLockHidesPreviousCards() {
        val current = androidx.compose.runtime.mutableStateOf(VoiceState(conversation = true,
            phase = VoicePhase.LISTENING, speechActive = true, subtitleRole = "tom", subtitle = "erreur Vosk"))
        compose.setContent { SiriusTheme(dark = true) { AssistantSheet(current.value, {}, {}, animated = false) } }
        compose.onNodeWithTag("hud-listening-status").assertIsDisplayed()
        compose.onNodeWithTag("tom-bubble").assertDoesNotExist()
        compose.onAllNodes(hasText("Je t'écoute…") and hasAnyAncestor(hasTestTag("hud-thread"))).assertCountEquals(0)
        compose.runOnIdle { current.value = withLiveTranscript(current.value, "Premier mot", true) }
        compose.onNodeWithTag("hud-listening-status").assertDoesNotExist()
        compose.onNodeWithText("Premier mot").assertIsDisplayed()
        compose.onNodeWithText("erreur Vosk").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(speechActive = false,
            phase = VoicePhase.SPEAKING, tomTranscript = "Phrase comprise", subtitleRole = "sirius", subtitle = "Réponse") }
        compose.onNodeWithText("Phrase comprise").assertIsDisplayed()
        compose.onNodeWithText("Réponse").assertIsDisplayed()
        compose.runOnIdle { current.value = withLockState(current.value, true) }
        compose.onNodeWithTag("tom-bubble").assertDoesNotExist()
        compose.onNodeWithText("Réponse").assertDoesNotExist()
    }

    @Test fun animatedCloseWaitsForWaveExitAndLockMasksTextImmediately() {
        compose.mainClock.autoAdvance = false
        val current = androidx.compose.runtime.mutableStateOf(VoiceState(conversation = true,
            phase = VoicePhase.LISTENING, speechActive = true))
        var ends = 0
        val finish: () -> Unit = { ends++; current.value = current.value.copy(conversation = false) }
        compose.setContent { SiriusTheme(dark = true) { AssistantSheet(current.value, finish, finish, animated = true) } }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("hud-listening-status").assertIsDisplayed()
        compose.onNodeWithTag("tom-bubble").assertDoesNotExist()
        compose.runOnIdle { current.value = withLockState(current.value, true) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("tom-bubble").assertDoesNotExist()
        compose.onNodeWithContentDescription("Couper le mode vocal").performClick()
        compose.mainClock.advanceTimeBy(256)
        compose.runOnIdle { assertEquals(0, ends) }
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle { assertEquals(1, ends) }
    }

    @Test fun reusedSessionClosesOnceAndCanOpenAgain() {
        val current = androidx.compose.runtime.mutableStateOf(VoiceState(conversation = true, phase = VoicePhase.LISTENING))
        var ends = 0
        val finish: () -> Unit = { ends++; current.value = current.value.copy(conversation = false) }
        compose.setContent { SiriusTheme(dark = true) { AssistantSheet(current.value, finish, finish, animated = false) } }
        compose.onNodeWithContentDescription("Couper le mode vocal").performClick()
        compose.runOnIdle { assertEquals(1, ends) }
        compose.runOnIdle { current.value = current.value.copy(conversation = true) }
        compose.onNodeWithContentDescription("Couper le mode vocal").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, ends) }
    }

    @Test fun fullScreenVolumeChangesGainWithoutPuttingTranscriptInThePill() {
        var gain = .6f
        compose.setContent {
            SiriusTheme(dark = true) {
                LiveHud(VoiceState(conversation = true), {}, {}, animated = false, onVolume = { gain = it })
            }
        }
        compose.onNodeWithContentDescription("Volume de Sirius").performClick()
        compose.onNodeWithContentDescription("Gain de la voix de Sirius").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.25f) }
        assertEquals(.25f, gain, .001f)
        compose.onNodeWithTag("hud-pill").assertIsDisplayed()
    }

    @Test fun threadKeepsBothSidesInOrderAndPartialFinalKeepTheSameNode() {
        val current = androidx.compose.runtime.mutableStateOf(beginHudUtterance(
            VoiceState(conversation = true, phase = VoicePhase.LISTENING, speechActive = true), "u1"))
        compose.setContent { SiriusTheme(dark = true) { AssistantSheet(current.value, {}, {}, animated = false) } }
        compose.runOnIdle { current.value = withLiveTranscript(current.value, "Regarde", true) }
        val bubbleId = compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().id
        compose.runOnIdle {
            current.value = withServerMessage(current.value.copy(speechActive = false),
                ChatMessage("m1", "tom", "Regarde ici."), true, "u1")
        }
        assertEquals(bubbleId, compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().id)
        compose.runOnIdle {
            current.value = withLiveTranscript(current.value, "Regarde tardif", true)
            current.value = withServerMessage(current.value, ChatMessage("m1", "tom", "Regarde ici."), false, "u1")
            current.value = withHudAudio(current.value, "r1", "Je vois ton carnet.", "0", "m1")
            current.value = withServerMessage(current.value, ChatMessage("s1", "sirius", "Je vois ton carnet."), true, replyTo = "m1")
        }
        compose.onAllNodesWithTag("tom-bubble").assertCountEquals(1)
        compose.onAllNodesWithTag("sirius-bubble").assertCountEquals(1)
        compose.onNodeWithText("Regarde ici.").assertIsDisplayed()
        compose.onNodeWithText("Je vois ton carnet.").assertIsDisplayed()
        val tom = compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().boundsInRoot
        val sirius = compose.onNodeWithTag("sirius-bubble").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("hud-pill").fetchSemanticsNode().boundsInRoot
        assertTrue(tom.bottom < sirius.top)
        assertTrue(sirius.bottom < pill.top)
        assertTrue(sirius.left < tom.left)
    }

    @Test fun shortCompactHudLeavesTheAppAboveItClickableAndKeepsOlderMessages() {
        var taps = 0
        var bounds = androidx.compose.ui.geometry.Rect.Zero
        val messages = (1..4).map { HudMessage("u$it", if (it % 2 == 0) "sirius" else "tom", "Message $it", true) }
        compose.setContent { SiriusTheme(dark = true) {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.material3.Button(onClick = { taps++ }, modifier = Modifier.align(Alignment.TopCenter)) { Text("Ouvrir le carnet") }
                AssistantSheet(VoiceState(conversation = true, hudMessages = messages), {}, {}, animated = false,
                    onTouchableBounds = { bounds = it })
            }
        } }
        compose.onNodeWithText("Ouvrir le carnet").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, taps); assertTrue(bounds.top > 0f) }
        compose.onNodeWithText("Message 4").assertIsDisplayed()
        compose.onNodeWithTag("hud-thread").performScrollToNode(hasText("Message 1"))
        compose.onNodeWithText("Message 1").assertIsDisplayed()
    }

    @Test fun fullScreenHasOneHeader() {
        compose.setContent { SiriusTheme(dark = true) { LiveHud(VoiceState(conversation = true), {}, {}, animated = false) } }
        compose.onAllNodesWithText("S I R I U S").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Étoile double Sirius").assertCountEquals(1)
    }


    @Test fun listeningStatusStaysOutsideThreadAndPowerShowsVoiceModeState() {
        val current = androidx.compose.runtime.mutableStateOf(beginHudUtterance(
            VoiceState(conversation = true, phase = VoicePhase.LISTENING, speechActive = true), "u1"))
        compose.setContent { SiriusTheme(dark = true) { AssistantSheet(current.value, {}, {}, animated = false) } }
        compose.onNodeWithTag("tom-bubble").assertDoesNotExist()
        compose.onNodeWithTag("hud-listening-status").assertIsDisplayed()
        compose.onNodeWithContentDescription("Couper le mode vocal").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Mode vocal actif"))
        compose.runOnIdle { current.value = withLiveTranscript(current.value, "Ouais", true) }
        compose.onNodeWithTag("hud-listening-status").assertDoesNotExist()
        compose.onNodeWithText("Ouais").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(conversation = false) }
        compose.onNodeWithContentDescription("Couper le mode vocal").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Mode vocal coupé"))
    }

    @Test fun listeningEmpty() = capture("listening-empty", beginHudUtterance(VoiceState(
        connected = true, conversation = true, phase = VoicePhase.LISTENING, speechActive = true), "u1"))

    @Test fun tomFeedback() {
        var state = beginHudUtterance(VoiceState(connected = true, conversation = true,
            phase = VoicePhase.LISTENING, speechActive = true), "u1")
        state = withLiveTranscript(state, "Ouais, l'apparition est", true).copy(speechActive = false)
        state = withServerMessage(state, ChatMessage("m1", "tom", "Ouais, l'apparition est tough."), true)
        state = withLiveTranscript(state, "Ouais, l'apparition est tough", true)
        state = withHudAudio(state, "r1", "On peut reprendre ensemble, à ton rythme.", "0", "m1")
        state = withServerMessage(state, ChatMessage("s1", "sirius", "On peut reprendre ensemble, à ton rythme."), true, replyTo = "m1")
        capture("tom-feedback", state)
        compose.onAllNodesWithTag("tom-bubble").assertCountEquals(1)
        compose.onAllNodesWithTag("sirius-bubble").assertCountEquals(1)
        compose.onNodeWithText("Je t'écoute…").assertDoesNotExist()
    }

    @Test fun thread() = capture("thread", VoiceState(conversation = true, phase = VoicePhase.SPEAKING,
        playbackLevel = .65f, hudMessages = listOf(
            HudMessage("u1", "tom", "On reprend les idées du carnet ?", true),
            HudMessage("r1", "sirius", "Oui. Laquelle veux-tu développer ?", true),
            HudMessage("u2", "tom", "Le voyage, pour commencer.", true),
            HudMessage("r2", "sirius", "D'accord, on regarde les dates ensemble."))))

    @Test fun lockedNewBubblesKeepTheSharedThreadAndHidePreviousTurns() {
        var state = VoiceState(conversation = true, connected = true, phase = VoicePhase.LISTENING)
        state = withServerMessage(state, ChatMessage("old-tom", "tom", "Ancienne question privée"), true)
        state = withServerMessage(state, ChatMessage("old-sirius", "sirius", "Ancienne réponse privée"), true)
        state = withLockState(state, true)
        state = beginHudUtterance(state, "locked-utterance")
        state = withLiveTranscript(state, "Tu m'entends", true)
        state = withServerMessage(state, ChatMessage("new-tom", "tom", "Tu m'entends téléphone verrouillé ?"), true, "locked-utterance")
        state = withHudAudio(state, "new-sirius", "Oui Tom, je t'écoute.", "0", "new-tom")
        state = withServerMessage(state, ChatMessage("new-sirius", "sirius", "Oui Tom, je t'écoute."), true, replyTo = "new-tom")
        capture("verrouille-nouvelles-bulles", state.copy(phase = VoicePhase.SPEAKING, playbackLevel = .5f))
        compose.onNodeWithText("Ancienne question privée").assertDoesNotExist()
        compose.onNodeWithText("Ancienne réponse privée").assertDoesNotExist()
        compose.onNodeWithText("Tu m'entends téléphone verrouillé ?").assertIsDisplayed()
        compose.onNodeWithText("Oui Tom, je t'écoute.").assertIsDisplayed()
        compose.onNodeWithText("Conversation masquée, téléphone verrouillé").assertDoesNotExist()
        compose.onAllNodesWithTag("tom-bubble").assertCountEquals(1)
        compose.onAllNodesWithTag("sirius-bubble").assertCountEquals(1)
        assertEquals(4, state.hudMessages.size)
        assertEquals(4, state.messages.size)
    }

    @Test fun locked() = capture("locked", VoiceState(conversation = true, locked = true,
        hudMessages = listOf(HudMessage("secret", "tom", "Texte privé"))))

    @Test fun full() = capture("full", VoiceState(conversation = true, phase = VoicePhase.SPEAKING,
        playbackLevel = .65f, hudMessages = listOf(HudMessage("u1", "tom", "On reprend les idées du carnet ?", true),
            HudMessage("r1", "sirius", "Oui. Laquelle veux-tu développer ?", true))), compact = false)

    @Test fun reconnecting() = capture("reconnecting", VoiceState(connected = false,
        connectionInterrupted = true, conversation = true, phase = VoicePhase.THINKING,
        status = "Connexion interrompue. Je réessaie…", hudMessages = listOf(HudMessage("u1", "tom", "On reprend les idées du carnet ?", true))))

    @Test fun reconnected() = capture("reconnected", VoiceState(connected = true,
        conversation = true, phase = VoicePhase.THINKING,
        hudMessages = listOf(HudMessage("u1", "tom", "On reprend les idées du carnet ?", true))))

    @Test fun connectionChangesInsidePillWithoutMovingThreadOrShowingTransportText() {
        val current = androidx.compose.runtime.mutableStateOf(VoiceState(connected = true,
            conversation = true, phase = VoicePhase.THINKING,
            hudMessages = listOf(HudMessage("u1", "tom", "Bonjour", true))))
        compose.setContent { SiriusTheme(dark = true) { AssistantSheet(current.value, {}, {}, animated = false) } }
        val before = compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { current.value = current.value.copy(connected = false, connectionInterrupted = true,
            status = "Connexion interrompue. Je réessaie…") }
        compose.onNodeWithTag("hud-connection").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Interrompue, reconnexion en cours"))
        compose.onNodeWithText("Connexion interrompue. Je réessaie…").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().boundsInRoot)
        val indicator = compose.onNodeWithTag("hud-connection").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("hud-pill").fetchSemanticsNode().boundsInRoot
        assertTrue(indicator.left >= pill.left && indicator.right <= pill.right)
        assertTrue(indicator.top >= pill.top && indicator.bottom <= pill.bottom)
        compose.runOnIdle { current.value = current.value.copy(connected = true, connectionInterrupted = false) }
        compose.onNodeWithTag("hud-connection").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Connectée"))
        assertEquals(before, compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun settingsGroupsAndBuildAreVisibleAndPilotButtonWorks() {
        var opens = 0
        compose.setContent { SiriusTheme(dark = true) {
            SettingsPage(ServerSettings("https://example.test/voix/", "tom", ""), VoiceState(modelReady = true),
                {}, {}, {}, {}, {}, {}, onPilot = { opens++ })
        } }
        compose.onNodeWithText("Voix").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/screenshots/settings-voice.png")
        compose.onNodeWithText("Mot d'éveil").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ouvrir le pilotage").performScrollTo().performClick()
        assertEquals(1, opens)
        compose.onNodeWithText("Connexion").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sirius ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}")
            .performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/screenshots/settings-connection.png")
    }

    @Test fun waiting() = capture("waiting", VoiceState(
        status = "Je t'écoute", connected = true, conversation = true,
        phase = VoicePhase.IDLE))

    @Test fun listening() = capture("listening", VoiceState(
        status = "Je t'écoute", connected = true, conversation = true,
        phase = VoicePhase.LISTENING, level = .83f, speechActive = true, tomTranscript = "Dis Sirius, on reprend les idées du carnet"))

    @Test fun thinking() = capture("thinking", VoiceState(
        status = "Sirius réfléchit", connected = true, conversation = true,
        phase = VoicePhase.THINKING, tomTranscript = "Sirius, raconte-moi ce que tu vois ici."))

    @Test fun speaking() = capture("speaking", VoiceState(
        status = "Sirius parle", connected = true, conversation = true,
        phase = VoicePhase.SPEAKING, playbackLevel = .76f,
        tomTranscript = "Sirius, raconte-moi ce que tu vois ici.",
        subtitleRole = "sirius", subtitle = "Je vois ton carnet ouvert. On peut reprendre tes idées une par une."))
}

/** A visible application under the transparent session, with no production data. */
@Composable private fun FictionalScreen() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF172B36), Color(0xFF09151F), Color(0xFF243542))))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 29.dp, vertical = 102.dp)) {
            Text("LUNDI 5 OCTOBRE", color = Color(0xFF91ABB5), fontSize = 12.sp, letterSpacing = 2.sp)
            Text("Ton carnet", color = Color(0xFFE3EDF0), fontSize = 38.sp, modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.fillMaxWidth().padding(top = 40.dp).height(200.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF47636B), Color(0xFF243A49), Color(0xFF121F2B))), RoundedCornerShape(30.dp))) {
                Text("Les idées prennent forme\nquand on leur laisse de l'espace.", color = Color(0xFFC8D7D9), fontSize = 18.sp,
                    lineHeight = 27.sp, modifier = Modifier.align(Alignment.BottomStart).padding(23.dp))
            }
            Text("À GARDER EN TÊTE", color = Color(0xFF8BA5AE), fontSize = 12.sp, letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 34.dp, bottom = 14.dp))
            Box(Modifier.fillMaxWidth().height(98.dp).background(Color(0xFF263A45), RoundedCornerShape(23.dp))) {
                Text("Préparer le voyage\nTrois détails à revoir", color = Color(0xFFBDCFD2), fontSize = 17.sp,
                    lineHeight = 25.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 21.dp))
            }
        }
    }
}
