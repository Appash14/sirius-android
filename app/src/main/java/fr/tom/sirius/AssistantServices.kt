package fr.tom.sirius

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*

class SiriusVoiceService : VoiceInteractionService() {
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onReady() {
        super.onReady()
        active = this
        android.util.Log.i("SiriusAnim", "service pret")
        val app = application as SiriusApp
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        app.engine.onWake = {
            android.util.Log.i("SiriusAnim", "reveil showSession depuis_detection_ms=${SystemClock.elapsedRealtime() - app.engine.wakeAcceptedAt}")
            runCatching { showSession(Bundle().apply {
                putBoolean("wake", true); putLong("wake_accepted_ms", app.engine.wakeAcceptedAt)
            }, 0) }
                .onFailure { app.engine.report("Je t'écoute. La feuille assistant est indisponible.") }
        }
        if (app.settings.wakeEnabled && app.engine.modelStore.installed()) WakeService.start(this)
        app.engine.ensureConnection()
        Looper.myQueue().addIdleHandler {
            warmScope.launch {
                if (!app.engine.state.value.conversation) {
                    withContext(Dispatchers.IO) { app.settings.read() }
                    if (!app.engine.state.value.conversation) withContext(Dispatchers.Default) { prewarmHudDrawing() }
                }
            }
            false
        }
    }
    override fun onShutdown() {
        warmScope.cancel()
        if (active === this) active = null
        val app = application as SiriusApp
        app.engine.onWake = null
        app.engine.endConversation()
        WakeService.stop(this)
        super.onShutdown()
    }
    companion object {
        private var active: SiriusVoiceService? = null
        internal fun openEntry(briefing: Boolean): Boolean {
            val service = active ?: return false
            return runCatching {
                service.showSession(Bundle().apply { putBoolean("briefing", briefing) }, 0)
                true
            }.getOrDefault(false)
        }
    }
    override fun onLaunchVoiceAssistFromKeyguard() { showSession(Bundle(), 0) }
}

class SiriusSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SiriusSession(this)
}

