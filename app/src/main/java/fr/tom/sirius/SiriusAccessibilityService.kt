package fr.tom.sirius

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

internal data class TargetStamp(val pkg: String, val window: Int, val id: String, val text: String,
    val description: String, val rectangle: String, val labels: String)
internal data class PhoneTarget(val node: AccessibilityNodeInfo, val stamp: TargetStamp)

class SiriusAccessibilityService : AccessibilityService() {
    private val app get() = application as SiriusApp
    private var overlay: PilotOverlay? = null
    @Volatile private var windowPackage = ""
    @Volatile private var windowClass = ""
    private var nodesTruncated = false
    override fun onServiceConnected() { super.onServiceConnected(); android.util.Log.i("SiriusAccessibility", "connected version=${BuildConfig.VERSION_NAME}"); app.pilot.attachAccessibility(this); app.engine.ensureConnection() }
    override fun onDestroy() { android.util.Log.i("SiriusAccessibility", "destroyed"); hideOverlay(); app.pilot.detachAccessibility(this); super.onDestroy() }
    override fun onInterrupt() { app.pilot.cancelPending("accessibilite_interrompue") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (app.engine.locked()) app.pilot.cancelPending("verrouille")
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Last window opened by another app: usually its activity class, sometimes a dialog.
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != packageName) { windowPackage = pkg; windowClass = event.className?.toString().orEmpty() }
        }
    }
    private fun label(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
    private fun blockedWindow() = windows.any { window ->
        val pkg = window.root?.packageName?.toString() ?: return@any false
        ActionPolicy.blockedPackage(pkg, label(pkg))
    }
    private fun guard(pkg: String = foregroundPackage()) {
        app.pilot.check(pkg, label(pkg))
        // Also reject a financial window behind a system overlay, dialog or notification shade.
        requireAction(!blockedWindow(), "application_bloquee")
    }
    fun foregroundPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        val result = mutableListOf<AccessibilityNodeInfo>(); var inspected = 0
        while (queue.isNotEmpty() && result.size < 400 && inspected++ < 1600) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) result.add(node)
            if (!node.isPassword) for (i in 0 until node.childCount.coerceAtMost(400)) node.getChild(i)?.let { if (queue.size < 1600) queue.add(it) }
        }
        nodesTruncated = queue.isNotEmpty()
        return result
    }
    private fun root(): AccessibilityNodeInfo { guard(); return rootInActiveWindow ?: throw ActionFailure("ecran_indisponible") }
    private fun rect(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun labels(node: AccessibilityNodeInfo): String {
        val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(node)
        val labels = linkedSetOf<String>(); var inspected = 0
        // Telegram cells can expose their labels on descendants without a visibility flag.
        // Inspect the descendants of a visible cell, but never cross a password subtree.
        while (pending.isNotEmpty() && inspected++ < 1600) {
            val child = pending.removeFirst()
            if (child.isPassword) continue
            listOf(child.text, child.contentDescription, child.hintText).forEach {
                it?.toString()?.trim()?.takeIf(String::isNotBlank)?.take(1000)?.let(labels::add)
            }
            for (i in 0 until child.childCount.coerceAtMost(400)) child.getChild(i)?.let {
                if (pending.size < 1600) pending.addLast(it)
            }
        }
        return labels.joinToString(" ").take(8000)
    }
    private fun stamp(node: AccessibilityNodeInfo) = TargetStamp(node.packageName?.toString().orEmpty(), node.windowId,
        node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), rect(node).flattenToString(), labels(node))

    fun screen(): JSONObject {
        val root = root(); val all = nodes(root)
        val output = JSONArray(); var bytes = 0; var truncated = nodesTruncated
        fun bounded(value: String, limit: Int = 1000): String {
            if (value.length > limit) truncated = true
            return value.take(limit).dropLastWhile { it.isHighSurrogate() }
        }
        for (node in all) {
            val bounds = rect(node)
            // Keep the existing strict wire schema: put cell descendants in description, bounded to 1000.
            val description = if (node.isPassword) "" else if (node.isClickable) labels(node) else node.contentDescription?.toString().orEmpty()
            val row = JSONObject().put("texte", if (node.isPassword) "" else bounded(node.text?.toString().orEmpty()))
                .put("description", bounded(description))
                .put("id", bounded(node.viewIdResourceName.orEmpty())).put("cliquable", node.isClickable).put("editable", node.isEditable)
                .put("defilable", node.isScrollable).put("focalise", node.isFocused).put("mot_de_passe", node.isPassword)
                .put("rectangle", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map { it.coerceIn(-100000, 100000) }))
            val size = row.toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > SCREEN_BYTES) { truncated = true; break }
            output.put(row); bytes += size
        }
        guard()
        return JSONObject().put("paquet", bounded(root.packageName?.toString().orEmpty(), 300)).put("noeuds", output).put("tronque", truncated)
    }
    internal fun target(action: PhoneAction): PhoneTarget {
        val root = root(); val all = nodes(root)
        val args = action.args
        val node = when {
            action.action == "ecrire" -> all.firstOrNull { it.isFocused && it.isEditable } ?: throw ActionFailure("champ_absent")
            args.has("x") -> {
                val x = args.getDouble("x").roundToInt(); val y = args.getDouble("y").roundToInt()
                val size = displaySize()
                requireAction(x < size.first && y < size.second, "coordonnees_invalides")
                all.filter { rect(it).contains(x, y) && it.isEnabled }.minByOrNull { rect(it).let { r -> r.width().toLong() * r.height() } }
                    ?: throw ActionFailure("cible_absente")
            }
            else -> {
                val matches = all.filter { node -> if (args.has("id")) node.viewIdResourceName == args.getString("id") else
                    node.text?.toString() == args.getString("texte") || node.contentDescription?.toString() == args.getString("texte") }
                requireAction(matches.isNotEmpty(), "cible_absente")
                // A label and its parent often share text. De-duplicate by actual clickable ancestor.
                val targets = matches.map { clickable(it) ?: it }.distinctBy { stamp(it) }
                requireAction(targets.size == 1, "cible_ambigue"); targets.single()
            }
        }
        requireAction(!node.isPassword, "champ_protege")
        val actual = if (action.action == "ecrire") node else clickable(node) ?: node
        guard(actual.packageName?.toString().orEmpty())
        return PhoneTarget(actual, stamp(actual))
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(12) {
            val n = current ?: return null
            if (canClick(n) && n.isEnabled && n.isVisibleToUser) return n
            current = n.parent
        }
        return null
    }
    internal suspend fun touch(action: PhoneAction, expected: TargetStamp): JSONObject {
        val current = target(action); requireAction(current.stamp == expected, "cible_modifiee"); guard(current.stamp.pkg)
        if (action.args.has("x")) {
            val path = Path().apply { moveTo(action.args.getDouble("x").toFloat(), action.args.getDouble("y").toFloat()) }
            gesture(path, 70)
        } else {
            requireAction(canClick(current.node), "cible_non_cliquable")
            requireAction(current.node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "action_echouee")
        }
        return JSONObject()
    }
    private fun canClick(node: AccessibilityNodeInfo) = node.isClickable ||
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    /** A local workflow, with no coordinate guessing, intent send, or retry of the send gesture. */
    internal suspend fun sendTelegramMessage(conversation: String, text: String): JSONObject {
        val pkg = resolveApplication(JSONObject().put("nom", "Telegram"))
        requireAction(pkg in setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta"), "application_non_prise_en_charge")
        open(pkg)
        waitFor { foregroundPackage() == pkg }
        delay(200)
        var cached: List<AccessibilityNodeInfo>? = null
        var cachedAt = 0L
        fun telegramNodes(): List<AccessibilityNodeInfo> {
            guard(); requireAction(foregroundPackage() == pkg, "cible_modifiee")
            val now = android.os.SystemClock.elapsedRealtime()
            // One tree per polling tick, instead of a tree for every field/title/button lookup.
            if (cached == null || now - cachedAt >= 80) {
                cached = nodes(root()).filter { !it.isPassword && it.isEnabled }; cachedAt = now
            }
            return cached!!
        }
        fun field() = telegramNodes().filter { it.isEditable }.singleOrNull()
        // Telegram reports its hint "Message" as the text of an empty composer: only typed text is a draft.
        fun fieldText() = field()?.let { TelegramLabels.typedText(it.text, it.hintText, it.isShowingHintText, it.textSelectionStart) }
        fun titleMatches(): Boolean = telegramNodes().any { node ->
            !node.isEditable && rect(node).bottom <= displaySize().second / 4 &&
                listOf(node.text, node.contentDescription).any { it?.toString()?.trim() == conversation }
        }
        fun inConversation() = field() != null && titleMatches() &&
            telegramNodes().none { node -> listOf(node.hintText, node.contentDescription).any { isSearchLabel(it?.toString().orEmpty()) } && node.isEditable }
        suspend fun clickSelected(select: () -> AccessibilityNodeInfo?) {
            currentCoroutineContext().ensureActive()
            val before = select() ?: throw ActionFailure("cible_absente")
            val expected = stamp(before)
            cached = null // Always re-read the target immediately before a gesture.
            val fresh = select() ?: throw ActionFailure("cible_modifiee")
            requireAction(stamp(fresh) == expected, "cible_modifiee"); guard(expected.pkg)
            requireAction(fresh.performAction(AccessibilityNodeInfo.ACTION_CLICK), "action_echouee")
            delay(200)
        }
        fun searchButton() = telegramNodes().mapNotNull { node ->
            if (!node.isEditable && listOf(node.text, node.contentDescription).any { isSearchLabel(it?.toString().orEmpty()) }) clickable(node) else null
        }.distinctBy { stamp(it) }.singleOrNull()
        fun row(): AccessibilityNodeInfo? {
            val matches = telegramNodes().filter { node -> !node.isEditable &&
                listOf(node.text, node.contentDescription).any { TelegramLabels.conversation(it?.toString().orEmpty(), conversation) }
            }.mapNotNull { clickable(it) }.filter { rect(it).height() < displaySize().second / 3 }
                .distinctBy { stamp(it) }
            requireAction(matches.size <= 1, "conversation_ambigue")
            return matches.singleOrNull()
        }
        suspend fun setField(value: String) {
            currentCoroutineContext().ensureActive()
            val before = field() ?: throw ActionFailure("champ_absent")
            val expected = stamp(before)
            cached = null
            val fresh = field() ?: throw ActionFailure("cible_modifiee")
            requireAction(stamp(fresh) == expected, "cible_modifiee"); guard(expected.pkg)
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            requireAction(fresh.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments), "action_echouee")
            delay(150)
        }
        if (!inConversation()) {
            // Leave another chat, drawer or search before looking for a recipient in the dialog list.
            var back = 0
            while ((field() != null || searchButton() == null) && back++ < 4) { global("retour"); delay(200) }
            requireAction(field() == null, "conversation_absente")
            if (row() == null) {
                clickSelected(::searchButton)
                waitFor { field() != null }
                setField(conversation)
                waitFor { row() != null }
            }
            clickSelected(::row)
            waitFor { inConversation() }
        }
        requireAction(inConversation(), "conversation_absente")
        // Do not replace a draft, reply or edit: it belongs to Tom.
        requireAction(fieldText().isNullOrBlank(), "brouillon_present")
        requireAction(telegramNodes().none { node -> listOf(node.text, node.contentDescription).any {
            ActionPolicy.normalize(it?.toString().orEmpty()) in setOf("cancel reply", "annuler la reponse", "cancel editing", "annuler la modification")
        } }, "brouillon_present")
        setField(text)
        fun sendButton(): AccessibilityNodeInfo? {
            requireAction(inConversation() && field()?.text?.toString() == text, "cible_modifiee")
            val matches = telegramNodes().mapNotNull { node ->
                if (listOf(node.text, node.contentDescription).any { it?.toString()?.trim()?.lowercase() in setOf("envoyer", "send") }) clickable(node) else null
            }.distinctBy { stamp(it) }
            requireAction(matches.size <= 1, "cible_ambigue")
            return matches.singleOrNull()
        }
        waitFor { sendButton() != null }
        clickSelected(::sendButton)
        // Success means Telegram consumed the composer, not that the recipient received it.
        waitFor { inConversation() && fieldText().isNullOrBlank() }
        return JSONObject().put("appli", "telegram").put("statut", "envoye")
    }
    private fun isSearchLabel(text: String) = ActionPolicy.normalize(text.trim()) in setOf("search", "rechercher", "recherche")
    private suspend fun waitFor(condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 2_000
        do {
            currentCoroutineContext().ensureActive(); guard()
            if (condition()) return
            delay(100)
        } while (android.os.SystemClock.elapsedRealtime() < until)
        throw ActionFailure("interface_non_prete")
    }
    internal fun write(action: PhoneAction, expected: TargetStamp): JSONObject {
        val current = target(action); requireAction(current.stamp == expected, "cible_modifiee"); guard(current.stamp.pkg)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.args.getString("texte")) }
        requireAction(current.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "action_echouee")
        return JSONObject()
    }
    /** Vertical only: an accessibility action on a container known to be vertical, otherwise a vertical swipe. */
    suspend fun scroll(direction: String): JSONObject {
        val root = root()
        val scrollable = nodes(root).filter { it.isScrollable && it.isEnabled }
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { rect(it) }
        val decision = ScrollChoice.choose(scrollable.map { candidate(it, focus) }, direction)
        guard()
        if (decision is ScrollDecision.Action && scrollable[decision.index].performAction(actionId(decision.method))) {
            return JSONObject().put("methode", "action")
        }
        // No vertical container, or the app ignored the action: a real vertical swipe never pages sideways.
        swipeGesture(if (direction == "bas") "haut" else "bas")
        return JSONObject().put("methode", "geste")
    }
    /** glisser: the finger moves in this direction. Sideways only reaches here with "explicite": true. */
    suspend fun swipe(finger: String): JSONObject { swipeGesture(finger); return JSONObject() }
    private suspend fun swipeGesture(finger: String) {
        val size = displaySize()
        val plan = Swipes.plan(finger, size.first, size.second)
        gesture(Path().apply { moveTo(plan.startX, plan.startY); lineTo(plan.endX, plan.endY) }, plan.durationMs)
    }
    private fun candidate(node: AccessibilityNodeInfo, focus: Rect?): ScrollCandidate {
        val ids = node.actionList.map { it.id }.toSet()
        fun has(action: AccessibilityAction) = action.id in ids
        val bounds = rect(node)
        val collection = node.collectionInfo
        return ScrollCandidate(className = node.className?.toString().orEmpty(), width = bounds.width(), height = bounds.height(),
            up = has(AccessibilityAction.ACTION_SCROLL_UP), down = has(AccessibilityAction.ACTION_SCROLL_DOWN),
            left = has(AccessibilityAction.ACTION_SCROLL_LEFT), right = has(AccessibilityAction.ACTION_SCROLL_RIGHT),
            pageUp = has(AccessibilityAction.ACTION_PAGE_UP), pageDown = has(AccessibilityAction.ACTION_PAGE_DOWN),
            pageLeft = has(AccessibilityAction.ACTION_PAGE_LEFT), pageRight = has(AccessibilityAction.ACTION_PAGE_RIGHT),
            forward = has(AccessibilityAction.ACTION_SCROLL_FORWARD), backward = has(AccessibilityAction.ACTION_SCROLL_BACKWARD),
            rows = collection?.rowCount ?: -1, columns = collection?.columnCount ?: -1,
            containsFocus = focus != null && bounds.contains(focus))
    }
    private fun actionId(method: ScrollMethod) = when (method) {
        ScrollMethod.SCROLL_DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN.id
        ScrollMethod.SCROLL_UP -> AccessibilityAction.ACTION_SCROLL_UP.id
        ScrollMethod.PAGE_DOWN -> AccessibilityAction.ACTION_PAGE_DOWN.id
        ScrollMethod.PAGE_UP -> AccessibilityAction.ACTION_PAGE_UP.id
        ScrollMethod.FORWARD -> AccessibilityAction.ACTION_SCROLL_FORWARD.id
        ScrollMethod.BACKWARD -> AccessibilityAction.ACTION_SCROLL_BACKWARD.id
    }
    /** Where the phone really is after an action, so Sirius can check it did only what Tom asked. */
    fun foreground(): JSONObject {
        val pkg = foregroundPackage()
        if (pkg.isNotEmpty() && (ActionPolicy.blockedPackage(pkg, label(pkg)) || blockedWindow())) {
            return JSONObject().put("paquet", "").put("activite", "").put("titre", "").put("bloque", true)
        }
        val title = runCatching { windows.firstOrNull { it.isActive }?.title?.toString() }.getOrNull().orEmpty()
        return JSONObject().put("paquet", pkg.take(300)).put("activite", if (windowPackage == pkg) windowClass.take(300) else "")
            .put("titre", title.take(200)).put("bloque", false)
    }
    /** Never lets a window error break an action: the notification keeps ARRÊTER anyway. */
    fun showOverlay() {
        if (overlay != null) return
        val view = runCatching { PilotOverlay(this) { app.pilot.emergencyStop("pastille") } }.getOrNull() ?: return
        overlay = view
        runCatching { view.show() }.onFailure { runCatching { view.hide() }; overlay = null }
    }
    fun updateOverlay(conversation: Boolean, phase: VoicePhase) { overlay?.update(conversation, phase) }
    fun hideOverlay() { overlay?.hide(); overlay = null }
    fun global(command: String): JSONObject {
        guard()
        val code = when (command) {
            "retour" -> GLOBAL_ACTION_BACK; "accueil" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; else -> throw ActionFailure("arguments_invalides")
        }
        requireAction(performGlobalAction(code), "action_echouee"); return JSONObject()
    }
    fun resolveApplication(args: JSONObject): String {
        val pkg = if (args.has("paquet")) args.getString("paquet") else {
            val requested = ActionPolicy.normalize(args.getString("nom"))
            val launchers = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val matches = launchers.filter { ActionPolicy.normalize(it.loadLabel(packageManager).toString()) == requested }.map { it.activityInfo.packageName }.distinct()
            requireAction(matches.isNotEmpty(), "application_absente"); requireAction(matches.size == 1, "application_ambigue"); matches.single()
        }
        app.pilot.check(pkg, label(pkg))
        return pkg
    }
    fun open(pkg: String): JSONObject {
        guard(); resolveApplication(JSONObject().put("paquet", pkg))
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: throw ActionFailure("application_absente")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return JSONObject().put("paquet", pkg)
    }
    fun intent(raw: String): JSONObject {
        guard(); val uri = ActionPolicy.validateUri(raw)
        val action = when (uri.scheme) { "tel" -> Intent.ACTION_DIAL; "sms" -> Intent.ACTION_SENDTO; else -> Intent.ACTION_VIEW }
        val intent = Intent(action, Uri.parse(raw)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // An explicit resolved activity prevents hidden financial handlers or chooser redirection.
        val resolved = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) ?: throw ActionFailure("application_absente")
        requireAction(resolved.activityInfo.exported, "intent_interdit")
        val pkg = resolved.activityInfo.packageName
        requireAction(pkg != "android" && !pkg.contains("intentresolver"), "choisis_application_par_defaut")
        app.pilot.check(pkg, resolved.loadLabel(packageManager).toString())
        intent.setClassName(pkg, resolved.activityInfo.name)
        startActivity(intent); return JSONObject()
    }
    suspend fun screenshot(): JSONObject {
        guard()
        val shot = suspendCancellableCoroutine<ScreenshotResult> { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (continuation.isActive) continuation.resume(screenshot) else screenshot.hardwareBuffer.close()
                }
                override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resumeWithException(ActionFailure("capture_indisponible")) }
            })
        }
        val data = try {
            withContext(Dispatchers.Default) {
                val hardware = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace) ?: throw ActionFailure("capture_indisponible")
                val software = hardware.copy(Bitmap.Config.ARGB_8888, false); hardware.recycle()
                requireAction(software != null, "capture_indisponible")
                val bitmap = software!!
                try {
                    val scale = minOf(1f, 720f / maxOf(bitmap.width, bitmap.height))
                    val reduced = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                    try {
                        val bytes = ByteArrayOutputStream(); requireAction(reduced.compress(Bitmap.CompressFormat.JPEG, 75, bytes), "capture_indisponible")
                        JSONObject().put("mime", "image/jpeg").put("largeur", reduced.width).put("hauteur", reduced.height)
                            .put("image", android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP))
                    } finally { if (reduced !== bitmap) reduced.recycle() }
                } finally { bitmap.recycle() }
            }
        } finally { shot.hardwareBuffer.close() }
        guard(); return data
    }
    private suspend fun gesture(path: Path, duration: Long) {
        guard()
        suspendCancellableCoroutine<Unit> { continuation ->
            val accepted = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(ActionFailure("geste_annule")) }
            }, null)
            if (!accepted && continuation.isActive) continuation.resumeWithException(ActionFailure("geste_indisponible"))
        }
    }
    private fun displaySize(): Pair<Int, Int> {
        val wm = getSystemService(android.view.WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }
    companion object { private const val SCREEN_BYTES = 56 * 1024 }
}
