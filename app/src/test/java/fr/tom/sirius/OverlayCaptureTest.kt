package fr.tom.sirius

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Insets
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
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
class OverlayCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun directory() { File("build/outputs/screenshots").mkdirs() }
    private val videoText = "Toujours une barre sombre tout en bas. Il n'y a pas le background qui s'assombrit un peu. " +
        "Il y a les bulles du chat qui ne remontent pas jusque tout en haut."
    private val videoFinal = "Vas-y, je je vais te faire un petit enregistrement d'écran, tu vas voir."

    private fun systemInsets(nav: Int) {
        compose.runOnUiThread {
            configureSessionWindow(compose.activity.window)
            val density = compose.activity.resources.displayMetrics.density
            compose.activity.window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, (24 * density).toInt(), 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, (nav * density).toInt()))
                .setVisible(WindowInsets.Type.systemBars(), true).build())
        }
        compose.waitForIdle()
    }

    @Composable private fun UnderlyingApp() {
        Box(Modifier.fillMaxSize().background(Color(0xFFCCDDE8))) {
            Column(Modifier.padding(horizontal = 32.dp, vertical = 70.dp)) {
                Text("Carnet de Tom", color = Color(0xFF284559), fontSize = 30.sp)
                Text("Idées et prochaines étapes", color = Color(0xFF284559), fontSize = 18.sp)
            }
        }
    }

    @Composable private fun SystemBars(nav: Int) {
        Box(Modifier.fillMaxSize()) {
            Text("09:43", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 3.dp))
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(nav.dp)) {
                if (nav == 24) drawLine(Color.White, Offset(size.width * .36f, size.height * .65f),
                    Offset(size.width * .64f, size.height * .65f), 3.dp.toPx())
                else for (fraction in listOf(.25f, .5f, .75f)) drawCircle(Color.White, 5.dp.toPx(),
                    Offset(size.width * fraction, size.height * .5f))
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("build/outputs/screenshots/hud-$name.png")
    private fun brightness(name: String, yFraction: Float): Float {
        val bitmap = BitmapFactory.decodeFile("build/outputs/screenshots/hud-$name.png")
        val pixel = bitmap.getPixel(2, (bitmap.height * yFraction).toInt().coerceAtMost(bitmap.height - 1))
        return (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3f
    }

    private fun longThread(nav: Int, name: String) {
        val messages = (1..10).map { index -> HudMessage("video-$index", if (index % 2 == 0) "tom" else "sirius",
            "Bulle $index. $videoText", true) }
        var density = 1f
        var touch = androidx.compose.ui.geometry.Rect.Zero
        compose.setContent { SiriusTheme(dark = true) {
            density = LocalDensity.current.density
            Box(Modifier.fillMaxSize()) {
                UnderlyingApp()
                // The veil is measured at the screen edge: the 2.8 border has its own test (EdgeGlowCaptureTest).
                AssistantSheet(VoiceState(conversation = true, connected = true, phase = VoicePhase.LISTENING,
                    hudMessages = messages), {}, {}, animated = false, onTouchableBounds = { touch = it }, edgeGlow = false)
                SystemBars(nav)
            }
        } }
        systemInsets(nav)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val thread = compose.onNodeWithTag("hud-thread").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("hud-pill").fetchSemanticsNode().boundsInRoot
        assertEquals(24 * density, thread.top, density)
        assertTrue(thread.height > root.height * .7f)
        assertEquals((nav + 14) * density, root.bottom - pill.bottom, density)
        assertTrue(touch.top <= thread.top && touch.bottom >= pill.bottom)
        compose.onNodeWithText(messages.last().text).assertIsDisplayed()
        capture("overlay-$name-long")
        assertWholeText(messages.last().text, density)
        assertRegularSpacing(density)
        // The underlying app remains visible below the controls through the veil and radial aurora.
        val bottom = brightness("overlay-$name-long", .995f)
        assertTrue("Bottom must still show the application: $bottom", bottom in 132f..185f)
        assertTrue(brightness("overlay-$name-long", .02f) > brightness("overlay-$name-long", .5f))
        compose.onNodeWithTag("hud-thread").performTouchInput { swipeDown() }
        compose.onNodeWithText(messages.last().text).assertIsNotDisplayed()
        compose.onNodeWithTag("hud-thread").performScrollToNode(hasText(messages.first().text))
        compose.onNodeWithText(messages.first().text).assertIsDisplayed()
        assertWholeText(messages.first().text, density)
        assertRegularSpacing(density)
        capture("overlay-$name-scrolled")
    }

    private fun assertWholeText(text: String, density: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        val layout = layouts.single()
        assertFalse(layout.hasVisualOverflow)
        assertTrue(layout.lineCount > 3)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        val bubble = compose.onNode((hasTestTag("tom-bubble") or hasTestTag("sirius-bubble")) and
            hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(bubble.height >= layout.size.height + 25 * density)
    }

    private fun assertRegularSpacing(density: Float) {
        val bubbles = compose.onAllNodes(hasTestTag("tom-bubble") or hasTestTag("sirius-bubble"))
            .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.height > 0 }.sortedBy { it.top }
        assertTrue(bubbles.size >= 2)
        bubbles.zipWithNext().forEach { (upper, lower) -> assertEquals(10 * density, lower.top - upper.bottom, density) }
    }

    @Test fun gestureNavigationLongThreadVeilAndBottom() = longThread(24, "gestures")
    @Test fun buttonNavigationLongThreadVeilAndBottom() = longThread(48, "buttons")

    @Test fun videoPartialIsReplacedAndLongTextsDoNotOverlap() {
        val current = mutableStateOf(beginHudUtterance(VoiceState(conversation = true, connected = true,
            phase = VoicePhase.LISTENING, speechActive = true), "video-1"))
        var density = 1f
        compose.setContent { SiriusTheme(dark = true) {
            density = LocalDensity.current.density
            Box(Modifier.fillMaxSize()) { UnderlyingApp(); AssistantSheet(current.value, {}, {}, animated = false) }
        } }
        systemInsets(24)
        compose.runOnIdle { current.value = withLiveTranscript(current.value, "Vas-y, je je diffère.", true) }
        val nodeId = compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().id
        compose.runOnIdle {
            current.value = withServerMessage(current.value, ChatMessage("message-1", "tom", videoFinal), true)
            current.value = withLiveTranscript(current.value, "Vas-y, je je vais te faire", true)
        }
        compose.onAllNodesWithTag("tom-bubble").assertCountEquals(1)
        assertEquals(nodeId, compose.onNodeWithTag("tom-bubble").fetchSemanticsNode().id)
        compose.onNodeWithText("Vas-y, je je diffère.").assertDoesNotExist()
        compose.onNodeWithText(videoFinal).assertIsDisplayed()
        compose.runOnIdle {
            current.value = withHudAudio(current.value.copy(speechActive = false, phase = VoicePhase.SPEAKING), "reply-1",
                "Ah, d'accord, tu parles de ma vitesse de parole, je parle trop lentement. " +
                    "Je l'accélère juste après le travail en cours. Envoie l'enregistrement, je regarde aussi le bug.", "0")
            current.value = beginHudUtterance(current.value, "video-2")
            current.value = withLiveTranscript(current.value, videoText, true)
            current.value = withServerMessage(current.value, ChatMessage("message-2", "tom", videoText), true)
        }
        capture("overlay-video-texts")
        assertWholeText(videoText, density)
        assertRegularSpacing(density)
    }

    @Test fun veilAnimatesOnOpenAndCloseAndIsSteadyWhenWaiting() {
        compose.mainClock.autoAdvance = false
        val current = mutableStateOf(VoiceState(conversation = true, phase = VoicePhase.LISTENING))
        var closes = 0
        val finish: () -> Unit = { closes++; current.value = current.value.copy(conversation = false) }
        compose.setContent { SiriusTheme(dark = true) {
            Box(Modifier.fillMaxSize()) {
                UnderlyingApp()
                AssistantSheet(current.value, finish, finish, animated = true, edgeGlow = false)
            }
        } }
        compose.mainClock.advanceTimeBy(600)
        capture("overlay-veil-open")
        val open = brightness("overlay-veil-open", .5f)
        assertTrue(open in 148f..167f)
        compose.runOnIdle { current.value = current.value.copy(phase = VoicePhase.IDLE) }
        compose.mainClock.advanceTimeBy(600)
        capture("overlay-veil-waiting")
        assertEquals(open, brightness("overlay-veil-waiting", .5f), 1f)
        compose.onNodeWithContentDescription("Couper le mode vocal").performClick()
        compose.mainClock.advanceTimeBy(160)
        capture("overlay-veil-closing")
        assertEquals(0, closes)
        assertTrue(brightness("overlay-veil-closing", .5f) > open + 10)
        compose.mainClock.advanceTimeBy(160)
        capture("overlay-veil-closed")
        assertEquals(1, closes)
        assertTrue(brightness("overlay-veil-closed", .5f) > 215f)
    }
}
