package fr.tom.sirius

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Owns microphone access while the conversation outlives an Activity or assistant window. */
class ConversationService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SiriusApp
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Conversation Sirius", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 44, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 45, Intent(this, ConversationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(44, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_sirius)
                .setContentTitle("Conversation avec Sirius").setContentText("Micro actif pendant la conversation")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Fermer", stop).build()).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            starting = false
            app.engine.endConversation()
            app.engine.report("Ouvre Sirius pour reprendre le micro.")
            stopSelf()
            return START_NOT_STICKY
        }
        starting = false; running = true
        if (intent?.action == STOP) app.engine.endConversation()
        if (stopRequested || !app.engine.state.value.conversation) {
            stopRequested = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { running = false; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "sirius.conversation"
        private const val STOP = "fr.tom.sirius.FERMER_CONVERSATION"
        private var starting = false
        private var running = false
        private var stopRequested = false
        fun start(context: Context): Boolean {
            stopRequested = false
            if (starting || running) return true
            return try {
                starting = true
                context.startForegroundService(Intent(context, ConversationService::class.java)); true
            } catch (_: Exception) { starting = false; false }
        }
        fun stop(context: Context) {
            if (starting) { stopRequested = true; return }
            if (running) context.stopService(Intent(context, ConversationService::class.java))
        }
    }
}