/** A session window has no Activity/Fragment owners. Compose needs all three explicitly. */
internal class SessionOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    init { saved.performAttach(); saved.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
    fun show() { registry.currentState = Lifecycle.State.RESUMED }
    fun hide() { if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.currentState = Lifecycle.State.CREATED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
}

internal class SiriusSession(context: android.content.Context) : VoiceInteractionSession(context) {
    private val app = context.applicationContext as SiriusApp
    private val engine = app.engine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owners = SessionOwners()
    private var content: ComposeView? = null
    private var hadConversation = false
    private val hudBounds = android.graphics.Rect()
    private val motionState = HudMotionState()
    private var cold = false
    private val main = Handler(Looper.getMainLooper())
    private var shown = false
    private var showAt = 0L
    private var invocationAt = 0L
    private var firstDraw = false
    private var beginPending: (() -> Unit)? = null
    private var frames: AssistantFrames? = null
    private var entranceStarted = false
    private var entranceFinished = false
    private var fallbackScale = 1f
    private val endFromEngine: (String) -> Unit = { requestExit(it, false) }
    private val entranceWatchdog = object : Runnable {
        private var fallbackAt = 0L
        override fun run() {
            if (!shown || motionState.exiting || !motionState.motion || entranceFinished) return
            if (!firstDraw) {
                content?.requestLayout(); content?.postInvalidateOnAnimation()
                main.postDelayed(this, 16); return
            }
            if (!motionState.fallbackClock && motionState.enterMs > 0f) return
            if (!motionState.fallbackClock) {
                motionState.fallbackClock = true; fallbackAt = SystemClock.elapsedRealtime()
                startEntrance()
                android.util.Log.i("SiriusAnim", "entree secours attente_ms=${fallbackAt - showAt}")
            }
            motionState.enterMs = ((SystemClock.elapsedRealtime() - fallbackAt) / fallbackScale).coerceAtMost(HudTimeline.ENTER_MS)
            content?.postInvalidateOnAnimation()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            if (motionState.enterMs >= HudTimeline.ENTER_MS) finishEntrance()
            else main.postDelayed(this, 16)
        }
    }
    private fun startEntrance() {
        if (entranceStarted || !shown) return
        entranceStarted = true; frames?.begin("entree")
    }
    private fun finishEntrance() {
        if (entranceFinished || !shown) return
        entranceFinished = true; frames?.end()
    }
    private fun requestExit(reason: String, stop: Boolean = true) {
        if (!shown || motionState.exiting) return
        android.util.Log.i("SiriusAnim", "sortie debut raison=$reason")
        frames?.end(); frames?.begin("sortie")
        motionState.exiting = true; hadConversation = false; beginPending = null
        if (stop) engine.endConversation(reason)
        if (!motionState.motion) { frames?.end(); hide() }
    }
    private val hideForAction: () -> Unit = {
        android.util.Log.i("SiriusAnim", "sortie debut raison=pilotage")
        frames?.end()
        hadConversation = false; hide()
    }
    override fun onCreateContentView(): View {
        val createdAt = SystemClock.elapsedRealtime()
        android.util.Log.i("SiriusAnim", "session contenu debut")
        cold = true
        window?.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC)
            configureSessionWindow(this)
            frames = AssistantFrames(this)
        }
        motionState.onEntranceStarted = ::startEntrance
        motionState.onEntranceFinished = ::finishEntrance
        motionState.onExitFinished = { frames?.end() }
        content = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by engine.state.collectAsState()
                val pilot by app.pilot.state.collectAsState()
                SiriusTheme(dark = true) {
                    AssistantSheet(state, onMicrophone = {
                        if (state.conversation) requestExit("micro")
                        else engine.beginConversation()
                    }, onDismiss = { requestExit("glisser") }, onVolume = engine::setSiriusVolume,
                        onTouchableBounds = { bounds ->
                            val next = android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
                            if (hudBounds != next) { hudBounds.set(next); content?.requestLayout() }
                        }, motionState = motionState,
                        // Delay, server end and Back: hide() exactly when the exit animation ends.
                        onExitFinished = { if (shown && motionState.exiting) hide() }, onCloseRequested = ::requestExitFromHud,
                        pilotPaused = pilot.stopped, onResumePilot = {
                            if (!engine.locked()) { app.pilot.setEnabled(true); engine.pilotChanged() }
                        }, onSettings = { context.startActivity(Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, accessibilityMissing = pilot.enabled && !pilot.accessibility,
                        onAccessibility = {
                            val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                                .putExtra("android.intent.extra.COMPONENT_NAME", android.content.ComponentName(context,
                                    SiriusAccessibilityService::class.java).flattenToString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(detail) }.onFailure {
                                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        })
                }
            }
        }
        content!!.viewTreeObserver.addOnDrawListener(ViewTreeObserver.OnDrawListener {
            if (shown && !firstDraw) {
                firstDraw = true
                val now = SystemClock.elapsedRealtime()
                android.util.Log.i("SiriusAnim", "premier_frame depuis_onshow_ms=${now - showAt} " +
                    "depuis_invocation_ms=${if (invocationAt > 0) (now - invocationAt).toString() else "-"}")
                // The draw is submitted before Keystore, microphone service and socket work.
                content?.post { if (shown) { beginPending?.invoke(); beginPending = null } }
            }
        })
        scope.launch { engine.state.collect { state ->
            if (state.conversation) hadConversation = true
            else if (hadConversation) {
                hadConversation = false
                requestExit("serveur", false)
            }
        } }
        android.util.Log.i("SiriusAnim", "session contenu fin duree_ms=${SystemClock.elapsedRealtime() - createdAt}")
        return content!!
    }
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Do not resize the other app. The entire visible thread and pill receive touch events.
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(hudBounds)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        showAt = SystemClock.elapsedRealtime(); shown = true; firstDraw = false
        entranceStarted = false; entranceFinished = false
        invocationAt = if (args?.getBoolean("wake", false) == true) args.getLong("wake_accepted_ms", 0L)
            else args?.getLong("invocation_time_ms", 0L) ?: 0L
        // Before showWindow(): the first visible frame is the hidden pose, then the HUD animates in.
        val moving = android.animation.ValueAnimator.areAnimatorsEnabled()
        fallbackScale = android.provider.Settings.Global.getFloat(context.contentResolver,
            "animator_duration_scale", 1f).coerceAtLeast(.01f)
        motionState.prepareShow(moving)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        val invocation = args?.getInt("invocation_type", -1) ?: -1
        val source = when {
            args?.getBoolean("wake", false) == true -> "reveil"
            args?.containsKey("briefing") == true -> "appli"
            invocation == 7 -> "assist"
            invocation == 6 -> "power"
            else -> "systeme"
        }
        android.util.Log.i("SiriusAnim", "ouverture source=$source invocation=${if (invocation >= 0) invocation else "-"} " +
            "froid=$cold mouvement=$moving")
        cold = false
        owners.show(); engine.assistantVisible = true; engine.onDeviceAction = hideForAction
        engine.onConversationEnd = endFromEngine
        beginPending = {
            engine.beginConversation(args?.getBoolean("wake", false) == true)
            if (args?.getBoolean("briefing", false) == true) engine.beginBriefing()
            hadConversation = engine.state.value.conversation
        }
        content?.requestLayout(); content?.postInvalidateOnAnimation()
        main.removeCallbacks(entranceWatchdog)
        if (moving) main.postDelayed(entranceWatchdog, 100)
        // Back can end the conversation before the StateFlow collector observes its first open state.
        hadConversation = engine.state.value.conversation
    }
    override fun onLockscreenShown() { engine.refreshLockState() }
    override fun onHide() {
        if (shown && !motionState.exiting && engine.onDeviceAction === hideForAction) {
            android.util.Log.i("SiriusAnim", "sortie debut raison=systeme")
        }
        shown = false; beginPending = null; main.removeCallbacks(entranceWatchdog); frames?.end()
        if (engine.onConversationEnd === endFromEngine) engine.onConversationEnd = null
        owners.hide(); engine.assistantVisible = false
        motionState.resetHidden()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        android.util.Log.i("SiriusAnim", "hide")
        if (engine.onDeviceAction === hideForAction) engine.onDeviceAction = null
        // Android hides/replaces this window during app switches. VoiceEngine owns the conversation.
        super.onHide()
    }
    private fun requestExitFromHud(reason: String) { requestExit(reason) }
    override fun onBackPressed() { requestExit("retour") }
    override fun onDestroy() {
        shown = false; main.removeCallbacks(entranceWatchdog); frames?.close(); frames = null
        if (engine.onConversationEnd === endFromEngine) engine.onConversationEnd = null
        scope.cancel(); owners.destroy(); content?.disposeComposition(); content = null
        engine.assistantVisible = false
        if (engine.onDeviceAction === hideForAction) engine.onDeviceAction = null
        // A deliberate hide for pilotage leaves the audio conversation owned by VoiceEngine.
        super.onDestroy()
    }
}

class SiriusRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onStopListening(listener: Callback?) { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback?) {}
}
