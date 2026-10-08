package fr.tom.sirius

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal enum class HudColor(val top: Int, val bottom: Int) {
    WAIT(0xFF618EB8.toInt(), 0xFF97BCD9.toInt()),
    TOM(0xFF82DDFB.toInt(), 0xFFEAFBFF.toInt()),
    THINKING(0xFF8369DD.toInt(), 0xFF80A4F9.toInt()),
    SIRIUS(0xFFF1A34D.toInt(), 0xFFFFE2A0.toInt())
}

internal fun VoiceState.hudColor() = when (phase) {
    VoicePhase.LISTENING -> if (speechActive) HudColor.TOM else HudColor.WAIT
    VoicePhase.THINKING, VoicePhase.RECONNECTING -> HudColor.THINKING
    VoicePhase.SPEAKING -> HudColor.SIRIUS
    VoicePhase.IDLE -> HudColor.WAIT
}

/** Warm native paints and easing tables without creating a visible assistant window. */
internal fun prewarmHudDrawing() {
    val start = android.os.SystemClock.elapsedRealtime()
    val bitmap = android.graphics.Bitmap.createBitmap(32, 64, android.graphics.Bitmap.Config.ARGB_8888)
    try {
        EdgeGlow().draw(android.graphics.Canvas(bitmap), 32, 64, ScreenOutline(6f, android.graphics.RectF(13f, 2f, 19f, 8f)),
            0f, .3f, FloatArray(HudColor.entries.size).apply { this[HudColor.WAIT.ordinal] = 1f }, 1f, true, 1f, 100f)
        for (time in 0..420 step 16) {
            HudTimeline.veil(time.toFloat(), 0f); HudTimeline.pillProgress(time.toFloat())
        }
    } finally { bitmap.recycle() }
    android.util.Log.i("SiriusAnim", "prechauffage dessin_ms=${android.os.SystemClock.elapsedRealtime() - start}")
}

