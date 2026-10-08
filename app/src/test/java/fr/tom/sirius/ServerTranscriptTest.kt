package fr.tom.sirius

import org.junit.Assert.*
import org.junit.Test

class ServerTranscriptTest {
    private val phrase = ChatMessage("phrase-1", "tom", "Voilà ma vraie phrase.")
    @Test fun liveFinalFillsTomBubbleAndLeavesSiriusCaptionAlone() {
        val state = VoiceState(conversation = true, phase = VoicePhase.SPEAKING,
            subtitleRole = "sirius", subtitle = "La réponse")
        val result = withServerMessage(state, phrase, live = true)
        assertEquals(phrase.text, result.tomTranscript)
        assertEquals("La réponse", result.subtitle)
        assertEquals("sirius", result.subtitleRole)
    }
    @Test fun historyAndSiriusMessagesCannotFillTomBubble() {
        val state = VoiceState(conversation = true)
        assertEquals("", withServerMessage(state, phrase, live = false).tomTranscript)
        assertEquals("", withServerMessage(state, phrase.copy(role = "sirius"), live = true).tomTranscript)
    }
    @Test fun lateTranscriptionCannotReplaceNewSpeechOrDormantHud() {
        assertEquals("", withServerMessage(VoiceState(conversation = true, speechActive = true), phrase, true).tomTranscript)
        assertEquals("", withServerMessage(VoiceState(), phrase, true).tomTranscript)
    }
    @Test fun liveWordsShowWhileTomSpeaksAndOnlyForTheCurrentUtterance() {
        val speaking = VoiceState(conversation = true, speechActive = true)
        assertEquals("Alors Sirius", withLiveTranscript(speaking, "Alors Sirius", current = true).tomTranscript)
        assertEquals("", withLiveTranscript(speaking, "Ancienne phrase", current = false).tomTranscript)
        assertEquals("", withLiveTranscript(speaking, "  ", current = true).tomTranscript)
        assertEquals("", withLiveTranscript(VoiceState(), "Alors", current = true).tomTranscript)
        // The final message then replaces the live words once Tom stopped speaking.
        val live = withLiveTranscript(speaking, "Alors Sirius regarde", true).copy(speechActive = false)
        assertEquals(phrase.text, withServerMessage(live, phrase, live = true).tomTranscript)
    }
    @Test fun serverCorrectionUpdatesHistoryWithoutDuplicatingTheMessage() {
        val state = withServerMessage(VoiceState(conversation = true), phrase, true)
        val corrected = phrase.copy(text = "Phrase finale corrigée")
        val result = withServerMessage(state, corrected, true)
        assertEquals(listOf(corrected), result.messages)
        assertEquals(corrected.text, result.tomTranscript)
    }
}
