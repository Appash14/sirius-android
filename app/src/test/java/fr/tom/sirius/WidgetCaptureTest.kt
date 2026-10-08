package fr.tom.sirius

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetCaptureTest {
    private fun capture(wide: Boolean, widthDp: Int, name: String) {
        File("build/outputs/screenshots").mkdirs()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val host = FrameLayout(activity)
            val view = SiriusWidget.views(activity, wide).apply(activity, host)
            host.addView(view)
            activity.setContentView(host)
            val density = activity.resources.displayMetrics.density
            val width = (widthDp * density).toInt()
            val height = (72 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            assertEquals(width, view.width)
            assertNotNull(view.findViewById<View>(R.id.widget_talk))
            // Capture the actual RemoteViews drawing at launcher cell bounds. A window traversal
            // otherwise remeasures match_parent roots to the Activity's full display dimensions.
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val output = File("build/outputs/screenshots/widget-$name.png")
                bitmap.captureRoboImage(output.path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.path, bounds)
                assertEquals(width, bounds.outWidth)
                assertEquals(height, bounds.outHeight)
            } finally { bitmap.recycle() }
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun roundOneCell() = capture(false, 72, "1x1")
    @Test fun labelledTwoCells() = capture(true, 156, "2x1")
}