@Composable
fun LiveHud(state: VoiceState, onMicrophone: () -> Unit, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, animated: Boolean = true, onVolume: (Float) -> Unit = {},
    compact: Boolean = false, onTouchableBounds: (Rect) -> Unit = {},
    motionState: HudMotionState? = null, onExitFinished: () -> Unit = {},
    onCloseRequested: ((String) -> Unit)? = null, pilotPaused: Boolean = false,
    onResumePilot: () -> Unit = {}, onSettings: (() -> Unit)? = null, onPilot: (() -> Unit)? = null,
    accessibilityMissing: Boolean = false, onAccessibility: () -> Unit = {}, edgeGlow: Boolean = true,
    screenOutline: ScreenOutline? = null) {
    val systemMotion = remember { ValueAnimator.areAnimatorsEnabled() }
    // The assistant session re-reads the system setting at each opening (its composition survives hide()).
    val motion = animated && (motionState?.motion ?: systemMotion)
    val sessionDriven = motionState != null
    val ownMotion = remember { HudMotionState().apply { if (!motion) enterMs = HudTimeline.ENTER_MS } }
    val hud = motionState ?: ownMotion
    SideEffect { HudRecompositions.count++ }
    val color = state.hudColor()
    val palette = remember { Array(HudColor.entries.size) { Animatable(if (it == color.ordinal) 1f else 0f) } }
    val weights = remember { FloatArray(HudColor.entries.size) }
    LaunchedEffect(color, motion) {
        coroutineScope {
            palette.forEachIndexed { index, value -> launch {
                val target = if (index == color.ordinal) 1f else 0f
                if (motion) value.animateTo(target, tween(420)) else value.snapTo(target)
            } }
        }
    }
    var closing by remember { mutableStateOf(false) }
    var closeMicrophone by remember { mutableStateOf(false) }
    var seenConversation by remember { mutableStateOf(false) }
    LaunchedEffect(state.conversation) {
        if (state.conversation) {
            closing = false; closeMicrophone = false; seenConversation = true
            // Reopened in the same composition without a session reset: replay the entrance.
            if (hud.exiting) hud.prepareShow(motion)
        } else if (sessionDriven && seenConversation && !hud.exiting) {
            // Delay, server end, Back: the conversation ended outside the HUD, play the exit before hide().
            seenConversation = false; hud.exiting = true
        }
    }
    // Entrance: the first visible frame is the hidden pose, the timeline starts two frames later.
    LaunchedEffect(hud, hud.generation, motion) {
        if (!motion) { hud.enterMs = HudTimeline.ENTER_MS; return@LaunchedEffect }
        if (hud.exiting || hud.enterMs >= HudTimeline.ENTER_MS) return@LaunchedEffect
        withTimeoutOrNull(100) {
            withFrameNanos { }
            withFrameNanos { }
        }
        if (hud.fallbackClock) return@LaunchedEffect
        hud.onEntranceStarted?.invoke()
        val from = hud.enterMs
        animate(from, HudTimeline.ENTER_MS, animationSpec = tween((HudTimeline.ENTER_MS - from).roundToInt().coerceAtLeast(1),
            easing = LinearEasing)) { value, _ -> if (!hud.exiting && !hud.fallbackClock) hud.enterMs = value }
        if (!hud.exiting && !hud.fallbackClock) hud.onEntranceFinished?.invoke()
    }
    // Exit: a close during the entrance freezes it and starts from there, without any jump.
    LaunchedEffect(hud, hud.exiting) {
        if (!hud.exiting) return@LaunchedEffect
        val from = hud.exitMs
        if (motion && from < HudTimeline.EXIT_MS) {
            animate(from, HudTimeline.EXIT_MS, animationSpec = tween((HudTimeline.EXIT_MS - from).roundToInt().coerceAtLeast(1),
                easing = LinearEasing)) { value, _ -> hud.exitMs = value }
        } else {
            hud.exitMs = HudTimeline.EXIT_MS
        }
        hud.onExitFinished?.invoke()
        if (closing) {
            val finish = if (closeMicrophone) onMicrophone else onDismiss
            closing = false; closeMicrophone = false
            finish()
        } else onExitFinished()
    }
    val dismiss: () -> Unit = {
        if (onCloseRequested != null) onCloseRequested("glisser")
        else { closeMicrophone = false; closing = true; seenConversation = false; hud.exiting = true }
    }
    val microphone: () -> Unit = {
        if (state.conversation) {
            if (onCloseRequested != null) onCloseRequested("micro")
            else { closeMicrophone = true; closing = true; seenConversation = false; hud.exiting = true }
        }
        else onMicrophone()
    }
    var volumeOpen by remember { mutableStateOf(false) }
    // The veil stays steady while listening; the border has its own entrance (HudTimeline.border).
    // The entrance itself comes from HudTimeline; this value only follows the conversation afterwards.
    val veil = remember { Animatable(if (state.conversation) 1f else 0f) }
    LaunchedEffect(state.conversation, closing, motion) {
        val targetVeil = if (state.conversation && !closing) 1f else 0f
        if (motion) veil.animateTo(targetVeil, tween(520, easing = FastOutSlowInEasing))
        else veil.snapTo(targetVeil)
    }
    val target = when (state.phase) {
        VoicePhase.LISTENING -> if (state.speechActive) .18f + state.level * .82f else .08f
        VoicePhase.SPEAKING -> .22f + state.playbackLevel * .78f
        VoicePhase.THINKING, VoicePhase.RECONNECTING -> .26f
        VoicePhase.IDLE -> .08f
    }
    val smooth = animateFloatAsState(target, if (motion) tween(130) else tween(0), label = "Niveau des vagues")
    // A separate envelope lets the TOM sun grow from the bottom over 500 ms, regardless of syllable level.
    val tomActive = state.conversation && color == HudColor.TOM
    val tomBloom = remember { Animatable(if (!motion && tomActive) 1f else 0f) }
    LaunchedEffect(tomActive, motion, hud.generation) {
        val presence = if (tomActive) 1f else 0f
        if (motion) tomBloom.animateTo(presence, tween(if (tomActive) 500 else 420, easing = FastOutSlowInEasing))
        else tomBloom.snapTo(presence)
    }
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion) {
        if (motion) {
            var start = 0L
            while (isActive) {
                val frame = withFrameNanos { it }
                if (start == 0L) start = frame
                seconds.floatValue = (frame - start) / 1_000_000_000f
            }
        } else seconds.floatValue = 0f
    }
    val border = remember { EdgeGlow() }
    val view = LocalView.current
    val blur = remember { BlurCache() }
    val bottomLight = remember { VoiceLight() }
    val meterLight = remember { VoiceLight() }
    val lightWeights = remember { FloatArray(HudColor.entries.size) }
    // Read as a boolean: the exit clock itself is only read in the draw phase, no recomposition per frame.
    val exitRunning by remember(hud) { derivedStateOf { hud.exiting && hud.exitMs < HudTimeline.EXIT_MS } }
    val density = LocalDensity.current.density
    val dragThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val dismissGesture = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(onDragStart = { drag = 0f }, onVerticalDrag = { change, amount ->
            change.consume(); drag = (drag + amount).coerceAtLeast(0f)
        }, onDragEnd = { if (drag > dragThreshold) dismiss(); drag = 0f }, onDragCancel = { drag = 0f })
    }
    Box(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
        .then(if (compact) Modifier else Modifier.background(Color(0xFF0B1525)))) {
        if (compact) Canvas(Modifier.fillMaxSize().testTag("hud-veil")) {
            val amount = (if (sessionDriven) 1f else veil.value) * HudTimeline.veil(hud.enterMs, hud.exitMs)
            if (amount > 0f) drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .25f * amount),
                .55f to Color.Black.copy(alpha = .28f * amount),
                1f to Color.Black.copy(alpha = .35f * amount)))
        }
        if (!compact) Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() })
        // Sirius 2.8: the screen border is the first sign of listening. In the assistant session it is lit on the
        // very first frame, before the veil and the pill, whatever the conversation state; it fades out with the exit.
        if (edgeGlow) Canvas(Modifier.fillMaxSize().testTag("hud-border")) {
            val amount = HudTimeline.border(hud.enterMs, hud.exitMs) * (if (sessionDriven) 1f else veil.value)
            for (index in weights.indices) weights[index] = palette[index].value
            border.draw(drawContext.canvas.nativeCanvas, size.width.toInt(), size.height.toInt(),
                screenOutline ?: border.outline(view, density), seconds.floatValue, smooth.value, weights, amount, motion,
                density, hud.enterMs, softContours = !compact)
        }
        if (compact) Canvas(Modifier.fillMaxSize()
            .hudEntrance(hud, motion, blur).testTag("hud-aurora").semantics { contentDescription = "Niveau vocal" }) {
            for (index in lightWeights.indices) lightWeights[index] = palette[index].value
            bottomLight.palette(lightWeights)
            bottomLight.aurora(drawContext.canvas.nativeCanvas, size.width, size.height, density,
                seconds.floatValue, smooth.value, tomBloom.value)
        }
        if (!compact) Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            SiriusStar(state.phase, 0f, Modifier.size(54.dp), animated = false)
            Text("S I R I U S", color = Color(0xFFC5D5E8), style = MaterialTheme.typography.labelSmall)
        }

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 22.dp).padding(top = if (compact) 0.dp else 100.dp, bottom = 14.dp)
            .then(if (compact) Modifier.wrapContentHeight(Alignment.Bottom) else Modifier)
            .onGloballyPositioned { onTouchableBounds(it.boundsInWindow()) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
            val leaving = exitRunning || (sessionDriven && seenConversation && !state.conversation)
            if (accessibilityMissing) androidx.compose.material3.TextButton(onClick = onAccessibility,
                modifier = Modifier.graphicsLayer { alpha = HudTimeline.pillAlpha(hud.enterMs, hud.exitMs) }) {
                Text("Réactiver l'accessibilité pour le pilotage")
            }
            if (pilotPaused || onSettings != null || onPilot != null) Row(Modifier.graphicsLayer { alpha = HudTimeline.pillAlpha(hud.enterMs, hud.exitMs) },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pilotPaused) Text("Session arrêtée. Ta prochaine demande pourra piloter.", style = MaterialTheme.typography.bodySmall)
                if (onSettings != null) androidx.compose.material3.TextButton(onClick = onSettings) { Text("Réglages") }
                if (onPilot != null) androidx.compose.material3.TextButton(onClick = onPilot) { Text("Pilotage") }
            }
            if (state.conversation || leaving) {
                HudThread(state, motion, hud, Modifier.weight(1f, fill = !compact))
                val pending = visibleHudMessages(state).firstOrNull { it.id == state.hudTomId }
                if (state.phase == VoicePhase.LISTENING && (state.locked || state.tomTranscript.isBlank()) &&
                    (pending == null || pending.text.isBlank())) {
                    Text("Je t'écoute…", color = Color(0xFFD9E0F2), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("hud-listening-status")
                            .graphicsLayer { alpha = HudTimeline.listening(hud.enterMs, hud.exitMs) })
                }
            }
            AnimatedVisibility(!compact && volumeOpen, enter = fadeIn(tween(if (motion) 180 else 0)),
                exit = fadeOut(tween(if (motion) 180 else 0))) {
                Surface(onClick = {}, color = Color(0xF10A101B), shape = RoundedCornerShape(22.dp)) {
                    SiriusVolume(state.siriusVolume, onVolume, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            val glow = Color(color.top)
            Box(modifier = Modifier.fillMaxWidth().height(68.dp)
                    .then(if (compact) Modifier else Modifier
                        .background(Color(0xF10A101B), RoundedCornerShape(50))
                        .drawBehind {
                            val amount = HudTimeline.halo(hud.enterMs, hud.exitMs)
                            if (amount > .004f) {
                                val middle = Offset(size.width / 2f, size.height / 2f)
                                val radius = size.width * .6f
                                scale(1f, (size.height * 1.9f) / (2f * radius), pivot = middle) {
                                    drawCircle(Brush.radialGradient(0f to glow.copy(alpha = amount),
                                        .55f to glow.copy(alpha = amount * .35f), 1f to Color.Transparent,
                                        center = middle, radius = radius), radius = radius, center = middle)
                                }
                            }
                        })
                    .hudEntrance(hud, motion, blur)
                    .testTag("hud-pill").then(dismissGesture)) {
                Row(Modifier.fillMaxSize().padding(start = 22.dp, end = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (compact) Spacer(Modifier.weight(1f))
                    else VoiceMeter(smooth, seconds, palette, meterLight, lightWeights,
                        Modifier.weight(1f).padding(horizontal = 12.dp).height(30.dp))
                    Canvas(Modifier.size(16.dp).testTag("hud-connection").semantics {
                        contentDescription = "Connexion au serveur"
                        stateDescription = when {
                            state.connected -> "Connectée"
                            state.connectionInterrupted -> "Interrompue, reconnexion en cours"
                            else -> "En attente"
                        }
                    }) {
                        val tint = when {
                            state.connected -> Color(0xFFADCBB5)
                            state.connectionInterrupted -> Color(0xFFE6AF65)
                            else -> Color(0xFF66788A)
                        }
                        drawCircle(tint, radius = 3.dp.toPx())
                        if (state.connectionInterrupted && !state.connected) drawCircle(tint,
                            radius = 6.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                    if (!compact) Surface(onClick = { volumeOpen = !volumeOpen }, color = Color.Transparent,
                        shape = RoundedCornerShape(50), modifier = Modifier.size(36.dp)
                            .semantics { contentDescription = "Volume de Sirius" }) {
                        Box(contentAlignment = Alignment.Center) { SpeakerGlyph(Modifier.size(18.dp)) }
                    }
                    if (compact) RoundPower(state.conversation, microphone, Modifier.size(48.dp))
                    else RoundMicrophone(state.conversation, microphone, Modifier.size(48.dp))
                }
            }
        }
    }
}

/** Reverse layout anchors new messages to the pill and keeps older messages available to a finger scroll. */
@Composable private fun ColumnScope.HudThread(state: VoiceState, motion: Boolean, hud: HudMotionState, modifier: Modifier) {
    // Also supports isolated preview states. The engine always supplies stable utterance ids.
    val messages = (if (state.locked || state.hudMessages.isNotEmpty()) visibleHudMessages(state) else buildList {
        if (state.tomTranscript.isNotBlank()) add(HudMessage("preview-tom", "tom", state.tomTranscript))
        if (state.subtitleRole == "sirius" && state.subtitle.isNotBlank()) add(HudMessage("preview-sirius", "sirius", state.subtitle))
    // Sirius 2.11.4 (Tom : « ça freeze un peu, chute de fps ») : le HUD ne garde que les 24 derniers messages.
    }).filter { it.text.isNotBlank() }.takeLast(HUD_MAX_MESSAGES)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val latest = messages.lastOrNull()
    val notice = when {
        state.phase == VoicePhase.RECONNECTING || (state.connectionInterrupted && !state.connected) -> "Je retrouve ton serveur"
        state.phase == VoicePhase.THINKING -> "Sirius réfléchit"
        state.phase == VoicePhase.LISTENING -> state.status.takeIf { it.startsWith("Pas de réponse après 5 min.") }
        else -> null
    }
    // Tom, 2.4.1: the thread follows every new line (new bubble, growing text, thinking chip) while it shows
    // the bottom or about one bubble above it. Only a finger scroll that ends higher stops following:
    // new content then shows a small button instead of moving the text Tom is reading.
    var follow by remember { mutableStateOf(true) }
    var unseen by remember { mutableStateOf(false) }
    val near = with(LocalDensity.current) { 120.dp.roundToPx() }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            follow = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset <= near
            // Launched apart: a finger cancelling this scroll must not stop the collector.
            if (follow && unseen) { unseen = false; scope.launch { list.scrollToItem(0) } }
        }
    }
    // Keys include the text length: a last bubble that grows must stay in view, not only a new item.
    LaunchedEffect(messages.size, latest?.id, latest?.text?.length, notice) {
        // A new first item keeps the previous one anchored (key-based position); snap back to the bottom.
        if (follow && !list.isScrollInProgress) list.scrollToItem(0) else unseen = true
    }
    LazyColumn(state = list, reverseLayout = true, modifier = modifier.fillMaxWidth()
        .testTag("hud-thread").graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // Fade clipped history, never the first lines of a fully visible bubble.
            if (list.canScrollForward) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White),
                endY = 32.dp.toPx()), blendMode = BlendMode.DstIn)
        }, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 2.dp)) {
        if (notice != null) item(key = "thinking") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(color = Color(0xF10B111C), shape = RoundedCornerShape(50), modifier = Modifier.testTag("hud-thinking")) {
                    Text(notice, color = Color(0xFFD9E0F2), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
        itemsIndexed(messages.asReversed(), key = { _, message -> message.id }) { index, message ->
            HudBubble(message, motion, index, hud)
        }
    }
    if (unseen && !follow) Surface(onClick = {
        unseen = false; follow = true
        scope.launch { list.scrollToItem(0) }
    }, color = Color(0xF1172C40), shape = RoundedCornerShape(50), modifier = Modifier.testTag("hud-new-message")) {
        Text("Nouveau message ↓", color = Color(0xFFEAF6FF), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

@Composable private fun HudBubble(message: HudMessage, motion: Boolean, index: Int, hud: HudMotionState) {
    val own = message.role == "tom"
    val pop = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(message.id) { if (motion) pop.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Surface(color = if (own) Color(0xF1172C40) else Color(0xF10B111C),
            shape = RoundedCornerShape(22.dp, 22.dp, if (own) 6.dp else 22.dp, if (own) 22.dp else 6.dp),
            modifier = Modifier.fillMaxWidth(.86f).testTag(if (own) "tom-bubble" else "sirius-bubble")
                .graphicsLayer {
                    // Opening cascade from the newest bubble (bottom) upwards, all together at the exit.
                    val t = hud.enterMs; val u = hud.exitMs
                    val rise = HudTimeline.bubbleProgress(t, index)
                    alpha = pop.value * HudTimeline.bubbleAlpha(t, u, index)
                    scaleX = (.94f + .06f * pop.value) * (.97f + .03f * rise); scaleY = scaleX
                    translationY = HudTimeline.BUBBLE_RISE_DP.dp.toPx() * (1f - rise) +
                        HudTimeline.BUBBLE_FALL_DP.dp.toPx() * HudTimeline.bubbleFall(u)
                    transformOrigin = TransformOrigin(if (own) 1f else 0f, 1f)
                }) {
            Text(message.text, color = if (own) Color(0xFFEAF6FF) else Color(0xFFF4F5F7),
                fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp))
        }
    }
}

/** Read clocks in drawing/layer phases, preserving the opening without frame recompositions. */
private fun Modifier.hudEntrance(hud: HudMotionState, motion: Boolean, blur: BlurCache) = graphicsLayer {
    val t = hud.enterMs; val u = hud.exitMs
    val rise = HudTimeline.pillProgress(t)
    val fall = HudTimeline.pillFall(u)
    alpha = HudTimeline.pillAlpha(t, u)
    translationY = HudTimeline.PILL_RISE_DP.dp.toPx() * (1f - rise) + HudTimeline.PILL_FALL_DP.dp.toPx() * fall
    val grow = (.94f + .06f * rise) * (1f - .04f * fall)
    scaleX = grow; scaleY = grow
    transformOrigin = TransformOrigin(.5f, 1f)
    renderEffect = if (motion) blur.get(HudTimeline.PILL_BLUR_DP.dp.toPx() * HudTimeline.pillBlur(t)) else null
}

@Composable private fun VoiceMeter(level: State<Float>, seconds: State<Float>, palette: Array<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>,
    light: VoiceLight, weights: FloatArray, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Niveau vocal" }) {
        for (index in weights.indices) weights[index] = palette[index].value
        light.palette(weights)
        light.wave(drawContext.canvas.nativeCanvas, size.width, size.height * .5f,
            size.height * (.03f + .22f * level.value), size.height * .32f, seconds.value, level.value)
    }
}

@Composable internal fun SiriusVolume(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Volume de Sirius", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${(value * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Gain de la voix de Sirius" })
    }
}

@Composable private fun SpeakerGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val tint = Color(0xFFA7B9CC)
        val w = size.width; val h = size.height
        drawLine(tint, Offset(w * .15f, h * .37f), Offset(w * .15f, h * .63f), 2.dp.toPx())
        drawLine(tint, Offset(w * .15f, h * .37f), Offset(w * .48f, h * .2f), 1.5.dp.toPx())
        drawLine(tint, Offset(w * .15f, h * .63f), Offset(w * .48f, h * .8f), 1.5.dp.toPx())
        drawLine(tint, Offset(w * .48f, h * .2f), Offset(w * .48f, h * .8f), 1.5.dp.toPx())
        drawArc(tint, -50f, 100f, false, Offset(w * .42f, h * .18f),
            androidx.compose.ui.geometry.Size(w * .5f, h * .64f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}

/** Messages shown in the HUD thread; the full history stays in the app. */
internal const val HUD_MAX_MESSAGES = 24
