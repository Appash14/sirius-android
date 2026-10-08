package fr.tom.sirius

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionWindowTest {
    @Suppress("DEPRECATION")
    private fun checkWindow() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val window = activity.window
        // Reproduce the framework VoiceInteractionSession's extra wrap_content frame.
        val frame = FrameLayout(activity).apply {
            id = android.R.id.content
            fitsSystemWindows = true
            setPadding(0, 24, 0, 48)
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        activity.setContentView(frame)
        window.navigationBarColor = Color.BLACK
        window.navigationBarDividerColor = Color.BLACK
        window.isNavigationBarContrastEnforced = true
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        configureSessionWindow(window)
        assertEquals(Color.TRANSPARENT, window.navigationBarColor)
        assertEquals(Color.TRANSPARENT, window.navigationBarDividerColor)
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
        assertFalse(window.isNavigationBarContrastEnforced)
        assertFalse(window.isStatusBarContrastEnforced)
        assertEquals(0, window.attributes.fitInsetsTypes)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(0f, window.attributes.dimAmount, 0f)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, window.attributes.height)
        assertFalse(frame.fitsSystemWindows)
        assertEquals(0, frame.paddingTop)
        assertEquals(0, frame.paddingBottom)
        assertEquals(Color.TRANSPARENT, (frame.background as ColorDrawable).color)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, frame.layoutParams.height)
        val outer = frame.parent as ViewGroup
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, outer.layoutParams.height)
        controller.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "w390dp-h844dp-night") fun gestureNavigationHasNoSystemScrimOrShortContainer() = checkWindow()
    @Test @Config(qualifiers = "w390dp-h844dp-notnight") fun buttonNavigationDisablesContrastScrimEvenInLightTheme() = checkWindow()
}
