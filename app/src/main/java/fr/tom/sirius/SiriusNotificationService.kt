package fr.tom.sirius

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class SiriusNotificationService : NotificationListenerService() {
    private val app get() = application as SiriusApp
    private val recent = linkedMapOf<String, JSONObject>()
    override fun onListenerConnected() { super.onListenerConnected(); app.pilot.attachNotifications(this); app.engine.ensureConnection() }
    override fun onListenerDisconnected() { app.pilot.detachNotifications(this); clearHistory(); super.onListenerDisconnected() }
    override fun onDestroy() { app.pilot.detachNotifications(this); clearHistory(); super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { remember(it) } }
    private fun remember(sbn: StatusBarNotification) {
        if (!app.settings.pilotEnabled || app.engine.locked() || sbn.packageName == packageName || ActionPolicy.blockedPackage(sbn.packageName)) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        if (ActionPolicy.blockedPackage(sbn.packageName, label)) return
        val extras = sbn.notification.extras
        val row = JSONObject().put("paquet", sbn.packageName).put("appli", label.take(200))
            .put("titre", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(500))
            .put("texte", (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(2000))
            .put("date", sbn.postTime)
        recent.remove(sbn.key); recent[sbn.key] = row
        while (recent.size > 30) recent.remove(recent.keys.first())
    }
    fun clearHistory() { recent.clear() }
    fun snapshot(): JSONObject {
        ActionPolicy.guard(app.settings.pilotEnabled, app.engine.locked())
        runCatching { activeNotifications.sortedBy { it.postTime }.forEach(::remember) }
        ActionPolicy.guard(app.settings.pilotEnabled, app.engine.locked())
        return JSONObject().put("notifications", JSONArray(recent.values.toList().takeLast(30).asReversed()))
    }
}
