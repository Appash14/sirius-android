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
import android.service.voice.VoiceInteractionService
import android.content.ComponentName

class WakeService : Service() {
    private val app get() = application as SiriusApp
    override fun onCreate() {
        super.onCreate()
        channel(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            app.settings.wakeEnabled = false; stopSelf(); return START_NOT_STICKY
        }
        if (!app.settings.wakeEnabled || !app.engine.hasMicPermission() || !app.engine.modelStore.installed()) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, WakeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_sirius)
                .setContentTitle("Sirius t'écoute").setContentText("Mot d'éveil actif : Dis Sirius")
                .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_SERVICE).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "Désactiver", stop).build()).build()
            startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            app.engine.wakeStarted()
        } catch (_: Exception) {
            app.engine.report("Ouvre Sirius pour reprendre l'écoute du mot d'éveil."); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { app.engine.wakeStopped(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "sirius.wake"
        private const val STOP = "fr.tom.sirius.STOP_WAKE"
        fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Écoute Sirius", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Écoute locale du mot d'éveil"; setSound(null, null); enableVibration(false)
                })
        }
        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, WakeService::class.java)) }
            catch (_: Exception) { (context.applicationContext as SiriusApp).engine.report("Ouvre Sirius pour activer le micro.") }
        }
        fun stop(context: Context) { context.stopService(Intent(context, WakeService::class.java)) }
        fun resumeNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            channel(context)
            val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(NotificationManager::class.java).notify(43,
                Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_sirius)
                    .setContentTitle("Reprends l'écoute Sirius").setContentText("Ouvre Sirius pour réactiver Dis Sirius.")
                    .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as SiriusApp
        if (!app.settings.wakeEnabled) return
        val active = VoiceInteractionService.isActiveService(context, ComponentName(context, SiriusVoiceService::class.java))
        // Android 15 prohibits microphone FGS creation directly from BOOT_COMPLETED.
        // The selected assistant is rebound by Android; its onReady resumes the service.
        if (Build.VERSION.SDK_INT < 35 && active) WakeService.start(context)
        else if (!active) WakeService.resumeNotification(context)
    }
}
