package fr.tom.sirius

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Step of one tap, in dp: about a tenth of a millimeter on the S25 Ultra. */
internal const val RING_STEP_DP = .5f

/**
 * Sirius 2.10: Tom sets the camera ring on the real punch hole. The screen shows the border exactly as the HUD
 * draws it (same EdgeGlow, same screen outline), and every tap moves it live; nothing is saved before « Enregistrer ».
 */
@Composable
internal fun RingCalibrationPage(initial: FloatArray, onSave: (FloatArray) -> Unit, onBack: () -> Unit) {
    var dx by remember { mutableFloatStateOf(initial.getOrElse(0) { 0f }) }
    var dy by remember { mutableFloatStateOf(initial.getOrElse(1) { 0f }) }
    var dr by remember { mutableFloatStateOf(initial.getOrElse(2) { 0f }) }
    val leave = { RingTuning.set(initial.getOrElse(0) { 0f }, initial.getOrElse(1) { 0f }, initial.getOrElse(2) { 0f }); onBack() }
    BackHandler(onBack = leave)
    // Applied before the draw of the same frame: what Tom sees is what the HUD will draw.
    SideEffect { RingTuning.set(dx, dy, dr) }
    val border = remember { EdgeGlow() }
    val view = LocalView.current
    val density = LocalDensity.current.density
    val weights = remember { FloatArray(HudColor.entries.size).also { it[HudColor.WAIT.ordinal] = 1f } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize().testTag("ring-calibration")) {
            // Reading the tuning here redraws the ring at every tap.
            if (dx.isNaN() || dy.isNaN() || dr.isNaN()) return@Canvas
            border.draw(drawContext.canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), border.outline(view, density),
                0f, .08f, weights, 1f, false, density, HudTimeline.ENTER_MS)
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Cale l'anneau sur le trou de la caméra", color = Color.White, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
            Text("Chaque appui le déplace d'un dixième de millimètre environ.", color = Color(0xFFB8C7D9),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { dy -= RING_STEP_DP }) { Text("↑") }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { dx -= RING_STEP_DP }) { Text("←") }
                Spacer(Modifier.size(24.dp))
                OutlinedButton(onClick = { dx += RING_STEP_DP }) { Text("→") }
            }
            OutlinedButton(onClick = { dy += RING_STEP_DP }) { Text("↓") }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { dr -= RING_STEP_DP }) { Text("Plus petit") }
                OutlinedButton(onClick = { dr += RING_STEP_DP }) { Text("Plus grand") }
            }
            Text(String.format(Locale.FRANCE, "x %+.1f   y %+.1f   taille %+.1f dp", dx, dy, dr), color = Color(0xFF8FA3BA),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { dx = 0f; dy = 0f; dr = 0f }) { Text("Réinitialiser") }
                TextButton(onClick = leave) { Text("Annuler") }
                Button(onClick = { onSave(floatArrayOf(dx, dy, dr)) }) { Text("Enregistrer") }
            }
        }
    }
}
