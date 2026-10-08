package fr.tom.sirius

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeGlowCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val background = 0xFF101418.toInt()

    private fun capture(name: String): Bitmap {
        val path = "build/outputs/screenshots/hud-bordure-$name.png"
        File(path).parentFile!!.mkdirs()
        compose.onRoot().captureRoboImage(path)
        return BitmapFactory.decodeFile(path)
    }
    private fun sum(pixel: Int) = android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) +
        android.graphics.Color.blue(pixel)
    private fun lit(bitmap: Bitmap, x: Int, y: Int) = sum(bitmap.getPixel(x, y)) > sum(background) + 90
    private fun same(bitmap: Bitmap, x: Int, y: Int) = abs(sum(bitmap.getPixel(x, y)) - sum(background)) <= 6
    // At the top of an open conversation the veil covers the background with 25 % black.
    private fun veiledBackground(bitmap: Bitmap, x: Int, y: Int) =
        abs(sum(bitmap.getPixel(x, y)) - sum(background) * .75f) <= 6f
    private fun coral(pixel: Int): Boolean {
        val red = android.graphics.Color.red(pixel)
        return red > 170 && red - android.graphics.Color.blue(pixel) > 60 && red - android.graphics.Color.green(pixel) > 45
    }
    /** Lit pixels in a band of the screen. */
    private fun litIn(bitmap: Bitmap, xs: IntProgression, ys: IntProgression): Int =
        xs.sumOf { x -> ys.count { y -> lit(bitmap, x, y) } }
    /** Coral pixels in a band of the screen. */
    private fun coralIn(bitmap: Bitmap, xs: IntProgression, ys: IntProgression): Int =
        xs.sumOf { x -> ys.count { y -> coral(bitmap.getPixel(x, y)) } }
    /** Border depth in dp on the upper side, away from the TOM sun rising through the bottom half. */
    private fun depth(bitmap: Bitmap, d: Float): Float {
        var deepest = 0
        for (y in (bitmap.height * .2f).toInt() until (bitmap.height * .4f).toInt() step 3)
            for (x in (36f * d).toInt() downTo 0) if (lit(bitmap, x, y)) { deepest = max(deepest, x); break }
        return deepest / d
    }

    @Test fun borderOpensWithACoralAccentThenFollowsEachStateAndLeavesNoTrace() {
        compose.mainClock.autoAdvance = false
        val metrics = compose.activity.resources.displayMetrics
        val d = metrics.density
        val width = metrics.widthPixels.toFloat()
        // A punch hole centered at the top of the screen, like the S25 front camera.
        val hole = RectF(width / 2f - 5.5f * d, 10.5f * d, width / 2f + 5.5f * d, 21.5f * d)
        val outline = ScreenOutline(36f * d, hole)
        val hud = HudMotionState().apply { prepareShow(true) }
        // First frame of a real opening: the conversation has not started yet.
        val state = mutableStateOf(VoiceState())
        compose.setContent { SiriusTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(background))) {
                AssistantSheet(state.value, {}, {}, motionState = hud, screenOutline = outline)
            }
        } }
        val first = capture("ouverture-0")
        val w = first.width; val h = first.height
        val band = (14f * d).toInt()
        val pill = compose.onNodeWithTag("hud-pill").fetchSemanticsNode().boundsInRoot
        val ringX = (width / 2f + 8.5f * d).toInt(); val ringY = (16f * d).toInt()
        // Sirius 2.10: the light starts at the bottom center, while veil and pill are still hidden.
        for ((x, y) in listOf(w / 2 to h - 4))
            assertTrue("bordure absente en ($x, $y)", lit(first, x, y))
        assertTrue("voile déjà visible", same(first, w / 2, h / 2))
        assertTrue("pilule déjà visible", same(first, pill.center.x.toInt(), pill.center.y.toInt()))
        val neutral = first.getPixel(3, h / 2)
        assertTrue("repos pas neutre", abs(android.graphics.Color.red(neutral) - android.graphics.Color.blue(neutral)) < 40)
        assertEquals("rien en haut à la première image", 0, litIn(first, 0 until w step 2, 0 until band step 2))
        assertEquals("lignes déjà là sur les côtés", 0, litIn(first, 0 until band step 2, 0 until h / 2 step 2))
        first.recycle()

        // Two coral heads climb both sides, then meet at the top and draw the camera ring.
        compose.runOnIdle { state.value = VoiceState(conversation = true, phase = VoicePhase.LISTENING) }
        compose.mainClock.advanceTimeBy(250)
        val running = capture("ouverture-250")
        val left = litIn(running, 0 until band step 2, h / 2 until h step 2)
        val right = litIn(running, w - band until w step 2, h / 2 until h step 2)
        assertEquals("le haut déjà tracé à 250 ms", 0, litIn(running, 0 until w step 2, 0 until band step 2))
        assertTrue("virgule absente à gauche ($left) ou à droite ($right)", left > 20 && right > 20)
        assertTrue("virgules pas symétriques ($left, $right)", left * 2 > right && right * 2 > left)
        running.recycle()
        compose.mainClock.advanceTimeBy(250)
        val landing = capture("ouverture-500")
        assertTrue("têtes pas montées", litIn(landing, 0 until band step 2, 0 until (h * .35f).toInt() step 2) > 20)
        landing.recycle()

        // Then the neutral rest after the whole opening (1.7 s): the border traced, the ring drawn.
        compose.mainClock.advanceTimeBy(1300)
        val restShot = capture("repos")
        val rest = restShot.getPixel(3, h / 2)
        assertTrue(sum(rest) > sum(background) + 90)
        assertTrue(abs(android.graphics.Color.red(rest) - android.graphics.Color.blue(rest)) < 40)
        assertEquals("accent resté au repos", 0, coralIn(restShot, 0 until w step 2, 0 until band step 2) +
            coralIn(restShot, 0 until band step 2, 0 until h step 2) + coralIn(restShot, w - band until w step 2, 0 until h step 2) +
            coralIn(restShot, 0 until w step 2, h - band until h step 2))
        // After the opening the whole border is traced, camera ring included.
        // Sirius 2.11.4 : plus d'anneau hors caméra frontale, seulement la ligne sous la caméra.
        for ((x, y) in listOf(3 to h / 2, w - 4 to h / 2, w / 3 to 3))
            assertTrue("bordure absente au repos en ($x, $y)", lit(restShot, x, y))
        val restDepth = depth(restShot, d)
        // No straight glow bridging the camera dip, anywhere between the ring and the top edge.
        assertEquals("lumière au-dessus de la caméra", 0, litIn(restShot,
            (width / 2f - 4f * d).toInt()..(width / 2f + 4f * d).toInt(), 0..(3f * d).toInt()))
        for (x in (width / 2f - 4f * d).toInt()..(width / 2f + 4f * d).toInt())
            for (y in 0..(3f * d).toInt()) assertTrue("glow resté au-dessus de la caméra", veiledBackground(restShot, x, y))
        restShot.recycle()

        fun shot(name: String): Pair<Int, Float> = capture(name).let { bitmap ->
            val result = bitmap.getPixel(3, bitmap.height / 2) to depth(bitmap, d); bitmap.recycle(); result
        }
        compose.runOnIdle { state.value = state.value.copy(speechActive = true, level = .35f) }
        compose.mainClock.advanceTimeBy(600)
        val (tom, tomDepth) = shot("tom")
        assertTrue("Tom pas bleu", android.graphics.Color.blue(tom) > android.graphics.Color.red(tom) + 30)
        compose.runOnIdle { state.value = state.value.copy(level = 1f) }
        compose.mainClock.advanceTimeBy(600)
        val (loud, loudDepth) = shot("tom-fort")
        assertTrue("Tom fort pas bleu", android.graphics.Color.blue(loud) > android.graphics.Color.red(loud) + 30)
        // The ribbons follow the voice: deeper with a normal voice than at rest, deeper again with a loud one.
        assertTrue("voix normale trop discrète ($tomDepth dp, repos $restDepth dp)", tomDepth > restDepth + 4f)
        assertTrue("voix forte pas plus marquée ($loudDepth dp contre $tomDepth dp)", loudDepth > tomDepth + 4f)
        compose.runOnIdle { state.value = state.value.copy(phase = VoicePhase.THINKING, speechActive = false, level = 0f) }
        compose.mainClock.advanceTimeBy(600)
        val (thinking, _) = shot("reflexion")
        assertTrue("réflexion pas violette", android.graphics.Color.blue(thinking) > android.graphics.Color.green(thinking) + 30)
        compose.runOnIdle { state.value = state.value.copy(phase = VoicePhase.SPEAKING, playbackLevel = .7f) }
        compose.mainClock.advanceTimeBy(600)
        val (sirius, _) = shot("sirius")
        assertTrue("Sirius pas ambre", android.graphics.Color.red(sirius) > android.graphics.Color.blue(sirius) + 30)

        compose.runOnUiThread { hud.exiting = true }
        compose.mainClock.advanceTimeBy(400)
        val closed = capture("sortie")
        for ((x, y) in listOf(3 to closed.height / 2, closed.width - 4 to closed.height / 2, closed.width / 2 to closed.height - 4,
            closed.width / 3 to 3, ringX to ringY, 2 to 2))
            assertTrue("trace restée en ($x, $y)", same(closed, x, y))
        closed.recycle()
    }

    @Test fun samsungCutoutGetsARoundRingOnThePunchHole() {
        compose.mainClock.autoAdvance = false
        val metrics = compose.activity.resources.displayMetrics
        val d = metrics.density
        val cx = metrics.widthPixels / 2f
        // Samsung (S25 Ultra): the cutout rises from the top edge down to the bottom of the punch hole.
        val outline = ScreenOutline(36f * d, RectF(cx - 5.5f * d, 0f, cx + 5.5f * d, 40f * d))
        val hud = HudMotionState().apply { prepareShow(true) }
        compose.setContent { SiriusTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(background))) {
                AssistantSheet(VoiceState(conversation = true, phase = VoicePhase.LISTENING), {}, {}, motionState = hud,
                    screenOutline = outline)
            }
        } }
        compose.mainClock.advanceTimeBy(1500)
        CameraRing.enabled = true
        compose.mainClock.advanceTimeBy(100)
        val shot = capture("anneau-samsung")
        CameraRing.enabled = false
        // Hole of 5.5 dp resting on the bottom of the cutout (center 34.5 dp), ring 3 dp around it: a circle.
        val cy = 34.5f * d; val r = 8.5f * d
        for ((x, y) in listOf(cx - r to cy, cx + r to cy, cx to cy - r, cx to cy + r))
            assertTrue("anneau absent en ($x, $y)", lit(shot, x.toInt(), y.toInt()))
        // No tall pill: nothing along the sides of the old shape above the hole, nothing inside the hole.
        for ((x, y) in listOf(cx - r to 18f * d, cx + r to 18f * d, cx to cy))
            assertTrue("pilule ou trou allumé en ($x, $y)", !lit(shot, x.toInt(), y.toInt()))
        for (x in (cx - 4f * d).toInt()..(cx + 4f * d).toInt())
            for (y in 0..(18f * d).toInt()) assertTrue("glow au-dessus de la caméra Samsung", veiledBackground(shot, x, y))
        shot.recycle()
    }

    @Test fun tomBorderFadesThroughTheBottomCornersWithoutLeavingALine() {
        val border = EdgeGlow()
        val weights = floatArrayOf(0f, 1f, 0f, 0f)
        compose.setContent {
            Canvas(Modifier.fillMaxSize().background(Color(background))) {
                border.draw(drawContext.canvas.nativeCanvas, size.width.toInt(), size.height.toInt(),
                    ScreenOutline(36f * density), 4f, .9f, weights, 1f, false, density, HudTimeline.ENTER_MS)
            }
        }
        val bitmap = capture("tom-sans-bord-inferieur")
        val d = compose.activity.resources.displayMetrics.density
        val h = bitmap.height; val w = bitmap.width
        for (x in 0 until w step 3) for (y in h - (20f * d).toInt() until h step 3)
            assertTrue("trait inférieur resté en ($x, $y)", same(bitmap, x, y))
        assertTrue("côté effacé trop haut", lit(bitmap, 3, h / 2))
        // A gradual fade down the straight side before the bottom corner, rather than a clipped endpoint.
        val upper = sum(bitmap.getPixel(3, h - (115f * d).toInt()))
        val lower = sum(bitmap.getPixel(3, h - (65f * d).toInt()))
        assertTrue("coin inférieur sans fondu", upper > lower + 30 && lower > sum(background) + 10)
        bitmap.recycle()
    }
}
