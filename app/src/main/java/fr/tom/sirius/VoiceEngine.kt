package fr.tom.sirius

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class ChatMessage(val id: String, val role: String, val text: String)
enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING, RECONNECTING }
data class VoiceState(
    val status: String = "Prêt à t'écouter", val connected: Boolean = false,
    val connectionInterrupted: Boolean = false,
    val wakeRunning: Boolean = false, val conversation: Boolean = false,
    val level: Float = 0f, val playbackLevel: Float = 0f, val messages: List<ChatMessage> = emptyList(),
    val modelReady: Boolean = false, val downloading: Boolean = false,
    val phase: VoicePhase = VoicePhase.IDLE, val subtitle: String = "",
    val subtitleRole: String = "", val locked: Boolean = false,
    val tomTranscript: String = "", val speechActive: Boolean = false,
    val siriusVolume: Float = .6f, val audioRoute: String = "Média, pas encore de lecture",
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.MOYENNE,
    val hudMessages: List<HudMessage> = emptyList(), val hudTomId: String? = null,
    val historyReady: Boolean = false
)
private data class CapturedPhrase(val pcm: ByteArray, val start: Double, val end: Double, val locked: Boolean, val enonce: String)
/** A live piece waiting for upload; pieces of one utterance leave one after the other, in order. */
internal class LiveChunk(val enonce: String, val seq: Int, val pcm: ByteArray, val start: Double, val end: Double,
                        val voicedMs: Int, val locked: Boolean, val last: Boolean)
private data class ReplyAudio(val bytes: ByteArray, val text: String, val key: AudioSentenceKey, val local: Boolean = false, val relance: Boolean = false)
private const val WAKE_LOG_TAG = "SiriusWake"

