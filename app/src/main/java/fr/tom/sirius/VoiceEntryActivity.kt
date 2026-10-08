package fr.tom.sirius

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Public launcher entries expose only the same lock-aware HUD as the assistant. */
class VoiceEntryActivity : ComponentActivity() {
    private val engine get() = (application as SiriusApp).engine
    private var pending = false
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchVoice() else engine.report("Autorise le micro pour parler à Sirius.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        pending = savedInstanceState == null
        setContent {
            val state by engine.state.collectAsStateWithLifecycle()
            BackHandler { engine.endConversation(); finish() }
            SiriusTheme(dark = true) {
                LiveHud(state, onMicrophone = {
                    if (state.conversation) { engine.endConversation(); finish() } else launchVoice()
                }, onDismiss = { engine.endConversation(); finish() }, onVolume = engine::setSiriusVolume)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pending = true
    }

    override fun onResume() {
        super.onResume()
        engine.appVisible = true
        engine.refreshLockState()
        if (pending) { pending = false; launchVoice() }
    }

    override fun onPause() { engine.appVisible = false; super.onPause() }

    private fun launchVoice() {
        val briefing = intent.action == VoiceEntry.BRIEFING
        if (briefing && engine.locked()) {
            engine.report("Déverrouille le téléphone pour écouter ton briefing.")
            return
        }
        if (!engine.hasMicPermission()) {
            if (engine.locked()) {
                getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() { microphone.launch(Manifest.permission.RECORD_AUDIO) }
                    })
            } else microphone.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (SiriusVoiceService.openEntry(briefing)) { finish(); return }
        engine.beginConversation()
        if (briefing) engine.beginBriefing()
    }
}

internal object VoiceEntry {
    const val TALK = "fr.tom.sirius.PARLER"
    const val BRIEFING = "fr.tom.sirius.BRIEFING"
    fun intent(context: Context, action: String = TALK) = Intent(context, VoiceEntryActivity::class.java)
        .setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
