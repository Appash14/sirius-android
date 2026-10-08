package fr.tom.sirius

import org.junit.Assert.*
import org.junit.Test

class ConversationTimingTest {
    @Test fun followUpStartsAfterPlaybackAndStopsExactlyAtTwelveSeconds() {
        val timing = ConversationTiming()
        timing.listen(0); timing.speechStarted(); timing.waitForReply(1000)
        assertFalse(timing.sleepDue(20000, false, false, true))
        timing.audioStarted()
        assertFalse(timing.sleepDue(120000, false, true, true))
        timing.listen(150000) // End of real playback, not reply_end arriving while still playing.
        assertFalse(timing.sleepDue(161999, false, false, false))
        assertTrue(timing.sleepDue(162000, false, false, false))
    }
    @Test fun speechInWindowOwnsTurnUntilSilenceEndsTheUtterance() {
        val timing = ConversationTiming(); timing.listen(100)
        timing.speechStarted()
        assertFalse(timing.sleepDue(20000, true, false, false))
        timing.waitForReply(20000)
        assertFalse(timing.sleepDue(40000, false, false, true))
    }
    @Test fun responseTimeoutAlsoBoundsWaitingWithoutConnectedSocket() {
        val timing = ConversationTiming(); timing.waitForReply(200)
        repeat(10) { ConversationTiming.reconnectDelay(it) }
        val due = 200 + ConversationTiming.RESPONSE_MS
        assertFalse(timing.responseDue(due - 1, false))
        assertTrue(timing.responseDue(due, false))
        assertFalse(timing.responseDue(due, true))
        timing.listen(due)
        assertFalse(timing.responseDue(due + 200000, false))
        assertTrue(timing.sleepDue(due + ConversationTiming.FOLLOW_UP_MS, false, false, false))
    }
    @Test fun shutdownClearsAllDeadlinesAndBackoffIsBounded() {
        val timing = ConversationTiming(); timing.waitForReply(100); timing.stop()
        assertFalse(timing.responseDue(200000, false))
        assertFalse(timing.sleepDue(200000, false, false, false))
        assertEquals(1000L, ConversationTiming.reconnectDelay(0))
        assertEquals(30000L, ConversationTiming.reconnectDelay(30))
    }
    @Test fun disconnectDuringPlaybackStartsABoundWithoutExtendingAnExistingWait() {
        val timing = ConversationTiming(); timing.waitForReply(100)
        timing.connectionLost(10000)
        assertTrue(timing.responseDue(100 + ConversationTiming.RESPONSE_MS, false))
        timing.audioStarted(); timing.connectionLost(100000)
        val due = 100000 + ConversationTiming.RESPONSE_MS
        assertFalse(timing.responseDue(due - 1, false))
        assertTrue(timing.responseDue(due, false))
        timing.connectionLost(180000)
        assertTrue(timing.responseDue(due, false))
    }
    @Test fun followUpSpeechAndAnotherQuestionNeverClearOrExtendAnAwaitedReply() {
        val timing = ConversationTiming()
        timing.waitForReply(100)
        timing.speechStarted(preserveReply = true)
        timing.waitForReply(10000)
        timing.connectionLost(20000)
        assertFalse(timing.responseDue(100 + ConversationTiming.RESPONSE_MS - 1, false))
        assertTrue(timing.responseDue(100 + ConversationTiming.RESPONSE_MS, false))
        assertFalse(timing.sleepDue(200000, false, false, true))
    }
}