/** Network/player mutations on Main, AudioRecord and the wake recognizer on one IO loop. */
class VoiceEngine(private val context: Context, private val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(VoiceState())
    val state = mutable.asStateFlow()
    /** Check keyguard before changing the thread or phase, even before the lock broadcast arrives. */
    private fun updateState(transform: (VoiceState) -> VoiceState) {
        mutable.update { transform(withLockState(it, locked())) }
    }
    val modelStore = ModelStore(context)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val timing = ConversationTiming()
    private val announcer = PilotAnnouncer()
    @Volatile private var keepWake = false
    @Volatile private var listening = false
    @Volatile private var playing = false
    @Volatile private var awaiting = false
    @Volatile private var speaking = false
    private val captureGeneration = AtomicInteger()
    private var micJob: Job? = null
    private var timingJob: Job? = null
    private var briefingJob: Job? = null
    private var briefingCallActive = false
    private var briefingSpeech: BriefingSpeech? = null
    private var captionJob: Job? = null
    private var envelopeJob: Job? = null
    private var reconnect: Job? = null
    private var connectionSetup: Job? = null
    private var retries = 0
    private var authRefused = false
    private var socket: WebSocket? = null
    private var socketHelloReceived = false
    private var protocol: VoiceProtocol? = null
    private var upload: Call? = null
    private val pendingPhrases = ArrayDeque<CapturedPhrase>()
    /** Utterance -> accepted question id. A new utterance never replaces an earlier pending answer. */
    private val waitingQuestions = linkedMapOf<String, String?>()
    private val acceptedTomQuestions = linkedSetOf<String>()
    private val answeredQuestions = linkedSetOf<String>()
    /** Server announced "partiels": utterances leave in pieces of about one second (live transcription). */
    @Volatile private var liveSupported = false
    /** Utterance shown in the Tom bubble; "partiel" events of any other utterance are ignored. */
    private var liveEnonce: String? = null
    private var deadEnonce: String? = null
    private val endedUtterances = linkedSetOf<String>()
    @Volatile private var serverEndedEnonce: String? = null
    private val chunkQueue = ArrayDeque<LiveChunk>()
    private var chunkCall: Call? = null
    private var chunkAttempts = 0
    private val chunkHttp by lazy { http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }
    private var epoch = 0
    private var activeQuestion: String? = null
    private var activeReply: String? = null
    private var locallyMutedReply: String? = null
    private var rejectAudio = false
    private val queue = ArrayDeque<ReplyAudio>()
    /** Includes preparation, queued audio and the current player; independent of text in the HUD. */
    private val receivedAudio = mutableSetOf<AudioSentenceKey>()
    /** Keep completed sentences even when send() fails, so the next hello can resend their acks. */
    private val playedAudio = linkedSetOf<AudioSentenceKey>()
    private var player: MediaPlayer? = null
    private var replyEnded = false
    private var serverTimeOffset = 0.0
    /** Conversation opened by the wake word: utterances carry eveil=true until the server accepted one. */
    private var wakeConversation = false
    private var eveilPending = false
    private var eveilEnonce: String? = null
    @Volatile private var pendingWakeAudio: WakeAudio? = null
    var assistantVisible = false
    /** MainActivity is resumed: a visible window, like the assistant sheet, for starting the microphone service. */
    @Volatile var appVisible = false
    /** True while Sirius has the hand (PilotController): the conversation must survive the piloted app. */
    @Volatile var pilotSession = false
    @Volatile private var pilotBusy = false
    @Volatile private var pilotActivityAt = 0L
    /** Exact, complete configured code; include origin and recognized text for diagnosis. */
    var onStopWord: (() -> Unit)? = null
    var onDeviceAction: (() -> Unit)? = null
    var onWake: (() -> Unit)? = null
    @Volatile var wakeAcceptedAt = 0L
        private set
    var onConversationEnd: ((String) -> Unit)? = null
    var onAction: ((JSONObject, (JSONObject) -> Boolean) -> Unit)? = null
    var onTransportLost: (() -> Unit)? = null
    /** Session hooks live with the controller; the voice engine keeps its existing public callbacks. */
    private fun pilot(): PilotController? = (context.applicationContext as? SiriusApp)?.pilot?.also {
        it.onStopSession = ::stopPilotSession
    }
    private fun stopCode(source: String, recognized: String) {
        val controller = pilot()
        if (controller != null) controller.emergencyStop(source, recognized) else onStopWord?.invoke()
    }
    private val playbackAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var audioDucked = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(playbackAttributes)
        .setOnAudioFocusChangeListener(::audioFocusChanged).build()

    private fun audioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // A notification requests a quieter voice, not cancellation of the question or its audio_ack.
                audioDucked = true
                player?.setVolume(playbackGain(), playbackGain())
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioDucked = false
                player?.setVolume(playbackGain(), playbackGain())
            }
            // While Sirius pilots, a reel taking focus must not cancel the conversation.
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (!pilotSession) recover("La sortie audio est occupée.")
        }
    }
    private fun playbackGain() = settings.siriusVolume * if (audioDucked) .2f else 1f

    init { updateState { it.copy(modelReady = modelStore.installed(), siriusVolume = settings.siriusVolume, wakeSensitivity = settings.wakeSensitivity) } }
    fun setWakeSensitivity(value: WakeSensitivity) { settings.wakeSensitivity = value; mutable.update { it.copy(wakeSensitivity = value) } }
    fun setSiriusVolume(value: Float) {
        settings.siriusVolume = value
        val gain = playbackGain()
        mutable.update { it.copy(siriusVolume = settings.siriusVolume) }
        player?.setVolume(gain, gain)
    }
    internal fun configurePlayback(target: MediaPlayer) {
        target.setAudioAttributes(playbackAttributes)
        target.setVolume(playbackGain(), playbackGain())
    }
    // Never set global audio mode or a preferred/communication output device.
    private fun checkMediaRoute(target: MediaPlayer? = null): Boolean {
        val mode = audioManager.mode
        val device = target?.routedDevice
        val callDevice = when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_TELEPHONY -> true
            else -> false
        }
        val blocked = mode != AudioManager.MODE_NORMAL || callDevice
        val route = when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "haut-parleur média"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "écouteur d'appel"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth média"
            null -> "route en attente"
            else -> "sortie type ${device.type}"
        }
        val detail = "Média, mode $mode, $route"
        if (state.value.audioRoute != detail) mutable.update { it.copy(audioRoute = detail) }
        if (blocked) recover("Sortie d'appel détectée. Termine l'appel ou déconnecte le kit d'appel pour écouter Sirius.")
        return !blocked
    }
    fun locked() = keyguard.isDeviceLocked || keyguard.isKeyguardLocked
    /** Each pilot action restarts the 12 s follow-up window; none runs out while an action executes. */
    fun pilotActivity(busy: Boolean) {
        pilotBusy = busy; pilotActivityAt = SystemClock.elapsedRealtime()
        // Media echo is filtered at capture. An action must not discard Tom's overlapping speech.
    }
    fun refreshLockState() {
        val value = locked()
        if (value && briefingCallActive) {
            resetReply()
            report("Déverrouille le téléphone pour écouter ton briefing.")
        }
        updateState { it }
        // Compared with the last announcement on this socket, not with the UI state that phase() also refreshes.
        if (settings.pilotEnabled && state.value.connected) announce(socket, value)
    }
    private fun announce(target: WebSocket?, value: Boolean = locked(), force: Boolean = false) {
        val ws = target ?: return
        announcer.frame(settings.pilotEnabled && !settings.pilotStopped, value, force, settings.pilotStopped)?.let { ws.send(it) }
    }
    fun report(message: String) { mutable.update { it.copy(status = message) } }
    private fun phase(value: VoicePhase, message: String) { updateState { it.copy(phase = value, status = message) } }
    fun hasMicPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun needsSocket() = listening || settings.pilotEnabled

    fun prepareModel() {
        if (state.value.downloading || modelStore.installed()) return
        mutable.update { it.copy(downloading = true) }
        scope.launch {
            try {
                modelStore.install { message -> if (!listening) report(message) }
                mutable.update { it.copy(modelReady = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { report("Modèle indisponible. Vérifie ta connexion et réessaie.") }
            finally { mutable.update { it.copy(downloading = false) } }
        }
    }
    fun wakeStarted() {
        if (!hasMicPermission() || !modelStore.installed()) {
            report("Ouvre Sirius pour autoriser le micro et télécharger le modèle."); WakeService.stop(context); return
        }
        keepWake = true; mutable.update { it.copy(wakeRunning = true) }
        if (!listening) report("Dis Sirius pour me réveiller")
        startMicrophone()
    }
    fun wakeStopped() {
        keepWake = false; mutable.update { it.copy(wakeRunning = false) }
        if (!listening) { micJob?.cancel(); report("Prêt à t'écouter") }
    }
    fun beginConversation(fromWake: Boolean = false) {
        val beganAt = SystemClock.elapsedRealtime()
        Log.i("SiriusAnim", "conversation debut reveil=$fromWake")
        // onShow, wake callbacks and window recreation can all request the same conversation.
        if (listening) { ensureConnection(); return }
        if (!hasMicPermission()) { report("Autorise le micro dans l'application Sirius."); return }
        if (!settings.read().configured) { report("Règle la connexion au serveur dans Sirius."); return }
        if (!ConversationService.start(context)) { report("Ouvre Sirius pour reprendre le micro."); return }
        listening = true; authRefused = false
        wakeConversation = fromWake; eveilPending = fromWake; eveilEnonce = null
        if (!fromWake) pendingWakeAudio = null
        endedUtterances.clear(); serverEndedEnonce = null
        captureGeneration.incrementAndGet(); timing.listen(SystemClock.elapsedRealtime())
        updateState { it.copy(conversation = true, phase = VoicePhase.LISTENING, status = "Je t'écoute", subtitle = "", subtitleRole = "", tomTranscript = "", hudTomId = null, speechActive = false) }
        announceReady()
        ensureConnection(); startMicrophone(); startTiming()
        Log.i("SiriusAnim", "conversation micro_demande duree_ms=${SystemClock.elapsedRealtime() - beganAt}")
    }
    internal fun beginBriefing(
        fetch: suspend () -> String = { BriefingClient(http).fetch(settings.read()) },
        synthesize: (suspend (String) -> ByteArray)? = null
    ) {
        if (locked()) { report("Déverrouille le téléphone pour écouter ton briefing."); return }
        if (!listening) return
        resetReply()
        briefingCallActive = true
        awaiting = true; replyEnded = false
        timing.waitForReply(SystemClock.elapsedRealtime())
        phase(VoicePhase.THINKING, "Je prépare ton briefing")
        briefingJob = scope.launch {
            var speech: BriefingSpeech? = null
            try {
                val text = fetch()
                if (locked() || !listening) { resetReply(); return@launch }
                if (synthesize == null) speech = BriefingSpeech(context).also { briefingSpeech = it }
                val id = "briefing-${java.util.UUID.randomUUID()}"
                // Android TTS limits each synthesis input. Bound memory to one queued segment.
                val pieces = briefingPieces(text)
                for ((index, piece) in pieces.withIndex()) {
                    while (player != null || queue.isNotEmpty()) delay(80)
                    val bytes = synthesize?.invoke(piece) ?: speech!!.synthesize(piece)
                    if (locked() || !listening) { resetReply(); return@launch }
                    updateState { withHudAudio(it, id, piece, "briefing:$index", null) }
                    queue.addLast(ReplyAudio(bytes, piece, AudioSentenceKey(id, epoch, index), local = true))
                    playNext()
                }
                replyEnded = true
                if (player == null && queue.isEmpty()) replyFinished()
            } catch (_: TimeoutCancellationException) { recover("Le briefing est indisponible. Réessaie.") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { recover("Le briefing est indisponible. Réessaie.") }
            finally {
                speech?.close()
                if (briefingSpeech === speech) briefingSpeech = null
            }
        }
    }

    fun endConversation(reason: String = "serveur") {
        if (listening || state.value.conversation) onConversationEnd?.invoke(reason)
        listening = false; awaiting = false; speaking = false; pendingPhrases.clear(); waitingQuestions.clear(); answeredQuestions.clear()
        wakeConversation = false; eveilPending = false; eveilEnonce = null
        pendingWakeAudio = null
        activeQuestion = null; activeReply = null; locallyMutedReply = null; rejectAudio = true
        captureGeneration.incrementAndGet(); timing.stop(); timingJob?.cancel()
        upload?.cancel(); upload = null
        chunkCall?.cancel(); chunkCall = null; chunkQueue.clear(); chunkAttempts = 0; liveEnonce = null
        endedUtterances.clear(); serverEndedEnonce = null
        socket?.send(VoiceProtocol.speech(false)); socket?.send(VoiceProtocol.ready(false))
        stopPlayback()
        // A window or the follow-up timeout closes the microphone, not the displayed history.
        updateState { it.copy(conversation = false, speechActive = false, tomTranscript = "", hudTomId = null, level = 0f, phase = VoicePhase.IDLE, subtitle = "", subtitleRole = "",
            status = if (keepWake) "Dis Sirius pour me réveiller" else "Prêt à t'écouter") }
        if (!settings.pilotEnabled) closeConnection()
        if (!keepWake) micJob?.cancel()
        ConversationService.stop(context)
    }
    fun configurationChanged() {
        endConversation(); closeConnection(); playedAudio.clear()
        mutable.update { clearHud(it).copy(messages = emptyList(), historyReady = false) }
        authRefused = false; ensureConnection()
    }
    fun pilotChanged() {
        announce(socket, force = true)
        if (needsSocket()) { authRefused = false; ensureConnection() } else closeConnection()
    }
    fun ensureConnection() { if (needsSocket() && !authRefused && socket == null && reconnect?.isActive != true) connect() }
    private fun closeConnection() {
        connectionSetup?.cancel(); connectionSetup = null
        reconnect?.cancel(); reconnect = null; onTransportLost?.invoke()
        val old = socket; socket = null; socketHelloReceived = false; announcer.reset(); old?.close(1000, "Fin")
        mutable.update { it.copy(connected = false, connectionInterrupted = false) }
    }
    private fun connectionOpened(ws: WebSocket) {
        if (socket !== ws || !needsSocket()) { ws.close(1000, "Fin"); return }
        mutable.update { it.copy(connected = true, connectionInterrupted = false) }; retries = 0; disconnectedAt = 0L
        // Wait for hello's epoch before replaying acks and announcing ready.
        ws.send(VoiceProtocol.hello(settings.pilotStopCode))
        announcer.reset(); announce(ws, force = true)
    }
    private fun announceReady() {
        val ws = socket ?: return
        if (!socketHelloReceived || !state.value.connected) return
        playedAudio.filter { it.epoch == epoch }.forEach { ws.send(VoiceProtocol.audioAck(it)) }
        ws.send(VoiceProtocol.ready(listening))
    }
    private fun connect() {
        if (socket != null || connectionSetup?.isActive == true || !needsSocket() || authRefused) return
        connectionSetup = scope.launch {
            val values = withContext(Dispatchers.IO) { settings.read() }
            connectionSetup = null
            if (socket == null && needsSocket() && !authRefused) openSocket(values)
        }
    }
    private fun openSocket(values: ServerSettings) {
        if (!values.configured) return
        val p = VoiceProtocol(normalizeAddress(values.address), values, settings.clientId); protocol = p
        socket = http.newWebSocket(p.socketRequest(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { main.post {
                connectionOpened(webSocket)
            } }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 6_000_000) return
                try {
                    val json = JSONObject(text)
                    if (json.has("client") && json.optString("client") != settings.clientId) return
                    val bytes = if (json.optString("type") == "audio") Base64.decode(json.getString("audio"), Base64.DEFAULT) else null
                    main.post {
                        if (socket !== webSocket || !state.value.connected) return@post
                        if (json.optString("type") == "action") {
                            // Result sender is bound to this exact authenticated transport, even after an approval tap.
                            onAction?.invoke(json) { result -> socket === webSocket && state.value.connected && webSocket.send(result.toString()) }
                        } else handleEvent(json, bytes)
                    }
                } catch (_: Exception) { main.post { if (socket === webSocket && listening) report("Réponse du serveur invalide.") } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code; response?.close(); main.post { connectionLost(webSocket, code) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, ""); main.post { connectionLost(webSocket, null) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { connectionLost(webSocket, null) } }
        })
    }
    private var disconnectedAt = 0L
    private fun connectionLost(ws: WebSocket, code: Int?) {
        if (socket !== ws) return
        socket = null; socketHelloReceived = false; announcer.reset(); mutable.update { it.copy(connected = false, connectionInterrupted = true) }; onTransportLost?.invoke()
        if (!needsSocket()) return
        if (code == 401 || code == 403) {
            authRefused = true; endConversation(); report("Connexion refusée. Vérifie ton identifiant et ton mot de passe."); return
        }
        if (disconnectedAt == 0L) disconnectedAt = SystemClock.elapsedRealtime()
        if (listening) timing.connectionLost(disconnectedAt)
        // Buffered audio and pending answers survive a transport retry, including acks completed offline.
        if (listening) phase(VoicePhase.RECONNECTING, "Je retrouve ton serveur")
        reconnect?.cancel()
        val delayMs = ConversationTiming.reconnectDelay(retries++, SystemClock.elapsedRealtime() - disconnectedAt)
        reconnect = scope.launch { delay(delayMs); reconnect = null; connect() }
    }
    private fun addMessage(json: JSONObject, live: Boolean = false, fromHistory: Boolean = false) {
        val id = json.optString("id"); val role = json.optString("role"); val text = json.optString("text").take(16000)
        if (id.isBlank() || role !in listOf("tom", "sirius") || text.isBlank()) return
        // A committed historical answer remains valid when disconnect increments the server epoch.
        val valid = !json.optBoolean("cancelled") && (fromHistory || json.optInt("epoch", epoch) >= epoch)
        updateState { current -> withServerMessage(current, ChatMessage(id, role, text),
            live && valid,
            protocolId(json.optString("enonce")),
            protocolId(json.optString("reply_id")),
            protocolId(json.optString("reply_to")),
            valid, waitingQuestions.entries.firstOrNull { it.value == id }?.key) }
    }
    private fun bindQuestion(enonce: String, question: String) {
        if (!waitingQuestions.containsKey(enonce)) return
        waitingQuestions[enonce] = question
        state.value.messages.firstOrNull { it.id == question && it.role == "tom" }?.let { message ->
            updateState { withServerMessage(it, message, live = true, questionEnonce = enonce) }
            if (question in acceptedTomQuestions && !StopWords.matchesCode(message.text, settings.pilotStopCode)) pilot()?.questionAccepted(enonce)
        }
        if (question in answeredQuestions) {
            waitingQuestions.remove(enonce)
            if (replyEnded && !playing && queue.isEmpty()) replyFinished()
        }
    }
    private fun handleEvent(json: JSONObject, bytes: ByteArray?) {
        val type = json.optString("type")
        if (type != "hello" && type != "message" && !listening) return
        if (type in listOf("thinking", "reply_start", "audio", "reply_end", "error") && json.optInt("epoch", epoch) < epoch) return
        if (briefingCallActive && type in listOf("thinking", "reply_start", "audio", "reply_end", "interrupted", "ignored", "error")) return
        when (type) {
            "hello" -> {
                liveSupported = json.optJSONObject("capabilities")?.optBoolean("partiels") == true
                pilot()?.automaticScreen = json.optJSONObject("capabilities")?.optBoolean("phone_action_state") == true
                val previousEpoch = epoch
                epoch = json.optInt("epoch")
                if (epoch != previousEpoch) locallyMutedReply = null
                if (epoch != previousEpoch) {
                    if (!briefingCallActive) stopPlayback()
                    activeReply = null
                    playedAudio.removeAll { it.epoch != epoch }
                }
                socketHelloReceived = true
                serverTimeOffset = json.optDouble("time", System.currentTimeMillis() / 1000.0) - System.currentTimeMillis() / 1000.0
                json.optJSONArray("messages")?.let { messages ->
                    val history = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
                    val before = state.value
                    val known = before.messages.map { it.id }.toSet()
                    val anchor = history.indexOfLast { it.optString("id") in known }
                    history.forEachIndexed { index, message ->
                        // Only unseen turns after the previous history cursor can complete a missed live turn.
                        val missed = before.historyReady && (anchor >= 0 || known.isEmpty()) &&
                            index > anchor && message.optString("id") !in known
                        addMessage(message, live = missed, fromHistory = true)
                    }
                }
                mutable.update { it.copy(historyReady = true) }
                val resumeStatus = json.optJSONObject("audio_resume")?.optString("status")
                val serverRestarted = epoch < previousEpoch
                if (!briefingCallActive && awaiting &&
                    ((epoch > previousEpoch && resumeStatus == "lost") || serverRestarted)) {
                    // Compatibility with servers that explicitly cancelled the previous generation.
                    waitingQuestions.entries.removeAll { it.value != null }
                    replyEnded = true
                    if (player == null && queue.isEmpty()) replyFinished()
                    if (listening) report("Connexion retrouvée. Le serveur a perdu la réponse ; repose ta question.")
                } else if (listening) phase(if (playing) VoicePhase.SPEAKING else if (awaiting) VoicePhase.THINKING else VoicePhase.LISTENING,
                    if (playing) "Sirius parle" else if (awaiting) "Sirius réfléchit" else "Je t'écoute")
                announceReady()
                if (listening && !awaiting && !playing && !state.value.speechActive) timing.listen(SystemClock.elapsedRealtime())
                sendNextPhrase()
                pumpChunks()
            }
            "fin_de_tour" -> endServerUtterance(json)
            "partiel" -> updateState { current ->
                withLiveTranscript(current, json.optString("text"), json.optString("enonce") == liveEnonce)
            }
            "message" -> {
                val isNew = state.value.messages.none { it.id == json.optString("id") }
                addMessage(json, live = true)
                if (json.optString("role") == "tom" && !json.optBoolean("cancelled") &&
                    json.optInt("epoch", epoch) >= epoch && json.optString("text").isNotBlank()) {
                    val enonce = protocolId(json.optString("enonce"))
                    acceptedTomQuestions.add(json.optString("id"))
                    while (acceptedTomQuestions.size > 200) acceptedTomQuestions.remove(acceptedTomQuestions.first())
                    if (enonce != null && waitingQuestions.containsKey(enonce)) bindQuestion(enonce, json.optString("id"))
                    val recognized = json.optString("text")
                    if (StopWords.matchesCode(recognized, settings.pilotStopCode)) {
                        if (isNew && pilotSession) stopCode("mot_code_serveur", recognized)
                    } else if (isNew) pilot()?.questionAccepted(enonce)
                }
            }
            "thinking" -> if (awaiting && !playing && !rejectAudio) { activeQuestion = json.optString("reply_to").takeIf { it.isNotBlank() && it != "null" } ?: json.optString("id"); phase(VoicePhase.THINKING, "Sirius réfléchit") }
            "reply_start" -> {
                if (json.optString("id") == locallyMutedReply) return
                if (!awaiting || rejectAudio || json.optInt("epoch") < epoch) return
                epoch = json.optInt("epoch"); activeReply = json.optString("id"); replyEnded = false
                activeQuestion = protocolId(json.optString("reply_to")) ?: activeQuestion
                if (!playing) phase(VoicePhase.THINKING, "Sirius réfléchit")
            }
            "audio" -> {
                val id = protocolId(json.optString("id")) ?: return
                if (id == locallyMutedReply) return
                val audioEpoch = json.optInt("epoch", -1)
                val index = json.optInt("index", -1)
                if (audioEpoch != epoch || index < 0) return
                val key = AudioSentenceKey(id, audioEpoch, index)
                if (key in playedAudio) { socket?.send(VoiceProtocol.audioAck(key)); return }
                if (key in receivedAudio) return
                if (!awaiting || rejectAudio || json.optInt("epoch") != epoch || bytes == null || bytes.size > 3_000_000 || (activeReply != null && json.optString("id") != activeReply)) return
                if (json.optString("mime") != "audio/mpeg") { recover("Format audio inconnu."); return }
                // Bound PCM memory, not the number of sentences: 38 short clips fit comfortably.
                if (queue.sumOf { it.bytes.size } + bytes.size > 12_000_000) { recover("Réponse trop longue."); return }
                val text = json.optString("text").take(16000)
                receivedAudio.add(key)
                updateState { withHudAudio(it, id, text, "$audioEpoch:$index", protocolId(json.optString("reply_to"))) }
                queue.addLast(ReplyAudio(bytes, text, key, relance = json.optBoolean("relance") || id.startsWith("relance-"))); playNext()
            }
            "reply_end" -> {
                if (!awaiting || json.optInt("epoch") != epoch || rejectAudio || (activeReply != null && json.optString("id") != activeReply)) return
                val question = protocolId(json.optString("reply_to")) ?: activeQuestion
                // Tom, 2.4: a server "relance" (short waiting clip while Sirius thinks) is not the answer.
                // Ending it must keep the question waiting, or the 12 s window closes the conversation.
                val relance = json.optBoolean("relance") || json.optString("id").startsWith("relance-")
                if (!relance && question != null) {
                    answeredQuestions.add(question)
                    while (answeredQuestions.size > 200) answeredQuestions.remove(answeredQuestions.first())
                    waitingQuestions.entries.removeAll { it.value == question }
                }
                replyEnded = true; if (player == null && queue.isEmpty()) replyFinished()
            }
            "interrupted" -> {
                val interruptedEpoch = json.optInt("epoch", epoch)
                if (interruptedEpoch < epoch) return
                // reply_start for this epoch already established the replacement response.
                // A delayed new_question frame must not mute that very response.
                if (json.optString("reason") == "new_question" && interruptedEpoch == epoch && activeReply != null) return
                val target = protocolId(json.optString("reply_to"))
                if (target != null && target != activeQuestion && waitingQuestions.values.none { it == target }) return
                // The real server sends new_question with reply_to=null AFTER the final short upload.
                // Unbound/newer questions already waiting belong to the next epoch, not to the old reply.
                val cancelledQuestion = target ?: activeQuestion
                val keepNext = json.optString("reason") == "new_question" && waitingQuestions.any {
                    it.value == null || it.value != cancelledQuestion
                }
                epoch = interruptedEpoch; stopPlayback(); activeReply = null
                if (keepNext) waitingQuestions.entries.removeAll { it.value != null && it.value == cancelledQuestion }
                else waitingQuestions.clear()
                activeQuestion = null
                awaiting = keepNext; rejectAudio = !keepNext; replyEnded = false
                if (!speaking) timing.listen(SystemClock.elapsedRealtime())
                if (keepNext) timing.waitForReply(SystemClock.elapsedRealtime())
                phase(if (keepNext) VoicePhase.THINKING else VoicePhase.LISTENING,
                    if (keepNext) "Sirius réfléchit" else "Je t'écoute")
            }
            "ignored" -> {
                // The server ignored the latest utterance (noise, no text). An earlier question still being
                // answered keeps the conversation waiting instead of falling back to the 12 s window (Tom, 2.4).
                waitingQuestions.keys.lastOrNull { waitingQuestions[it] == null }?.let { waitingQuestions.remove(it) }
                if (awaiting && waitingQuestions.isNotEmpty()) return
                awaiting = false; timing.listen(SystemClock.elapsedRealtime()); phase(VoicePhase.LISTENING, "Je n'ai pas entendu de phrase. Réessaie.")
            }
            "error" -> recover("La réponse est indisponible. Réessaie.")
            // Server found no "Sirius" in the first utterance after a wake: back to standby, silently.
            "reveil_rejete" -> if (wakeConversation) { endConversation("reveil_rejete"); mutable.update { clearHud(it) } }
        }
    }
    /** The server can end a live utterance before the local silence detector emits its last piece. */
    private fun endServerUtterance(json: JSONObject) {
        val enonce = protocolId(json.optString("enonce")) ?: return
        if (enonce in endedUtterances || json.optInt("epoch", epoch) < epoch) return
        val capturing = liveEnonce == enonce
        if (!capturing && chunkQueue.none { it.enonce == enonce } && !waitingQuestions.containsKey(enonce)) return
        endedUtterances.add(enonce)
        while (endedUtterances.size > 128) endedUtterances.remove(endedUtterances.first())
        // Detach before cancel: the asynchronous failure callback must not retry or recover this turn.
        if (chunkQueue.firstOrNull()?.enonce == enonce) {
            val call = chunkCall; chunkCall = null; chunkAttempts = 0; call?.cancel()
        }
        chunkQueue.removeAll { it.enonce == enonce }
        if (enonce == eveilEnonce) eveilPending = false
        if (capturing) {
            serverEndedEnonce = enonce; speaking = false
            mutable.update { it.copy(speechActive = false, level = 0f) }
            socket?.send(VoiceProtocol.speech(false))
        }
        if (!waitingQuestions.containsKey(enonce)) expectReply(enonce)
        protocolId(json.optString("id"))?.let { bindQuestion(enonce, it) }
        // A delayed end of an earlier turn must not hide Tom's newer speech or cut existing playback.
        if (!playing && (capturing || !state.value.speechActive)) phase(VoicePhase.THINKING, "Sirius réfléchit")
        pumpChunks()
    }
    private fun sendPhrase(phrase: CapturedPhrase) {
        if (!listening) return
        expectReply(phrase.enonce)
        pendingPhrases.addLast(phrase); sendNextPhrase()
    }
    private fun expectReply(enonce: String) {
        if (briefingCallActive) resetReply()
        waitingQuestions.putIfAbsent(enonce, null)
        if (!awaiting) { rejectAudio = false; replyEnded = false; activeQuestion = null; activeReply = null }
        awaiting = true
        timing.waitForReply(SystemClock.elapsedRealtime())
        if (!playing && !state.value.speechActive) phase(VoicePhase.THINKING, "Sirius réfléchit")
    }
    private fun sendNextPhrase() {
        if (upload != null || !listening) return
        if (!state.value.connected) { ensureConnection(); return }
        pendingPhrases.removeFirstOrNull()?.let(::uploadPhrase)
    }
    private fun uploadPhrase(phrase: CapturedPhrase) {
        if (!listening) return
        rejectAudio = false
        val p = protocol ?: return
        val eveil = phrase.enonce == eveilEnonce
        val request = p.phraseRequest(wavBytes(phrase.pcm), phrase.start + serverTimeOffset, phrase.end + serverTimeOffset, phrase.locked || locked(), eveil)
        val call = http.newCall(request); upload = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post {
                if (upload === call) { upload = null; recover("Envoi impossible. Repose ta question.") }
            } }
            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    val body = it.body?.string()?.take(4000).orEmpty()
                    Pair(it.code == 202, runCatching { JSONObject(body).optString("id") }.getOrDefault(""))
                }
                main.post {
                    if (upload !== call) return@post
                    upload = null
                    if (result.first && eveil) eveilPending = false
                    if (!result.first) recover("Le serveur a refusé la phrase. Vérifie tes réglages.")
                    else if (result.second.isNotBlank() && waitingQuestions.containsKey(phrase.enonce)) bindQuestion(phrase.enonce, result.second)
                    sendNextPhrase()
                }
            }
        })
    }
    /** Live piece from the microphone loop. The last one ends the utterance: Sirius may answer from here on. */
    private fun sendChunk(chunk: LiveChunk) {
        if (!listening || chunk.enonce == deadEnonce || chunk.enonce in endedUtterances) return
        if (chunk.last) {
            expectReply(chunk.enonce)
        }
        chunkQueue.addLast(chunk); pumpChunks()
    }
    /** One upload at a time, in order, two retries on network or server errors (the server ignores duplicates). */
    private fun pumpChunks() {
        if (chunkCall != null || !listening) return
        val chunk = chunkQueue.firstOrNull() ?: return
        val p = protocol ?: return
        // A silent last piece carries no audio: the final text is then ready as soon as the server gets it.
        val wav = if (chunk.last && chunk.seq > 0 && chunk.voicedMs == 0) null else wavBytes(chunk.pcm)
        val call = chunkHttp.newCall(p.chunkRequest(wav, chunk.enonce, chunk.seq, chunk.last, chunk.voicedMs,
            chunk.start + serverTimeOffset, chunk.end + serverTimeOffset, chunk.locked || locked(), chunk.enonce == eveilEnonce))
        chunkCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { chunkDone(call, chunk, null, "") } }
            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    val body = it.body?.string()?.take(4000).orEmpty()
                    Pair(it.code, runCatching { JSONObject(body).optString("id") }.getOrDefault(""))
                }
                main.post { chunkDone(call, chunk, result.first, result.second) }
            }
        })
    }
    private fun chunkDone(call: Call, chunk: LiveChunk, code: Int?, id: String) {
        if (chunkCall !== call) return
        chunkCall = null
        if (code == 202) {
            chunkQueue.remove(chunk); chunkAttempts = 0
            if (chunk.enonce == eveilEnonce) eveilPending = false
            if (chunk.last && id.isNotBlank() && waitingQuestions.containsKey(chunk.enonce)) bindQuestion(chunk.enonce, id)
        } else if ((code == null || code == 429 || code >= 500) && chunkAttempts < 2) {
            chunkAttempts++
            scope.launch { delay(400L * chunkAttempts); pumpChunks() }
            return
        } else {
            // This utterance cannot reach the server complete: drop it rather than send half a sentence.
            chunkAttempts = 0; deadEnonce = chunk.enonce
            chunkQueue.removeAll { it.enonce == chunk.enonce }
            recover(if (code == null) "Envoi impossible. Repose ta question." else "Le serveur a refusé la phrase. Vérifie tes réglages.")
        }
        pumpChunks()
    }
    /** Stop sound promptly, but leave the utterance and question bookkeeping to the server's interrupted. */
    internal fun interruptForSpeech(voicedMs: Int) {
        if (!playing || voicedMs < PhraseDetector.INTERRUPTION_MS) return
        locallyMutedReply = activeReply
        stopPlayback()
        if (listening) phase(VoicePhase.LISTENING, "Je t'écoute")
    }
    /** Invalidate the old server request too, while keeping the voice conversation and permission. */
    private fun stopPilotSession() {
        locallyMutedReply = activeReply
        resetReply()
        chunkCall?.cancel(); chunkCall = null; chunkQueue.clear()
        liveEnonce?.let { endedUtterances.add(it); serverEndedEnonce = it }
        socket?.send(VoiceProtocol.interrupt())
    }
    private fun resetReply() {
        rejectAudio = true; stopPlayback(); awaiting = false; pendingPhrases.clear(); waitingQuestions.clear(); answeredQuestions.clear(); activeQuestion = null; activeReply = null
        upload?.cancel(); upload = null
        timing.listen(SystemClock.elapsedRealtime())
    }
    private fun recover(message: String) { resetReply(); if (listening) phase(VoicePhase.LISTENING, message) else report(message) }
    private fun stopPlayback() {
        briefingJob?.cancel(); briefingJob = null; briefingCallActive = false
        briefingSpeech?.close(); briefingSpeech = null
        captionJob?.cancel(); envelopeJob?.cancel(); envelopeJob = null
        queue.clear(); receivedAudio.clear(); player?.release(); player = null; playing = false; audioDucked = false
        mutable.update { finishHudReply(it).copy(playbackLevel = 0f) }
        audioManager.abandonAudioFocusRequest(focus)
    }
    private fun playNext() {
        if (player != null || queue.isEmpty()) return
        if (!checkMediaRoute()) return
        val segment = queue.removeFirst(); val bytes = segment.bytes
        if (segment.local && locked()) { resetReply(); return }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { recover("La sortie audio est occupée."); return }
        val next = MediaPlayer(); player = next
        var envelope = PcmEnvelope.SILENT
        try {
            configurePlayback(next)
            next.setDataSource(object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun close() {}
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt()); bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count); return count
                }
            })
            next.setOnPreparedListener { if (player === it) {
                if (segment.local && locked()) { resetReply(); return@setOnPreparedListener }
                if (!checkMediaRoute()) return@setOnPreparedListener
                playing = true; if (!segment.relance) timing.audioStarted(); phase(VoicePhase.SPEAKING, "Sirius parle"); it.start()
                captionJob?.cancel()
                captionJob = scope.launch {
                    while (player === next && isActive) {
                        if (!checkMediaRoute(next)) break
                        val length = ((next.currentPosition.toFloat() / next.duration.coerceAtLeast(1)) * segment.text.length).toInt().coerceIn(0, segment.text.length)
                        mutable.update { current -> current.copy(subtitle = segment.text.take(length), subtitleRole = "sirius", playbackLevel = envelope.at(next.currentPosition)) }
                        delay(40)
                    }
                }
            } }
            next.setOnCompletionListener { if (player === it) {
                receivedAudio.remove(segment.key)
                if (!segment.local) {
                    playedAudio.add(segment.key)
                    socket?.send(VoiceProtocol.audioAck(segment.key))
                }
                captionJob?.cancel(); envelopeJob?.cancel(); envelopeJob = null
                mutable.update { current -> current.copy(subtitle = segment.text, subtitleRole = "sirius", playbackLevel = 0f) }
                it.release(); player = null; playing = false; playNext()
                if (player == null && queue.isEmpty()) {
                    if (replyEnded) replyFinished() else { timing.waitForReply(SystemClock.elapsedRealtime()); phase(VoicePhase.THINKING, "Sirius prépare la suite") }
                }
            } }
            next.setOnErrorListener { failed, _, _ -> if (player === failed) recover("Lecture audio impossible. Réessaie."); true }
            next.prepareAsync()
            envelopeJob = scope.launch {
                val decoded = withContext(Dispatchers.IO) {
                    try { decodeEnvelope(context, bytes) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { PcmEnvelope.SILENT }
                }
                if (player === next) envelope = decoded
            }
        } catch (_: Exception) { recover("Lecture audio impossible. Réessaie.") }
    }
    private fun replyFinished() {
        if (!awaiting) return // Duplicate reply_end must not reopen the 12 s window.
        briefingCallActive = false
        mutable.update { finishHudReply(it) }
        awaiting = waitingQuestions.isNotEmpty(); activeQuestion = null; activeReply = null
        if (state.value.connectionInterrupted && !state.value.connected) timing.connectionLost(SystemClock.elapsedRealtime())
        else if (awaiting) timing.waitForReply(SystemClock.elapsedRealtime())
        else if (!speaking) timing.listen(SystemClock.elapsedRealtime())
        audioManager.abandonAudioFocusRequest(focus)
        if (listening) {
            if (state.value.connectionInterrupted && !state.value.connected) phase(VoicePhase.RECONNECTING, "Je retrouve ton serveur")
            else phase(if (awaiting) VoicePhase.THINKING else VoicePhase.LISTENING, if (awaiting) "Sirius réfléchit" else "Je t'écoute")
        }
    }
    private fun startTiming() {
        timingJob?.cancel()
        timingJob = scope.launch {
            while (listening && isActive) {
                val now = SystemClock.elapsedRealtime(); refreshLockState()
                if (timing.responseDue(now, playing)) {
                    recover("Pas de réponse après 5 min. Tu peux réessayer.")
                } else if (timing.sleepDue(now, speaking, playing, awaiting) && !pilotBusy &&
                    now - pilotActivityAt >= ConversationTiming.FOLLOW_UP_MS) { endConversation("delai"); break }
                delay(100)
            }
        }
    }
    private fun startMicrophone() {
        if (micJob?.isActive == true) return
        val previous = micJob
        micJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            var recorder: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            var outputLevel: PlaybackLevelReference? = null
            var model: Model? = null
            var wakeRecognizer: Recognizer? = null
            var stopRecognizer: Recognizer? = null
            var stopListening = true
            val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sirius:microphone")
            try {
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, FRAME_SAMPLES * 8))
                recorder = input; check(input.state == AudioRecord.STATE_INITIALIZED)
                if (AcousticEchoCanceler.isAvailable()) aec = runCatching { AcousticEchoCanceler.create(input.audioSessionId)?.apply { enabled = true } }.getOrNull()
                if (NoiseSuppressor.isAvailable()) ns = runCatching { NoiseSuppressor.create(input.audioSessionId)?.apply { enabled = true } }.getOrNull()
                wakeLock.acquire(10 * 60 * 1000L); var lockRenewed = SystemClock.elapsedRealtime()
                input.startRecording()
                outputLevel = PlaybackLevelReference()
                val echoGate = MediaEchoGate()
                val quietFrame = ByteArray(FRAME_SAMPLES * 2)
                val frame = ShortArray(FRAME_SAMPLES); val vad = PhraseDetector()
                var generation = captureGeneration.get(); var startedAt = 0.0; var lastMeter = 0L
                var pieceFrom = 0.0; var utterance = ""
                var interruptionPosted = false
                var lastPlaybackTrace = 0L
                val wakeBuffer = WakeAudioBuffer()
                var wakeAudio: WakeAudio? = null
                while (isActive && (keepWake || listening)) {
                    val elapsed = SystemClock.elapsedRealtime()
                    if (elapsed - lockRenewed > 9 * 60 * 1000) {
                        if (wakeLock.isHeld) wakeLock.release()
                        wakeLock.acquire(10 * 60 * 1000L); lockRenewed = elapsed
                    }
                    val n = input.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING); check(n > 0)
                    val bytes = pcmBytes(frame, n); val energy = rms(frame, n)
                    if (generation != captureGeneration.get()) {
                        generation = captureGeneration.get(); vad.reset(); wakeRecognizer?.reset(); wakeBuffer.discard(); wakeAudio = null; speaking = false; interruptionPosted = false
                    }
                    if (keepWake && !listening && wakeRecognizer == null && modelStore.installed()) {
                        // The model may already be loaded by the stop words of a pilot session.
                        val loaded = model ?: Model(modelStore.directory.absolutePath)
                        model = loaded
                        // Per-word scores: WakeFilter only trusts a final result where both words are clear.
                        wakeRecognizer = Recognizer(loaded, SAMPLE_RATE.toFloat(), WakeFilter.grammar()).apply { setWords(true) }
                    }
                    if (listening) {
                        val now = System.currentTimeMillis()
                        if (utterance == serverEndedEnonce) {
                            vad.finishByServer(); utterance = ""; wakeAudio = null
                            speaking = false; interruptionPosted = false
                        }
                        val wasSpeaking = vad.speaking
                        vad.live = liveSupported
                        val playback = playing
                        val reference = outputLevel?.read(elapsed)
                        val spectrum = if (reference != null) EchoSpectrum.of(DoubleArray(n) { frame[it].toDouble() }, SAMPLE_RATE.toDouble()) else null
                        val residual = echoGate.voiceEnergy(energy, reference?.rms, elapsed, vad.speaking, spectrum, reference?.spectrum)
                        val suppressTranscript = residual < 1.0 && energy > 0.0
                        // Echo frames are silence, including preroll, never chunks of the speaker's words.
                        // Tom can still start or continue a phrase while a pilot action or media is active.
                        val captureBytes = if (suppressTranscript) quietFrame else bytes
                        val piece = vad.feed(captureBytes, residual, playback, locked())
                        speaking = vad.speaking
                        if (BuildConfig.DEBUG && playing && elapsed - lastPlaybackTrace >= 100) {
                            lastPlaybackTrace = elapsed
                            Log.d("SiriusBarge", "rms=${energy.toInt()} ready=${vad.interruptionReady} suppressed=$suppressTranscript")
                        }
                        if (!vad.interruptionReady) interruptionPosted = false
                        if (vad.interruptionReady && !interruptionPosted) {
                            interruptionPosted = true
                            val captureId = generation
                            main.post { if (listening && captureId == captureGeneration.get()) interruptForSpeech(PhraseDetector.INTERRUPTION_MS) }
                        }
                        if (!wasSpeaking && vad.speaking) {
                            startedAt = now / 1000.0 - .3; pieceFrom = startedAt
                            wakeAudio = pendingWakeAudio; pendingWakeAudio = null
                            val id = VoiceProtocol.newUtteranceId().also { utterance = it }
                            timing.speechStarted(preserveReply = awaiting)
                            val captureId = generation
                            main.post { if (listening && captureId == captureGeneration.get() && id !in endedUtterances) {
                                liveEnonce = id
                                pilot()?.questionStarted(id)
                                if (eveilPending) eveilEnonce = id
                                timing.speechStarted(preserveReply = awaiting); socket?.send(VoiceProtocol.speech(true, eveilPending))
                                if (!playing) phase(VoicePhase.LISTENING, "Je t'écoute")
                                updateState { beginHudUtterance(it, id).copy(subtitle = "", subtitleRole = "", tomTranscript = "", speechActive = true) }
                            } }
                        }
                        if (piece != null && !vad.live) {
                            val prefix = wakeAudio; wakeAudio = null
                            val captured = CapturedPhrase((prefix?.pcm ?: byteArrayOf()) + piece.pcm,
                                startedAt - (prefix?.seconds ?: 0.0), now / 1000.0,
                                vad.completedLocked || locked() || prefix?.locked == true, utterance); val captureId = generation
                            main.post { if (listening && captureId == captureGeneration.get()) { mutable.update { it.copy(speechActive = false) }; socket?.send(VoiceProtocol.speech(false)); sendPhrase(captured) } }
                        } else if (piece != null) {
                            // Live piece: sent at once so Tom sees his words; the last one ends the utterance.
                            val prefix = wakeAudio; wakeAudio = null
                            val chunk = LiveChunk(utterance, piece.seq, (prefix?.pcm ?: byteArrayOf()) + piece.pcm,
                                pieceFrom - (prefix?.seconds ?: 0.0), now / 1000.0, piece.voicedMs + (prefix?.ms ?: 0),
                                piece.locked || locked() || prefix?.locked == true, piece.last)
                            pieceFrom = now / 1000.0
                            val captureId = generation
                            main.post { if (listening && captureId == captureGeneration.get() && chunk.enonce !in endedUtterances) {
                                if (chunk.last) { mutable.update { it.copy(speechActive = false) }; socket?.send(VoiceProtocol.speech(false)) }
                                sendChunk(chunk)
                            } }
                        } else if (wasSpeaking && !vad.speaking) {
                            val captureId = generation
                            main.post { if (listening && captureId == captureGeneration.get()) {
                                mutable.update { it.copy(speechActive = false) }; socket?.send(VoiceProtocol.speech(false))
                                if (!awaiting && !playing) timing.listen(SystemClock.elapsedRealtime())
                            } }
                        }
                        if (elapsed - lastMeter >= 50) {
                            lastMeter = elapsed; mutable.update { it.copy(level = (energy / 6000).toFloat().coerceIn(0f, 1f)) }
                        }
                        // Full vocabulary preserves questions containing "arrêté"; only a short final command stops.
                        if (stopListening && pilotSession && modelStore.installed()) {
                            try {
                                val loaded = model ?: Model(modelStore.directory.absolutePath)
                                model = loaded
                                val recognizer = stopRecognizer ?: Recognizer(loaded, SAMPLE_RATE.toFloat())
                                stopRecognizer = recognizer
                                if (recognizer.acceptWaveForm(captureBytes, captureBytes.size)) {
                                    val recognized = JSONObject(recognizer.result).optString("text")
                                    if (StopWords.matchesCode(recognized, settings.pilotStopCode)) {
                                        val captureId = generation
                                        main.post { if (pilotSession && captureId == captureGeneration.get()) stopCode("mot_code_local", recognized) }
                                    }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { stopListening = false; stopRecognizer?.close(); stopRecognizer = null }
                        } else if (stopRecognizer != null) { stopRecognizer?.close(); stopRecognizer = null }
                    } else if (keepWake && wakeRecognizer != null) {
                        wakeBuffer.feed(bytes, locked())
                        // Partials never wake (Tom, 2026-10-05: private phrases sent without "Dis Sirius").
                        val final = wakeRecognizer.acceptWaveForm(bytes, bytes.size)
                        if (final) {
                            val result = wakeRecognizer.result
                            val threshold = settings.wakeSensitivity.minConf
                            val span = WakeFilter.acceptedSpan(result, threshold)
                            val debugWake = Log.isLoggable(WAKE_LOG_TAG, Log.DEBUG)
                            if (debugWake) {
                                Log.d(WAKE_LOG_TAG, "threshold=$threshold accepted=${span != null}; ${WakeFilter.scores(result)}")
                            }
                            val prefix = span?.let { wakeBuffer.wake(it) }
                            if (prefix != null) {
                                wakeAcceptedAt = SystemClock.elapsedRealtime()
                                Log.i("SiriusAnim", "reveil detection")
                                wakeRecognizer.reset(); wakeBuffer.discard()
                                main.post { if (!listening && keepWake) {
                                    pendingWakeAudio = prefix
                                    beginConversation(true)
                                    if (listening) onWake?.invoke() else pendingWakeAudio = null
                                } }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Throwable) { main.post {
                endConversation(); keepWake = false; mutable.update { it.copy(wakeRunning = false) }; WakeService.stop(context)
                report("Micro ou modèle indisponible. Ouvre Sirius pour réessayer.")
            } } finally {
                runCatching { recorder?.stop() }; recorder?.release(); aec?.release(); ns?.release(); outputLevel?.close()
                stopRecognizer?.close(); wakeRecognizer?.close(); model?.close()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }
}
