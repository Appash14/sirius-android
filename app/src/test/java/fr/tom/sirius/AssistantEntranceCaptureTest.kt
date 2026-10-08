package fr.tom.sirius

import android.app.Application
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
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
class AssistantEntranceCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(direction: String, time: Int): Float {
        val path = "build/outputs/screenshots/hud-assistant-$direction-$time.png"
        File(path).parentFile!!.mkdirs()
        compose.onRoot().captureRoboImage(path)
        val bitmap = BitmapFactory.decodeFile(path)
        val pixel = bitmap.getPixel(3, bitmap.height / 2)
        bitmap.recycle()
        return android.graphics.Color.red(pixel).toFloat()
    }
    @Test fun hiddenFirstFrameContinuousEntranceAndExitWithoutFrameRecomposition() {
        compose.mainClock.autoAdvance = false
        val hud = HudMotionState().apply { prepareShow(true) }
        var ended = 0
        HudRecompositions.count = 0
        compose.setContent { SiriusTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFFCCDDE8))) {
                // Veil and pill timeline measured at the screen edge; the border is checked in EdgeGlowCaptureTest.
                AssistantSheet(VoiceState(conversation = true, phase = VoicePhase.LISTENING), {}, {},
                    motionState = hud, onExitFinished = { ended++ }, edgeGlow = false)
            }
        } }
        val initial = capture("entree", 0)
        assertEquals(204f, initial, 1f)
        val bounds = compose.onNodeWithTag("hud-pill").fetchSemanticsNode().boundsInRoot
        val zero = BitmapFactory.decodeFile("build/outputs/screenshots/hud-assistant-entree-0.png")
        assertEquals(204, android.graphics.Color.red(zero.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())))
        zero.recycle()
        var previous = initial
        var captured120 = false
        var captured240 = false
        repeat(34) {
            compose.mainClock.advanceTimeByFrame()
            if (!captured120 && hud.enterMs >= 120f) {
                val value = capture("entree", 120); assertTrue(value < previous); previous = value; captured120 = true
            }
            if (!captured240 && hud.enterMs >= 240f) {
                val value = capture("entree", 240); assertTrue(value <= previous); previous = value; captured240 = true
            }
        }
        assertTrue(captured120 && captured240)
        assertEquals(420f, hud.enterMs, .01f)
        val final = capture("entree", 420)
        assertTrue(final < initial - 45)
        assertTrue("LiveHud recomposed ${HudRecompositions.count} times", HudRecompositions.count <= 3)
        capture("sortie", 0)
        compose.runOnUiThread { hud.exiting = true }
        compose.mainClock.advanceTimeBy(160)
        val half = capture("sortie", 130)
        assertTrue(half > final && half < initial)
        assertEquals(0, ended)
        compose.mainClock.advanceTimeBy(160)
        assertEquals(initial, capture("sortie", 260), 1f)
        assertEquals(1, ended)
        compose.mainClock.advanceTimeBy(160)
        assertEquals(1, ended)
    }
    @Test fun disabledAnimationsRenderFinalPoseOnFirstFrame() {
        compose.mainClock.autoAdvance = false
        val hud = HudMotionState().apply { prepareShow(false) }
        compose.setContent { SiriusTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFFCCDDE8))) {
                AssistantSheet(VoiceState(conversation = true), {}, {}, motionState = hud, edgeGlow = false)
            }
        } }
        assertEquals(420f, hud.enterMs, 0f)
        assertTrue(capture("sans-animation", 0) < 159f)
    }
}
