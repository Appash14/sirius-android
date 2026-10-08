package fr.tom.sirius

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PilotOverlayCaptureTest {
    private val background = 0xFF101418.toInt()
    private fun view(overlay: PilotOverlay, field: String) = PilotOverlay::class.java.getDeclaredField(field)
        .apply { isAccessible = true }.get(overlay) as View

    @Test fun realPilotWindowsDrawSoftRubyUnderTheCameraWithNoBottomAndKeepTheStopButton() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        val w = (390 * density).toInt(); val h = (844 * density).toInt()
        val cx = w / 2f; val cy = 34.5f * density; val ringRadius = 8.5f * density
        for (camera in listOf(true, false)) {
            val outline = ScreenOutline(36f * density,
                if (camera) RectF(cx - 5.5f * density, 0f, cx + 5.5f * density, 40f * density) else null)
            var stops = 0
            val overlay = PilotOverlay(activity, outline) { stops++ }
            overlay.show()
            try {
                val border = view(overlay, "border")
                val pill = view(overlay, "pill") as LinearLayout
                val flags = (border.layoutParams as WindowManager.LayoutParams).flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertTrue((pill.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(background)
                border.layout(0, 0, w, h); border.draw(canvas)
                val name = if (camera) "pilotage-rouge" else "pilotage-rouge-sans-camera"
                val output = File("build/outputs/screenshots/$name.png"); output.parentFile!!.mkdirs()
                bitmap.captureRoboImage(output.path)
                fun red(x: Float, y: Float) = Color.red(bitmap.getPixel(x.toInt(), y.toInt()))
                fun ruby(x: Float, y: Float): Boolean {
                    val pixel = bitmap.getPixel(x.toInt(), y.toInt())
                    return Color.red(pixel) > Color.green(pixel) + 15 && Color.red(pixel) > Color.blue(pixel) + 10
                }
                assertTrue("red light missing on the left", ruby(3f * density, h / 2f))
                assertTrue("red light missing on the right", ruby(w - 4f * density, h / 2f))
                assertTrue("red light missing on top", ruby(w / 3f, 3f * density))
                assertTrue("halo must fade inward smoothly", red(2f * density, h / 2f) > red(6f * density, h / 2f) + 5)
                assertEquals("no inner frame", background, bitmap.getPixel((16 * density).toInt(), h / 2))
                assertEquals("respect the rounded corner", background, bitmap.getPixel(2, 2))
                for (x in 0 until w step 3) for (y in h - (20 * density).toInt() until h step 3)
                    assertEquals("bottom edge at ($x,$y)", background, bitmap.getPixel(x, y))
                assertTrue("side must fade before bottom corner", red(3f * density, h - 115f * density) > red(3f * density, h - 45f * density) + 5)
                if (camera) {
                    assertTrue("border must dip under camera", ruby(cx, cy + ringRadius + 4f * density))
                    for (x in (cx - 3 * density).toInt()..(cx + 3 * density).toInt())
                        for (y in 0..(18 * density).toInt())
                            assertEquals("no bridge over camera", background, bitmap.getPixel(x, y))
                    assertEquals("do not light camera hole", background, bitmap.getPixel(cx.toInt(), cy.toInt()))
                    canvas.drawCircle(cx, cy, 5.5f * density, android.graphics.Paint().apply { color = Color.BLACK })
                }
                // Capture both actual overlay views, including the unchanged caption and ARRÊTER control.
                pill.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
                pill.layout(0, 0, pill.measuredWidth, pill.measuredHeight)
                canvas.save(); canvas.translate((w - pill.width) / 2f, 50f * density); pill.draw(canvas); canvas.restore()
                bitmap.captureRoboImage(output.path); bitmap.recycle()
                assertEquals("Sirius a la main", (pill.getChildAt(1) as TextView).text.toString())
                assertEquals("ARRÊTER", (pill.getChildAt(2) as TextView).text.toString())
                assertTrue(pill.getChildAt(2).performClick())
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, stops)
            } finally { overlay.hide() }
        }
        controller.pause().stop().destroy()
    }
}
