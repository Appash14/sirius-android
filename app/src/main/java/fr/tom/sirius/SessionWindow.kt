package fr.tom.sirius

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat

/** VoiceInteractionSession has framework containers in addition to the Compose content. */
@Suppress("DEPRECATION")
internal fun configureSessionWindow(window: Window) {
    window.apply {
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Sirius draws its own entrance and exit (HudTimeline): no system or OEM window animation on top.
        setWindowAnimations(0)
        setGravity(Gravity.FILL)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = Color.TRANSPARENT
        navigationBarDividerColor = Color.TRANSPARENT
        // Android adds a dark navigation scrim in button mode unless this is explicitly disabled.
        isNavigationBarContrastEnforced = false
        isStatusBarContrastEnforced = false
        WindowCompat.setDecorFitsSystemWindows(this, false)
        WindowCompat.getInsetsController(this, decorView).apply {
            isAppearanceLightNavigationBars = false
            isAppearanceLightStatusBars = false
        }
        attributes = attributes.apply {
            dimAmount = 0f
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }
    fun stretchContent(view: View) {
        if (view.id == android.R.id.content) {
            view.fitsSystemWindows = false
            view.setPadding(0, 0, 0, 0)
            view.setBackgroundColor(Color.TRANSPARENT)
            view.layoutParams = view.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) stretchContent(view.getChildAt(index))
    }
    stretchContent(window.decorView)
}
