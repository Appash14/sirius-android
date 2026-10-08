package fr.tom.sirius

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val Night = Color(0xFF080F20)
private val Ice = Color(0xFFB7D6F8)
private val Silver = Color(0xFFEAF0FA)
private val Mist = Color(0xFF97A9C2)
private val Panel = Color(0xFF15243A)
private val Rounded = RoundedCornerShape(24.dp)

@Composable fun SiriusTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Ice, onPrimary = Night, secondary = Color(0xFF91B2D9),
        background = Night, surface = Color(0xFF0E192B), surfaceVariant = Panel,
        onBackground = Silver, onSurface = Silver, onSurfaceVariant = Mist,
        outline = Color(0xFF33465F), outlineVariant = Color(0xFF233249)
    ) else lightColorScheme(
        primary = Color(0xFF345E8D), onPrimary = Color.White,
        background = Color(0xFFF1F5FB), surface = Color(0xFFF8FAFF), surfaceVariant = Color(0xFFE2EAF5),
        onBackground = Night, onSurface = Night, onSurfaceVariant = Color(0xFF53657D),
        outline = Color(0xFF7992AF), outlineVariant = Color(0xFFCED9E8)
    )
    val base = Typography()
    MaterialTheme(colorScheme = colors, typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontSize = 32.sp, fontWeight = FontWeight.Normal),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Normal),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontSize = 23.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 1.3.sp)
    ), shapes = Shapes(medium = Rounded, large = Rounded)) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

@Composable fun NightBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.background(Brush.verticalGradient(listOf(colors.surface, colors.background, colors.background)))) {
        Canvas(Modifier.fillMaxSize()) {
            val at = Offset(size.width * .65f, size.height * .23f)
            val radius = size.width * .75f
            drawCircle(Brush.radialGradient(listOf(colors.primary.copy(alpha = .045f), Color.Transparent), at, radius), radius, at)
        }
        content()
    }
}

/** Frame-clock animation, no bitmap, timer or layout pass per microphone sample. */
@Composable fun SiriusStar(phase: VoicePhase, level: Float, modifier: Modifier = Modifier, animated: Boolean = true) {
    val color = MaterialTheme.colorScheme.primary
    val animation = rememberInfiniteTransition(label = "Sirius")
    val breath = if (animated) animation.animateFloat(.93f, 1.06f,
        infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Respiration").value else 1f
    val turn = if (animated) animation.animateFloat(0f, 360f,
        infiniteRepeatable(tween(22000, easing = LinearEasing)), label = "Orbite").value else 0f
    val pulse = if (animated) animation.animateFloat(.35f, .8f,
        infiniteRepeatable(tween(720), RepeatMode.Reverse), label = "Rayonnement").value else .55f
    val meter by animateFloatAsState(if (phase == VoicePhase.LISTENING) level else 0f, tween(90), label = "Micro")
    Canvas(modifier.semantics { contentDescription = "Étoile double Sirius" }) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * .45f
        val scale = when (phase) {
            VoicePhase.LISTENING -> breath + meter * .2f
            VoicePhase.SPEAKING -> 1f + pulse * .1f
            else -> breath
        }
        val glow = if (phase == VoicePhase.SPEAKING) .2f + pulse * .12f else .15f + meter * .17f
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glow), color.copy(alpha = .035f), Color.Transparent), center, radius), radius, center)
        softContour(color.copy(alpha = .13f), 7.dp.toPx()) { tint, stroke ->
            drawCircle(tint, radius * .96f, center, style = stroke)
        }
        rotate(-32f + if (phase == VoicePhase.THINKING) turn else 0f, center) {
            softContour(color.copy(alpha = .2f), 6.dp.toPx()) { tint, stroke ->
                drawOval(tint, Offset(center.x - radius * .62f, center.y - radius * .24f),
                    Size(radius * 1.24f, radius * .48f), style = stroke)
            }
        }
        if (phase == VoicePhase.SPEAKING) repeat(8) { index ->
            val angle = index * Math.PI / 4 + .1
            val a = radius * .69f; val b = radius * (.79f + pulse * .1f)
            softContour(color.copy(alpha = pulse * .35f), 6.dp.toPx()) { tint, stroke ->
                drawLine(tint, center + Offset((cos(angle) * a).toFloat(), (sin(angle) * a).toFloat()),
                    center + Offset((cos(angle) * b).toFloat(), (sin(angle) * b).toFloat()), stroke.width, StrokeCap.Round)
            }
        }
        fun star(at: Offset, r: Float, alpha: Float) {
            val p = Path().apply {
                moveTo(at.x, at.y - r)
                cubicTo(at.x + r * .16f, at.y - r * .2f, at.x + r * .2f, at.y - r * .16f, at.x + r, at.y)
                cubicTo(at.x + r * .2f, at.y + r * .16f, at.x + r * .16f, at.y + r * .2f, at.x, at.y + r)
                cubicTo(at.x - r * .16f, at.y + r * .2f, at.x - r * .2f, at.y + r * .16f, at.x - r, at.y)
                cubicTo(at.x - r * .2f, at.y - r * .16f, at.x - r * .16f, at.y - r * .2f, at.x, at.y - r); close()
            }
            drawPath(p, color.copy(alpha = alpha))
            drawCircle(Silver, r * .12f, at)
        }
        rotate(if (phase == VoicePhase.THINKING) turn * .45f - 10f else -10f, center) {
            star(center + Offset(radius * .08f, -radius * .1f), radius * .32f * scale, 1f)
            star(center + Offset(-radius * .34f, radius * .3f), radius * .13f * scale, .92f)
        }
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.softContour(
    color: Color, width: Float, draw: (Color, Stroke) -> Unit) {
    for (index in 12 downTo 1) {
        val share = (25f - 2f * index) / 144f
        draw(color.copy(alpha = color.alpha * share), Stroke(width * index / 12f, cap = StrokeCap.Round))
    }
}

