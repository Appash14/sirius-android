package fr.tom.sirius

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow

/** Ongoing notification while Sirius has the hand, with the local ARRÊTER button. */
object PilotNotifications {
    const val SESSION_ID = 50
    const val STOPPED_ID = 51
    private const val CHANNEL = "sirius.pilotage.session"
    private const val COLOR = 0xFFE5484D.toInt()

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pilotage en cours", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Montre quand Sirius a la main sur le téléphone, avec le bouton ARRÊTER"
                setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 62,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    fun session(context: Context): Notification {
        // Explicit intent to a receiver that is not exported: the stop never goes through the network or Sirius.
        val stop = PendingIntent.getBroadcast(context, 61, Intent(context, PilotStopReceiver::class.java).setAction(PilotStopReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_sirius)
            .setContentTitle("Sirius pilote ton téléphone")
            .setContentText("Touche ARRÊTER pour reprendre la main tout de suite.")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setColor(COLOR).setColorized(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(context))
            .addAction(Notification.Action.Builder(null, "ARRÊTER", stop).build()).build()
    }

    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun postSession(context: Context) {
        if (allowed(context)) context.getSystemService(NotificationManager::class.java).notify(SESSION_ID, session(context))
    }

    fun postStopped(context: Context) {
        if (!allowed(context)) return
        context.getSystemService(NotificationManager::class.java).notify(STOPPED_ID,
            Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_sirius)
                .setContentTitle("Pilotage arrêté")
                .setContentText("La prochaine demande pourra piloter. L'autorisation reste active.")
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setAutoCancel(true)
                .setContentIntent(open(context))
                .build())
    }
}

/** The ARRÊTER button of the notification. Works offline, even when Sirius or the server misbehave. */
class PilotStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SiriusApp
        if (intent.action == ACTION) app.pilot.emergencyStop("notification")
        else if (intent.action == RESUME && !app.engine.locked()) {
            app.pilot.setEnabled(true); app.engine.pilotChanged()
        }
    }
    companion object {
        const val ACTION = "fr.tom.sirius.ARRETER_PILOTAGE"
        const val RESUME = "fr.tom.sirius.REPRENDRE_PILOTAGE"
    }
}

/**
 * Keeps the microphone usable while the piloted app is in front (Instagram...), so Tom and Sirius can keep
 * talking. Started only from a visible window, as Android requires for a microphone service.
 */
class PilotSessionService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SiriusApp
        try {
            startForeground(PilotNotifications.SESSION_ID, PilotNotifications.session(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            starting = false
            app.pilot.micServiceFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        starting = false
        running.value = true
        // The session may have ended while Android was creating the service.
        if (stopRequested || !app.pilot.state.value.session) {
            stopRequested = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { running.value = false; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        val running = MutableStateFlow(false)
        @Volatile private var starting = false
        @Volatile private var stopRequested = false
        fun start(context: Context): Boolean = try {
            stopRequested = false; starting = true
            context.startForegroundService(Intent(context, PilotSessionService::class.java)); true
        } catch (_: Exception) { starting = false; false }
        /** Never stop before startForeground(): Android would crash the app. A pending start stops itself. */
        fun stop(context: Context) {
            if (starting) { stopRequested = true; return }
            if (running.value) context.stopService(Intent(context, PilotSessionService::class.java))
        }
    }
}
