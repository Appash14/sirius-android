package fr.tom.sirius

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ActionLogEntry(val sequence: String, val at: Long, val action: String, val ok: Boolean, val reason: String,
    val source: String = "", val recognized: String = "")
data class PilotState(val enabled: Boolean = false, val accessibility: Boolean = false, val notifications: Boolean = false,
    val confirmations: Boolean = false, val log: List<ActionLogEntry> = emptyList(), val stopped: Boolean = false, val session: Boolean = false)

private const val NOT_CANCELLED = "action_annulee"

private class ActiveAction(val command: PhoneAction, val sender: (JSONObject) -> Boolean) {
    var job: Job? = null
    var approval: CompletableDeferred<Boolean>? = null
    var token: String? = null
    var expires = 0L
    var cancelReason = NOT_CANCELLED
    val intents = mutableListOf<PendingIntent>()
    val steps = JSONArray()
    var stepIndex = -1
}

/** No exported command receiver. Only VoiceEngine's authenticated WS dispatch calls receive(). */
class PilotController(private val context: Context, private val settings: Settings, private val engine: VoiceEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(PilotState(enabled = settings.pilotEnabled, log = loadLog()))
    val state = mutable.asStateFlow()
    internal var automaticScreen = false
    internal var onStopSession: (() -> Unit)? = null
    private var accessibility: SiriusAccessibilityService? = null
    private var notifications: SiriusNotificationService? = null
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var active: ActiveAction? = null
    private val completed = linkedSetOf<String>()
    private var sessionJob: Job? = null
    private var lastActivity = 0L
    private var micServiceRequested = false
    private var nextQuestionUtterance: String? = null
    init {
        // Migrate the old emergency stop, which turned off an already authorized switch.
        settings.migratePilotSessionStop()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Confirmations du pilotage", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Autorise ou refuse une action demandée par Sirius"; lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
        PilotNotifications.channel(context)
        refresh()
    }
    fun attachAccessibility(service: SiriusAccessibilityService) { accessibility = service; if (state.value.session) service.showOverlay(); refresh() }
    fun detachAccessibility(service: SiriusAccessibilityService) { if (accessibility === service) { accessibility = null; cancelPending("accessibilite_absente"); refresh() } }
    fun attachNotifications(service: SiriusNotificationService) { notifications = service; refresh() }
    fun detachNotifications(service: SiriusNotificationService) { if (notifications === service) { notifications = null; refresh() } }
    fun refresh() {
        mutable.update { it.copy(enabled = settings.pilotEnabled, stopped = settings.pilotStopped, accessibility = accessibility != null,
            notifications = notifications != null, confirmations = canConfirm()) }
    }
    /** Every entry point checks in this order: keyguard, Tom's emergency stop, opt-in switch, banking apps. */
    fun check(pkg: String = "", label: String = "") =
        ActionPolicy.guard(settings.pilotEnabled, engine.locked(), pkg, label, settings.pilotStopped)
    /** Persistent permission, independent from stopping the current session. */
    fun setEnabled(enabled: Boolean) {
        settings.pilotEnabled = enabled
        if (enabled) { settings.pilotStopped = false; manager.cancel(PilotNotifications.STOPPED_ID) }
        else { cancelPending("pilotage_desactive"); notifications?.clearHistory(); endSession("pilotage_desactive") }
        refresh()
    }
    /** Local cancellation wins immediately. Only a new accepted voice question can reopen the session. */
    fun emergencyStop(source: String, recognized: String = "") {
        settings.pilotStopped = true
        nextQuestionUtterance = null
        cancelPending(STOPPED_BY_TOM)
        notifications?.clearHistory()
        endSession(record = false)
        val origin = source.takeIf { it in setOf("pastille", "notification", "mot_code_local", "mot_code_serveur", "serveur") } ?: "autre"
        val heard = recognized.take(400)
        log("arrêt de session", true, STOPPED_BY_TOM, origin, heard)
        android.util.Log.i("SiriusPilot", "arret source=$origin texte_reconnu=${JSONObject.quote(heard)}")
        refresh()
        onStopSession?.invoke()
        engine.report("Session arrêtée. Ta prochaine demande pourra piloter.")
        engine.pilotChanged()
        PilotNotifications.postStopped(context)
    }
    fun questionStarted(utterance: String) { if (settings.pilotStopped) nextQuestionUtterance = utterance }
    fun questionAccepted(utterance: String?) {
        if (!settings.pilotStopped || !settings.pilotEnabled || nextQuestionUtterance == null ||
            utterance != nextQuestionUtterance) return
        nextQuestionUtterance = null
        settings.pilotStopped = false
        manager.cancel(PilotNotifications.STOPPED_ID)
        refresh(); engine.pilotChanged()
        log("nouvelle demande", true, "")
    }
    /** The first reason wins: a later detach or socket loss must not hide Tom's stop. */
    fun cancelPending(reason: String) {
        active?.let {
            if (it.cancelReason == NOT_CANCELLED) {
                it.cancelReason = reason
                android.util.Log.i("SiriusPilot", "annulation source=autre raison=$reason action=${it.command.action}")
                if (reason != STOPPED_BY_TOM) log("annulation ${it.command.action}", false, reason, "autre")
            }
            it.job?.cancel(); clearConfirmation(it)
        }
    }
    /** Microphone service refused by Android: keep the plain ongoing notification so ARRÊTER stays reachable. */
    fun micServiceFailed() {
        micServiceRequested = false
        if (state.value.session) PilotNotifications.postSession(context)
    }
    private fun touchSession() {
        lastActivity = SystemClock.elapsedRealtime()
        if (!state.value.session) beginSession() else ensureMicService()
    }
    private fun beginSession() {
        mutable.update { it.copy(session = true) }
        engine.pilotSession = true
        if (!ensureMicService()) PilotNotifications.postSession(context)
        accessibility?.showOverlay()
        sessionJob?.cancel()
        sessionJob = scope.launch {
            while (isActive) {
                val voice = engine.state.value
                accessibility?.updateOverlay(voice.conversation, voice.phase)
                val idle = active == null && !voice.conversation && SystemClock.elapsedRealtime() - lastActivity >= SESSION_IDLE_MS
                if (idle || engine.locked() || !settings.pilotEnabled) {
                    endSession(if (engine.locked()) "verrouille" else if (!settings.pilotEnabled) "pilotage_desactive" else "inactivite")
                    break
                }
                delay(400)
            }
        }
    }
    /** Started only from a visible window (assistant sheet or app), as Android requires for a microphone service. */
    private fun ensureMicService(): Boolean {
        if (PilotSessionService.running.value || micServiceRequested) return true
        val voice = engine.state.value
        if (!voice.conversation || !(engine.assistantVisible || engine.appVisible) || !engine.hasMicPermission()) return false
        micServiceRequested = PilotSessionService.start(context)
        return micServiceRequested
    }
    fun endSession(reason: String = "session_terminee", record: Boolean = true) {
        if (!state.value.session && sessionJob == null) return
        if (record) {
            log("fin de session", true, reason, "autre")
            android.util.Log.i("SiriusPilot", "arret source=autre raison=$reason")
        }
        val job = sessionJob; sessionJob = null
        mutable.update { it.copy(session = false) }
        engine.pilotSession = false
        micServiceRequested = false
        PilotSessionService.stop(context)
        manager.cancel(PilotNotifications.SESSION_ID)
        accessibility?.hideOverlay()
        job?.cancel()
    }
    fun receive(json: JSONObject, sender: (JSONObject) -> Boolean) {
        val command = try {
            requireAction(json.toString().toByteArray(Charsets.UTF_8).size <= 24000, "arguments_invalides")
            PhoneAction.parse(json)
        } catch (e: ActionFailure) {
            val id = (json.opt("id") as? String)?.take(100).orEmpty()
            sender(failedAction(id, e.reason)); log("commande invalide", false, e.reason); return
        }
        // Stopping needs neither an active session nor access to the phone. Repeated requests stay successful.
        if (command.action == "arreter_session") {
            emergencyStop("serveur")
            sender(actionResult(command.id, true))
            return
        }
        try { check() }
        catch (e: ActionFailure) { sender(failedAction(command.id, e.reason)); log(command.action, false, e.reason); return }
        if (active?.command?.id == command.id) return // The first result remains authoritative.
        if (command.id in completed) { sender(failedAction(command.id, "commande_deja_traitee")); return }
        if (active != null) { sender(failedAction(command.id, "telephone_occupe")); log(command.action, false, "telephone_occupe"); return }
        val current = ActiveAction(command, sender); active = current
        touchSession()
        engine.pilotActivity(true)
        current.job = scope.launch(start = CoroutineStart.LAZY) {
            var result: JSONObject
            try {
                val data = withTimeout(14_500) {
                    check()
                    if (command.action != "notifications") {
                        requireAction(accessibility != null, "accessibilite_absente")
                        // Hide the sheet only once the microphone service runs, so the conversation keeps its mic.
                        if (micServiceRequested) withTimeoutOrNull(800) { PilotSessionService.running.first { it } }
                        engine.onDeviceAction?.invoke()
                        // hide() crosses Binder and posts MSG_HIDE. A fixed 150 ms can launch the app
                        // before onHide restores the previous task (often HOME). Wait for that callback.
                        val hidden = withTimeoutOrNull(2_000) {
                            while (engine.assistantVisible) { check(); delay(25) }
                            true
                        } ?: false
                        requireAction(hidden, "assistant_masquage_non_confirme")
                        delay(150) // Let focus settle after the confirmed hide, never before it.
                    }
                    val output = execute(current)
                    accessibility?.let { service ->
                        if (command.action !in setOf("sequence", "envoyer_message")) settle(command, service, output)
                    }
                    output
                }
                // Never release a screenshot/tree/notification captured just before keyguard appeared.
                check()
                result = actionResult(command.id, true, data)
            } catch (_: TimeoutCancellationException) { result = failedAction(command.id, "delai_depasse") }
            catch (e: CancellationException) { result = failedAction(command.id, current.cancelReason) }
            catch (e: ActionFailure) { result = failedAction(command.id, e.reason) }
            catch (_: Exception) { result = failedAction(command.id, "action_indisponible") }
            finally { clearConfirmation(current) }
            val data = result.getJSONObject("data")
            if (command.action == "sequence") {
                data.put("etapes", current.steps)
                if (!result.getBoolean("ok")) data.put("index_erreur", current.stepIndex)
            }
            attachScreen(command, result)
            current.sender(result)
            log(command.action, result.getBoolean("ok"), result.getJSONObject("data").optString("raison"))
            completed.add(command.id); while (completed.size > 100) completed.remove(completed.first())
            if (active === current) active = null
            lastActivity = SystemClock.elapsedRealtime()
            engine.pilotActivity(false)
        }
        current.job!!.start()
    }
    /** Let the screen settle before reporting what is in front. */
    private suspend fun settle(command: PhoneAction, service: SiriusAccessibilityService, output: JSONObject) {
        when (command.action) {
            "ouvrir" -> {
                val target = output.optString("paquet")
                val until = SystemClock.elapsedRealtime() + 2_000
                while (SystemClock.elapsedRealtime() < until && service.foregroundPackage() != target) delay(150)
                delay(350)
                requireAction(target.isNotEmpty() && service.foregroundPackage() == target, "ouverture_non_confirmee")
            }
            "toucher", "global", "lancer_intent" -> delay(200)
            "defiler", "glisser" -> delay(200)
            "ecrire" -> delay(150)
        }
    }
    private fun attachScreen(command: PhoneAction, result: JSONObject) {
        val data = result.getJSONObject("data")
        if (data.optString("raison") in setOf(STOPPED_BY_TOM, "verrouille")) return
        // Old servers only accept premier_plan on success. New servers advertise the extended contract.
        if (!automaticScreen && !result.getBoolean("ok")) return
        try {
            check()
            accessibility?.let { service ->
                if (automaticScreen && command.action != "ecran") data.put("ecran", service.screen())
                data.put("premier_plan", service.foreground())
            }
        } catch (_: ActionFailure) { /* No screen or private foreground after a stop, lock or blocked app. */ }
        catch (_: Exception) { /* A disappearing window must not hide the action's result. */ }
    }
    private suspend fun execute(current: ActiveAction, command: PhoneAction = current.command): JSONObject {
        val args = command.args
        currentCoroutineContext().ensureActive()
        check()
        if (command.action == "sequence") {
            var capture: JSONObject? = null
            val steps = args.getJSONArray("actions")
            for (i in 0 until steps.length()) {
                current.stepIndex = i
                val step = steps.getJSONObject(i)
                val child = PhoneAction.parse(JSONObject().put("id", "step-$i").put("action", step.getString("action")).put("args", step.getJSONObject("args")))
                try {
                    val output = execute(current, child)
                    accessibility?.let { settle(child, it, output) }
                    check(); currentCoroutineContext().ensureActive()
                    if (child.action == "capture") capture = output
                    current.steps.put(JSONObject().put("action", child.action).put("ok", true))
                    log("sequence[$i] ${child.action}", true, "")
                } catch (e: ActionFailure) {
                    current.steps.put(JSONObject().put("action", child.action).put("ok", false).put("raison", e.reason))
                    log("sequence[$i] ${child.action}", false, e.reason)
                    throw e
                }
            }
            return JSONObject().apply { capture?.let { put("capture", it) } }
        }
        if (command.action == "notifications") return notifications?.snapshot() ?: throw ActionFailure("notifications_absentes")
        val service = accessibility ?: throw ActionFailure("accessibilite_absente")
        check(service.foregroundPackage())
        return when (command.action) {
            "ecran" -> service.screen()
            "capture" -> service.screenshot()
            "ouvrir" -> service.open(service.resolveApplication(args))
            "toucher", "ecrire" -> {
                val target = service.target(command)
                // Tom authorizes taps and writing. Revalidate the target, keyguard and banking guard.
                check(target.stamp.pkg)
                if (command.action == "ecrire") service.write(command, target.stamp) else service.touch(command, target.stamp)
            }
            "defiler" -> service.scroll(args.getString("direction"))
            "glisser" -> service.swipe(args.getString("direction"))
            "global" -> service.global(args.getString("commande"))
            "lancer_intent" -> { confirm(current, "Ouvrir : ${args.getString("uri").take(180)}"); service.intent(args.getString("uri")) }
            "envoyer_message" -> service.sendTelegramMessage(args.getString("conversation"), args.getString("texte"))
            else -> throw ActionFailure("action_inconnue")
        }
    }
    private fun canConfirm(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    private suspend fun confirm(current: ActiveAction, detail: String) {
        requireAction(canConfirm(), "confirmation_indisponible")
        check()
        val token = UUID.randomUUID().toString(); current.token = token
        current.expires = SystemClock.elapsedRealtime() + CONFIRM_MS
        val deferred = CompletableDeferred<Boolean>(); current.approval = deferred
        fun intent(allowed: Boolean, code: Int): PendingIntent = PendingIntent.getActivity(context, code,
            Intent(context, ActionConfirmationActivity::class.java).setAction("fr.tom.sirius.CONFIRM.$token.$allowed")
                .putExtra("token", token).putExtra("allow", allowed), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT).also { current.intents.add(it) }
        val allow = intent(true, 501); val deny = intent(false, 502)
        val description = when (current.command.action) {
            "ecrire" -> "écrire dans le champ sélectionné"
            "lancer_intent" -> "ouvrir un lien ou un composeur"
            else -> "toucher un bouton qui peut envoyer, appeler ou publier"
        }
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_sirius)
            .setContentTitle("Sirius veut $description").setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText("$detail\nAutorise uniquement si tu reconnais l'action. Expire dans 12 s."))
            .setCategory(Notification.CATEGORY_RECOMMENDATION).setVisibility(Notification.VISIBILITY_SECRET)
            .setTimeoutAfter(CONFIRM_MS).setOnlyAlertOnce(false).setOngoing(false)
            .addAction(Notification.Action.Builder(null, "Autoriser", allow).setAuthenticationRequired(true).build())
            .addAction(Notification.Action.Builder(null, "Refuser", deny).setAuthenticationRequired(true).build()).build()
        manager.notify(CONFIRM_ID, notification)
        val granted = try { withTimeout(CONFIRM_MS) { deferred.await() } }
            catch (_: TimeoutCancellationException) { throw ActionFailure("confirmation_expiree") }
        requireAction(granted, "confirmation_refusee")
        check()
        clearConfirmation(current)
    }
    fun decide(token: String?, granted: Boolean) {
        val current = active ?: return
        if (token == null || current.token != token || current.approval == null) return
        if (SystemClock.elapsedRealtime() >= current.expires) { current.approval?.complete(false); cancelPending("confirmation_expiree"); return }
        try { check(); requireAction(canConfirm(), "confirmation_indisponible") }
        catch (e: ActionFailure) { cancelPending(e.reason); return }
        current.token = null // A second tap or replay has no authority.
        current.approval?.complete(granted)
    }
    private fun clearConfirmation(current: ActiveAction) {
        current.token = null; manager.cancel(CONFIRM_ID); current.intents.forEach { it.cancel() }; current.intents.clear()
    }
    private fun loadLog(): List<ActionLogEntry> = try {
        val rows = JSONArray(settings.readActionLog())
        (0 until rows.length()).map { i -> val row = rows.getJSONObject(i)
            ActionLogEntry(row.getString("sequence"), row.getLong("at"), row.getString("action"), row.getBoolean("ok"), row.optString("reason"), row.optString("source"), row.optString("recognized"))
        }.takeLast(100)
    } catch (_: Exception) { emptyList() }
    private fun log(action: String, ok: Boolean, reason: String, source: String = "", recognized: String = "") {
        val entries = (state.value.log + ActionLogEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), action, ok, reason, source, recognized)).takeLast(100)
        mutable.update { it.copy(log = entries) }
        val json = JSONArray(entries.map { JSONObject().put("sequence", it.sequence).put("at", it.at).put("action", it.action).put("ok", it.ok).put("reason", it.reason).put("source", it.source).put("recognized", it.recognized) })
        settings.saveActionLog(json.toString())
    }
    companion object {
        private const val CHANNEL = "sirius.confirmations"
        private const val CONFIRM_ID = 49
        private const val CONFIRM_MS = 12_000L
        /** Notification and pill stay this long after the last action, unless a conversation is still open. */
        private const val SESSION_IDLE_MS = 30_000L
    }
}

/** A notification activity closes the shade. A broadcast action can leave System UI covering the target. */
class ActionConfirmationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("token")
        val allowed = intent.getBooleanExtra("allow", false)
        val pilot = (application as SiriusApp).pilot
        finish()
        Handler(Looper.getMainLooper()).postDelayed({ pilot.decide(token, allowed) }, 250)
    }
}
