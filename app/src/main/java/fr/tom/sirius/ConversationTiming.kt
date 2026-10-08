package fr.tom.sirius

/** Monotonic deadlines. Server events and network retries never extend the first response wait. */
class ConversationTiming {
    @Volatile private var idleUntil = 0L
    @Volatile private var replyUntil = 0L
    fun listen(now: Long) { replyUntil = 0; idleUntil = now + FOLLOW_UP_MS }
    fun speechStarted(preserveReply: Boolean = false) { idleUntil = 0; if (!preserveReply) replyUntil = 0 }
    fun waitForReply(now: Long) { idleUntil = 0; if (replyUntil == 0L) replyUntil = now + RESPONSE_MS }
    fun connectionLost(now: Long) { if (replyUntil == 0L) waitForReply(now) }
    fun audioStarted() { idleUntil = 0; replyUntil = 0 }
    fun stop() { idleUntil = 0; replyUntil = 0 }
    fun sleepDue(now: Long, speaking: Boolean, playing: Boolean, awaiting: Boolean) =
        idleUntil != 0L && now >= idleUntil && !speaking && !playing && !awaiting
    fun responseDue(now: Long, playing: Boolean) = replyUntil != 0L && now >= replyUntil && !playing
    companion object {
        const val FOLLOW_UP_MS = 12_000L
        // Tom, 2.4: a long reflection must never close the conversation; 5 min stays a clear upper bound.
        const val RESPONSE_MS = 300_000L
        fun reconnectDelay(attempt: Int, outageMs: Long = Long.MAX_VALUE) =
            if (outageMs < 30_000) 1_000L else (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)
    }
}
