package fr.tom.sirius

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class AssistantBottomCaptureTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String): Bitmap {
        val path = "build/outputs/screenshots/hud-assistant-bas-$name.png"
        File(path).parentFile!!.mkdirs()
        compose.onNodeWithTag("hud-aurora").captureRoboImage(path)
        return BitmapFactory.decodeFile(path)
    }

    private fun brightness(bitmap: Bitmap): Float {
        var sum = 0L; var count = 0
        // Avoid the controls: measure the light itself in the bottom left and center.
        for (y in bitmap.height * 85 / 100 until bitmap.height step 6)
            for (x in bitmap.width / 8 until bitmap.width * 2 / 3 step 6) {
                val pixel = bitmap.getPixel(x, y)
                sum += android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
                count++
            }
        return sum.toFloat() / count
    }

    @Test fun bottomIsTransparentWithOnlyPowerAndConnectionAndBothVoicesLightItUp() {
        val state = mutableStateOf(VoiceState(conversation = true, connected = true, phase = VoicePhase.LISTENING))
        var closes = 0
        compose.setContent { SiriusTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                AssistantSheet(state.value, { closes++ }, {}, animated = false, edgeGlow = false)
            }
        } }
        compose.onNodeWithTag("hud-pill").assertIsDisplayed()
        compose.onNodeWithTag("hud-connection").assertIsDisplayed()
        compose.onNodeWithContentDescription("Couper le mode vocal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Étoile double Sirius").assertDoesNotExist()
        compose.onNodeWithContentDescription("Volume de Sirius").assertDoesNotExist()
        compose.onNodeWithContentDescription("Gain de la voix de Sirius").assertDoesNotExist()
        compose.onNodeWithContentDescription("Couper le micro et terminer").assertDoesNotExist()
        val rest = capture("repos")
        compose.runOnIdle { state.value = state.value.copy(speechActive = true, level = .12f) }
        val quiet = capture("tom-faible")
        compose.runOnIdle { state.value = state.value.copy(level = .35f) }
        val normal = capture("tom-normal")
        val quietCore = quiet.getPixel(quiet.width / 2, quiet.height * 94 / 100)
        assertTrue("Voix faible sans cœur blanc", minOf(android.graphics.Color.red(quietCore),
            android.graphics.Color.green(quietCore), android.graphics.Color.blue(quietCore)) < 160)
        compose.runOnIdle { state.value = state.value.copy(level = .9f) }
        val tom = capture("tom")
        assertTrue("Tom doit éclairer davantage que le repos", brightness(tom) > brightness(rest) + 12f)
        assertTrue("La lumière doit suivre le niveau du micro", brightness(tom) > brightness(quiet) + 8f)
        // Feathered top of the aurora, rather than a rectangular or pill backdrop.
        val top = tom.getPixel(tom.width / 2, 2)
        assertTrue(android.graphics.Color.red(top) < 30 && android.graphics.Color.blue(top) < 35)
        // Normal speech lights a broad band a third of the way up the screen, including away from the center.
        for (x in listOf(normal.width / 5, normal.width / 2, normal.width * 4 / 5)) {
            val y = normal.height * 2 / 3
            fun sum(pixel: Int) = android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) +
                android.graphics.Color.blue(pixel)
            assertTrue("Le soleil doit atteindre le tiers bas en voix normale", sum(normal.getPixel(x, y)) > sum(rest.getPixel(x, y)) + 60)
            assertTrue("Une voix forte doit faire monter le halo", sum(tom.getPixel(x, y)) > sum(quiet.getPixel(x, y)) + 30)
        }
        val white = tom.getPixel(tom.width / 2, tom.height * 94 / 100)
        val red = android.graphics.Color.red(white); val green = android.graphics.Color.green(white)
        val blue = android.graphics.Color.blue(white)
        assertTrue("Le cœur TOM doit être blanc lumineux", minOf(red, green, blue) > 160 && maxOf(red, green, blue) - minOf(red, green, blue) < 30)
        compose.runOnIdle { state.value = state.value.copy(phase = VoicePhase.SPEAKING, playbackLevel = .12f) }
        val quietSirius = capture("sirius-faible")
        compose.runOnIdle { state.value = state.value.copy(playbackLevel = .9f) }
        val sirius = capture("sirius")
        assertTrue("La lumière doit suivre le niveau de lecture", brightness(sirius) > brightness(quietSirius) + 8f)
        // Same 65 dp above the bottom as before the TOM canvas was expanded to the whole screen.
        val amber = sirius.getPixel(sirius.width / 2, sirius.height - (sirius.width / 390f * 65f).toInt())
        assertTrue("Sirius doit être ambre", android.graphics.Color.red(amber) > android.graphics.Color.blue(amber) + 10)
        compose.onNodeWithContentDescription("Couper le mode vocal").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
        listOf(rest, quiet, normal, tom, quietSirius, sirius).forEach { it.recycle() }
    }

    @Test fun tomStartsWithAColoredBloomGrowingFromTheBottomOverFiveHundredMilliseconds() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(VoiceState(conversation = true, connected = true, phase = VoicePhase.LISTENING))
        compose.setContent { SiriusTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                AssistantSheet(state.value, {}, {}, edgeGlow = false)
            }
        } }
        // Let the HUD entrance finish before checking the distinct speech entrance.
        compose.mainClock.advanceTimeBy(2400)
        val rest = capture("tom-debut-repos")
        compose.runOnIdle { state.value = state.value.copy(speechActive = true, level = .35f) }
        val start = capture("tom-debut-0")
        assertEquals("Pas de saut sur la première image vocale", brightness(rest), brightness(start), 2f)
        var previous = brightness(start)
        val shots = mutableListOf(rest, start)
        var elapsed = 0L
        for (time in listOf(80L, 160L, 300L, 500L, 700L)) {
            compose.mainClock.advanceTimeBy(time - elapsed)
            elapsed = time
            val shot = capture("tom-debut-$time"); shots.add(shot)
            val light = brightness(shot)
            assertTrue("Fondu vocal qui recule à $time ms ($light contre $previous)", light >= previous - 2f)
            if (time == 80L) assertTrue("Lumière trop brusque à 80 ms", light < brightness(rest) + 25f)
            previous = light
        }
        assertTrue("Le soleil doit grandir pendant le fondu", brightness(shots[5]) > brightness(shots[2]) + 15f)
        fun sum(pixel: Int) = android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) +
            android.graphics.Color.blue(pixel)
        val low = shots[2]; val risen = shots.last()
        val x = risen.width / 2; val y = risen.height * 2 / 3
        assertTrue("Le halo doit monter depuis le bas", sum(risen.getPixel(x, y)) > sum(low.getPixel(x, y)) + 35)
        val colored = risen.getPixel(x, risen.height * 94 / 100)
        assertTrue("Voix normale sans cœur blanc", minOf(android.graphics.Color.red(colored),
            android.graphics.Color.green(colored), android.graphics.Color.blue(colored)) < 160)
        // A strong voice earns the white core through the existing microphone-level smoothing.
        compose.runOnIdle { state.value = state.value.copy(level = .9f) }
        compose.mainClock.advanceTimeBy(300)
        val loud = capture("tom-debut-fort"); shots.add(loud)
        val white = loud.getPixel(x, loud.height * 94 / 100)
        assertTrue("Cœur blanc réservé à la voix forte", minOf(android.graphics.Color.red(white),
            android.graphics.Color.green(white), android.graphics.Color.blue(white)) > 160)
        // Stop and restart during the release: animate from the current opacity instead of resetting it.
        compose.runOnIdle { state.value = state.value.copy(speechActive = false) }
        compose.mainClock.advanceTimeBy(160)
        val release = capture("tom-reprise-avant"); shots.add(release)
        compose.runOnIdle { state.value = state.value.copy(speechActive = true) }
        val restart = capture("tom-reprise-0"); shots.add(restart)
        assertEquals("Reprise sans saut d'opacité", brightness(release), brightness(restart), 2f)
        shots.forEach { it.recycle() }
    }

    @Test fun transparentBottomKeepsTheDownwardDismissGesture() {
        var dismisses = 0
        compose.setContent { SiriusTheme(dark = true) {
            AssistantSheet(VoiceState(conversation = true), {}, { dismisses++ }, animated = false)
        } }
        compose.onNodeWithTag("hud-pill").performTouchInput {
            // Continue beyond the 68 dp control row: touch slop is consumed before the 72 dp dismiss threshold.
            swipe(androidx.compose.ui.geometry.Offset(width * .3f, 1f),
                androidx.compose.ui.geometry.Offset(width * .3f, height * 2f), 300)
        }
        compose.runOnIdle { assertEquals(1, dismisses) }
    }
}
