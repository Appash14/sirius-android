package fr.tom.sirius

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Accessibility overlay while Sirius has the hand: a soft ruby light that never takes a touch, and a small
 * pill (Sirius state + ARRÊTER) that replaces the hidden voice HUD. TYPE_ACCESSIBILITY_OVERLAY needs no
 * extra permission and is a trusted window, so touches outside the pill reach the app normally.
 */
internal class PilotOverlay(private val context: Context, private val screenOutline: ScreenOutline? = null,
    private val onStop: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(IDLE) }
    private var border: View? = null
    private var pill: View? = null
    private var label: TextView? = null

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
    private fun px(value: Float) = dp(value).toInt()

    fun show() {
        if (border != null) return
        val frame = PilotBorderView(context, screenOutline)
        windows.addView(frame, WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Sirius a la main"
        })
        border = frame

        val caption = TextView(context).apply {
            setTextColor(TEXT); textSize = 13f; text = "Sirius a la main"; maxLines = 1
        }
        val stop = TextView(context).apply {
            text = "ARRÊTER"; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(STOP) }
            setPadding(px(12f), px(6f), px(12f), px(6f))
            contentDescription = "Arrêter le pilotage de Sirius"
            isClickable = true
            // Leave the click dispatch before the stop removes this window.
            setOnClickListener { main.post { onStop() } }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(PILL); setStroke(px(1f), BORDER) }
            setPadding(px(12f), px(5f), px(5f), px(5f))
            addView(View(context).apply { background = dot }, LinearLayout.LayoutParams(px(8f), px(8f)).apply { marginEnd = px(8f) })
            addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(10f) })
        }
        val statusBar = runCatching { windows.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top }.getOrDefault(px(28f))
        windows.addView(row, WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            fitInsetsTypes = 0
            y = statusBar + px(8f)
            title = "Sirius pastille"
        })
        pill = row; label = caption
    }

    /** The pill doubles as a reduced HUD while the conversation continues behind the piloted app. */
    fun update(conversation: Boolean, phase: VoicePhase) {
        val (wanted, color) = if (!conversation) "Sirius a la main" to IDLE else when (phase) {
            VoicePhase.LISTENING -> "Sirius a la main : je t'écoute" to LISTENING
            VoicePhase.SPEAKING -> "Sirius a la main : je parle" to SPEAKING
            VoicePhase.THINKING -> "Sirius a la main : je réfléchis" to THINKING
            VoicePhase.RECONNECTING -> "Sirius a la main : je me reconnecte" to THINKING
            VoicePhase.IDLE -> "Sirius a la main" to IDLE
        }
        label?.let { if (it.text?.toString() != wanted) it.text = wanted }
        dot.setColor(color)
    }

    fun hide() {
        for (view in listOfNotNull(border, pill)) runCatching { windows.removeViewImmediate(view) }
        border = null; pill = null; label = null
    }

    companion object {
        const val BORDER = 0xFFE34353.toInt()
        private const val PILL = 0xEB0B111C.toInt()
        private const val TEXT = 0xFFF4F5F7.toInt()
        private const val STOP = 0xFFE5484D.toInt()
        private const val IDLE = 0xFF87A6C4.toInt()
        private const val LISTENING = 0xFFD9F5FF.toInt()
        private const val SPEAKING = 0xFFFFD18B.toInt()
        private const val THINKING = 0xFFB5A8F1.toInt()
    }
}

/** Same geometry, camera dip and feathered layers as the HUD, with a slow ruby light and no bottom edge. */
internal class PilotBorderView(context: Context, private val screenOutline: ScreenOutline? = null) : View(context) {
    private val glow = EdgeGlow(Array(HudColor.entries.size) {
        intArrayOf(0xFFE34353.toInt(), 0xFF9E1836.toInt(), 0xFFFF6B76.toInt())
    })
    private val weights = FloatArray(HudColor.entries.size).apply { this[HudColor.WAIT.ordinal] = 1f }
    private val started = SystemClock.elapsedRealtime()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val moving = android.animation.ValueAnimator.areAnimatorsEnabled()
        glow.draw(canvas, width, height, screenOutline ?: glow.outline(this, density),
            (SystemClock.elapsedRealtime() - started) / 1000f, .08f, weights, 1f, moving, density,
            HudTimeline.ENTER_MS, softContours = true, omitBottom = true)
        if (moving && isAttachedToWindow) postInvalidateOnAnimation()
    }
}