@Composable internal fun RoundPower(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.semantics {
        contentDescription = "Couper le mode vocal"
        stateDescription = if (active) "Mode vocal actif" else "Mode vocal coupé"
    }, shape = CircleShape, color = Color.Transparent) {
        Canvas(Modifier.padding(13.dp)) {
            val tint = Color(0xFFDDECF5)
            val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
            // The gap straddles twelve o'clock; the vertical stem enters it from above.
            drawArc(tint, -55f, 290f, false, Offset(size.width * .12f, size.height * .17f),
                Size(size.width * .76f, size.height * .76f), style = stroke)
            drawLine(tint, Offset(size.width * .5f, size.height * .02f),
                Offset(size.width * .5f, size.height * .47f), stroke.width, StrokeCap.Round)
        }
    }
}

@Composable fun RoundMicrophone(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, modifier = modifier.size(56.dp).semantics {
        contentDescription = if (active) "Couper le micro et terminer" else "Parler à Sirius"
        stateDescription = if (active) "Micro ouvert" else "Micro coupé"
    }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Canvas(Modifier.fillMaxSize()) {
            softContour(color.copy(alpha = .4f), 8.dp.toPx()) { tint, stroke ->
                drawCircle(tint, size.minDimension / 2f - 2.dp.toPx(), style = stroke)
            }
        }
        Canvas(Modifier.padding(16.dp)) {
            val w = size.width; val h = size.height
            drawRoundRect(color, Offset(w * .35f, h * .08f), Size(w * .3f, h * .52f), androidx.compose.ui.geometry.CornerRadius(w * .2f), style = Stroke(1.7.dp.toPx()))
            val p = Path().apply { moveTo(w * .18f, h * .43f); cubicTo(w * .18f, h * .91f, w * .82f, h * .91f, w * .82f, h * .43f) }
            drawPath(p, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
            drawLine(color, Offset(w * .5f, h * .79f), Offset(w * .5f, h * .98f), 1.7.dp.toPx(), StrokeCap.Round)
            if (!active) drawLine(color, Offset(0f, h), Offset(w, 0f), 1.7.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable fun ConnectionBadge(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(if (connected) Color(0xFFADCBB5) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        Text(if (connected) "En direct" else "En veille", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun ConversationScreen(state: VoiceState, pilot: PilotState, onTalk: () -> Unit, onEnd: () -> Unit,
    onSettings: () -> Unit, onPilot: () -> Unit, modifier: Modifier = Modifier, animated: Boolean = true, onVolume: (Float) -> Unit = {}, onAccessibility: () -> Unit = onPilot,
    onResumePilot: () -> Unit = {}) {
    var retainHud by remember { mutableStateOf(state.conversation) }
    LaunchedEffect(state.conversation) {
        if (state.conversation) retainHud = true
        else { kotlinx.coroutines.delay(if (animated && android.animation.ValueAnimator.areAnimatorsEnabled()) 520L else 0L); retainHud = false }
    }
    if (state.conversation || retainHud) {
        val finish: () -> Unit = { retainHud = false; onEnd() }
        LiveHud(state, onMicrophone = finish, onDismiss = finish, modifier = modifier, animated = animated, onVolume = onVolume,
            pilotPaused = pilot.stopped, onResumePilot = onResumePilot, onSettings = onSettings, onPilot = onPilot,
            accessibilityMissing = pilot.enabled && !pilot.accessibility, onAccessibility = onAccessibility)
        return
    }
    NightBackdrop(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✧", color = MaterialTheme.colorScheme.primary, fontSize = 25.sp)
                Text("Sirius", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                ConnectionBadge(state.connected)
                IconButton(onClick = onSettings, modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = "Réglages" }) {
                    val c = MaterialTheme.colorScheme.onSurfaceVariant
                    Canvas(Modifier.size(20.dp)) {
                        val center = Offset(size.width / 2, size.height / 2)
                        drawCircle(c, size.width * .28f, center, style = Stroke(1.4.dp.toPx()))
                        drawCircle(c, size.width * .09f, center, style = Stroke(1.2.dp.toPx()))
                        repeat(8) { rotate(it * 45f) { drawLine(c, Offset(center.x, size.height * .05f), Offset(center.x, size.height * .19f), 2.dp.toPx()) } }
                    }
                }
            }
            if (pilot.enabled && !pilot.accessibility) Surface(color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Le pilotage a perdu l'accès à l'écran", style = MaterialTheme.typography.titleSmall)
                    Text("Réactive Sirius dans Accessibilité pour reprendre.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onAccessibility) { Text("Réactiver l'accessibilité") }
                }
            }
            SiriusStar(state.phase, state.level, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).size(188.dp), animated)
            Text(state.status, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 2)
            Text(when (state.phase) {
                VoicePhase.SPEAKING -> "Tu peux me couper en parlant"
                VoicePhase.THINKING -> "Je prends un instant pour te répondre"
                VoicePhase.LISTENING -> "La conversation se rendort après 12 s de silence"
                VoicePhase.RECONNECTING -> "Je retrouve ton serveur"
                VoicePhase.IDLE -> if (state.wakeRunning) "Le mot d'éveil reste sur ton téléphone" else "Un instant pour toi"
            }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundMicrophone(state.conversation, if (state.conversation) onEnd else onTalk)
                Text(if (state.conversation) "Couper le micro" else "Parler à Sirius", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (state.subtitle.isNotBlank() && state.subtitleRole == "tom") {
                Text(state.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = TextAlign.Center, maxLines = 2)
            }
            HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Notre conversation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Transcription serveur", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val list = rememberLazyListState()
            LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex) }
            LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.messages.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                        Text("Je suis là.", style = MaterialTheme.typography.titleLarge)
                        Text("Pose ta question à voix haute. Nos échanges apparaîtront ici.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
            }
            Surface(onClick = onPilot, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pilotage", style = MaterialTheme.typography.labelLarge)
                        Text(if (pilot.enabled) "Autorisations et journal" else "Le téléphone reste entre tes mains", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (pilot.enabled) "Actif  ›" else "Désactivé  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable private fun MessageBubble(message: ChatMessage) {
    val own = message.role == "tom"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(20.dp, 20.dp, if (own) 5.dp else 20.dp, if (own) 20.dp else 5.dp))
            .background(if (own) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(if (own) "TOI" else "SIRIUS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable fun AssistantSheet(state: VoiceState, onMicrophone: () -> Unit, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, animated: Boolean = true, onVolume: (Float) -> Unit = {},
    onTouchableBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    motionState: HudMotionState? = null, onExitFinished: () -> Unit = {}, onCloseRequested: ((String) -> Unit)? = null,
    pilotPaused: Boolean = false, onResumePilot: () -> Unit = {}, onSettings: (() -> Unit)? = null,
    accessibilityMissing: Boolean = false, onAccessibility: () -> Unit = {}, edgeGlow: Boolean = true,
    screenOutline: ScreenOutline? = null) {
    LiveHud(state, onMicrophone, onDismiss, modifier, animated, onVolume, compact = true, onTouchableBounds = onTouchableBounds,
        motionState = motionState, onExitFinished = onExitFinished, onCloseRequested = onCloseRequested,
        pilotPaused = pilotPaused, onResumePilot = onResumePilot, onSettings = onSettings,
        accessibilityMissing = accessibilityMissing, onAccessibility = onAccessibility, edgeGlow = edgeGlow,
        screenOutline = screenOutline)
}
