package fr.tom.sirius

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

class SiriusApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var engine: VoiceEngine
        private set
    lateinit var pilot: PilotController
        private set
    private val main = Handler(Looper.getMainLooper())
    private val lockWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            engine.refreshLockState()
            // The keyguard can engage a few seconds after the screen turns off.
            if (intent.action == Intent.ACTION_SCREEN_OFF) main.postDelayed({ engine.refreshLockState() }, 6_000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        engine = VoiceEngine(this, settings)
        pilot = PilotController(this, settings, engine)
        engine.onAction = pilot::receive
        engine.onTransportLost = { pilot.cancelPending("connexion_perdue") }
        engine.onStopWord = { pilot.emergencyStop("autre") }
        watchKeyguard()
        engine.ensureConnection()
    }
    /** Lock and unlock are announced to the server even outside a conversation. Protected system broadcasts only. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun watchKeyguard() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(lockWatcher, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(lockWatcher, filter)
    }
}
