package fr.tom.sirius

/** In-memory conversation only. The stable id survives partial and final transcription. */
data class HudMessage(val id: String, val role: String, val text: String = "", val final: Boolean = false,
                      val serverId: String? = null, val replyTo: String? = null,
                      val audioParts: Map<String, String> = emptyMap(), val visibleWhenLocked: Boolean = false)

/** A fresh lock starts a new visibility window without removing any shared conversation message. */
internal fun withLockState(state: VoiceState, locked: Boolean): VoiceState =
    if (state.locked == locked) state else state.copy(locked = locked,
        hudMessages = state.hudMessages.map { it.copy(visibleWhenLocked = false) })

internal fun visibleHudMessages(state: VoiceState): List<HudMessage> =
    if (state.locked) state.hudMessages.filter { it.visibleWhenLocked } else state.hudMessages

internal fun clearHud(state: VoiceState) = state.copy(hudMessages = emptyList(), hudTomId = null)

internal fun beginHudUtterance(state: VoiceState, id: String): VoiceState {
    if (!state.conversation || state.hudMessages.any { it.id == id }) return state
    return state.copy(hudTomId = id, hudMessages = state.hudMessages + HudMessage(id, "tom", visibleWhenLocked = state.locked))
}

/** A late partial cannot change either the final bubble or the current transcript. */
internal fun withLiveTranscript(state: VoiceState, text: String, current: Boolean): VoiceState {
    if (!current || !state.conversation || text.isBlank()) return state
    val target = state.hudMessages.firstOrNull { it.id == state.hudTomId }
    if (target?.final == true) return state
    return state.copy(tomTranscript = text.take(4000), hudMessages = state.hudMessages.map {
        if (it.id == state.hudTomId) it.copy(text = text.take(4000)) else it
    })
}

internal fun protocolId(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "null" }

private fun replyTarget(state: VoiceState, id: String, replyId: String?, replyTo: String?): HudMessage? =
    state.hudMessages.firstOrNull { it.role == "sirius" && (it.id == id || it.serverId == id) }
        ?: replyId?.let { key -> state.hudMessages.firstOrNull { it.role == "sirius" && it.id == key } }
        ?: replyTo?.let { key -> state.hudMessages.firstOrNull { it.role == "sirius" && it.replyTo == key } }

/** History reconciles known bubbles, without inserting old turns into the current session. */
internal fun withServerMessage(state: VoiceState, message: ChatMessage, live: Boolean,
                               enonce: String? = null, replyId: String? = null, replyTo: String? = null,
                               reconcile: Boolean = true, questionEnonce: String? = null): VoiceState {
    val seen = state.messages.any { it.id == message.id }
    val history = (if (seen) state.messages.map { if (it.id == message.id) message else it }
        else state.messages + message).takeLast(200)
    if (!state.conversation || !reconcile) return state.copy(messages = history)
    val utterance = protocolId(enonce)
    val target = if (message.role == "sirius") replyTarget(state, message.id, protocolId(replyId), protocolId(replyTo))
        else questionEnonce?.let { key -> state.hudMessages.firstOrNull { it.role == "tom" && it.id == key } }
            ?: state.hudMessages.firstOrNull { it.role == "tom" && (it.serverId == message.id || it.id == message.id) }
            ?: utterance?.let { key -> state.hudMessages.firstOrNull { it.role == "tom" && it.id == key } }
            // Finals without enonce can arrive before speechActive becomes false.
            // Replace the last open bubble even when its partial text is completely wrong.
            ?: if (utterance == null && live && !seen) state.hudMessages.lastOrNull {
                it.role == "tom" && !it.final
            } else null
    if (target == null && (!live || seen)) return state.copy(messages = history)
    val bubble = if (target == null) HudMessage(
        if (message.role == "tom") utterance ?: message.id else protocolId(replyId) ?: message.id,
        message.role, message.text, true, message.id, protocolId(replyTo), visibleWhenLocked = state.locked)
    else target.copy(text = message.text, final = true, serverId = message.id,
        replyTo = protocolId(replyTo) ?: target.replyTo)
    val bubbles = if (target == null) state.hudMessages + bubble else state.hudMessages.filterNot { it.id != target.id && it.role == message.role && it.serverId == message.id }.map {
        if (it.id == target.id) bubble else it
    }
    return state.copy(messages = history, hudMessages = bubbles,
        tomTranscript = if (message.role == "tom" && !state.speechActive &&
            (state.hudTomId == null || target == null || target.id == state.hudTomId)) message.text else state.tomTranscript)
}

/** Text is visible on receipt, even if audio focus or playback later fails. Segment keys ignore replays. */
internal fun withHudAudio(state: VoiceState, id: String, text: String, part: String,
                         replyTo: String? = null): VoiceState {
    if (!state.conversation || text.isBlank()) return state
    val target = replyTarget(state, id, null, protocolId(replyTo))
    val parts = target?.audioParts.orEmpty()
    if (parts.containsKey(part)) return state
    val updatedParts = parts + (part to text)
    val bubble = target?.copy(
        text = if (target.final) target.text else updatedParts.values.joinToString(" "),
        replyTo = protocolId(replyTo) ?: target.replyTo, audioParts = updatedParts)
        ?: HudMessage(id, "sirius", text, replyTo = protocolId(replyTo), audioParts = updatedParts, visibleWhenLocked = state.locked)
    return state.copy(hudMessages = if (target == null) state.hudMessages + bubble else state.hudMessages.map {
        if (it.id == target.id) bubble else it
    })
}

internal fun finishHudReply(state: VoiceState) = state.copy(hudMessages = state.hudMessages.map {
    if (it.role == "sirius" && !it.final) it.copy(final = true) else it
})
