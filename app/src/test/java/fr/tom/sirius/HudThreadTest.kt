package fr.tom.sirius

import org.junit.Assert.*
import org.junit.Test

class HudThreadTest {
    @Test fun lateQuestionBindingDoesNotMoveItAfterTheAnswerInHistory() {
        val question = ChatMessage("q", "tom", "Question")
        val answer = ChatMessage("r", "sirius", "Réponse")
        var state = beginHudUtterance(VoiceState(conversation = true), "phone")
        state = withServerMessage(state, question, true, "e1")
        state = withServerMessage(state, answer, true, replyTo = "q")
        state = withServerMessage(state, question, true, questionEnonce = "phone")
        assertEquals(listOf(question, answer), state.messages)
        assertEquals(listOf("phone", "r"), state.hudMessages.map { it.id })
    }

    @Test fun questionReturnedByPostMergesLegacyFinalWithWrongUtteranceIdEvenAfterItArrived() {
        var state = beginHudUtterance(VoiceState(conversation = true), "phone-1")
        state = withLiveTranscript(state, "Ma phrase en cours", true)
        val final = ChatMessage("voix-1", "tom", "Ma phrase finale")
        state = withServerMessage(state, final, true, "e1")
        assertEquals(2, state.hudMessages.size) // Same failure as serveur.py.avant-doublon.
        state = withServerMessage(state, final, true, "e1", questionEnonce = "phone-1")
        assertEquals(listOf(HudMessage("phone-1", "tom", final.text, true, "voix-1")), state.hudMessages)
        assertEquals(1, state.messages.size)
    }

    @Test fun partialAndFinalKeepOneBubbleAndTheSameId() {
        var state = beginHudUtterance(VoiceState(conversation = true, speechActive = true), "utterance-1")
        state = withLiveTranscript(state, "Dis Sirius", true)
        state = withLiveTranscript(state, "Dis Sirius regarde", true)
        assertEquals(listOf("utterance-1"), state.hudMessages.map { it.id })
        assertFalse(state.hudMessages.single().final)
        state = withServerMessage(state.copy(speechActive = false), ChatMessage("server-1", "tom", "Regarde ici."), true, "utterance-1")
        assertEquals(HudMessage("utterance-1", "tom", "Regarde ici.", true, "server-1"), state.hudMessages.single())
        assertEquals(state, withLiveTranscript(state, "Ancienne phrase", false))
        val late = withLiveTranscript(state, "partiel en retard", true)
        assertEquals(state, late)
    }

    @Test fun turnsStayInOrderAndAudioSegmentsExtendOneReply() {
        var state = beginHudUtterance(VoiceState(conversation = true), "u1")
        state = withServerMessage(state, ChatMessage("m1", "tom", "Première question"), true, "u1")
        state = withHudAudio(state, "r1", "Un début de réponse.", "0")
        state = withHudAudio(state, "r1", "Un début de réponse.", "0")
        state = withHudAudio(state, "r1", "Et la suite.", "1")
        state = beginHudUtterance(state, "u2").copy(speechActive = true)
        state = withLiveTranscript(state, "Autre question", true)
        assertEquals(listOf("u1", "r1", "u2"), state.hudMessages.map { it.id })
        assertEquals("Un début de réponse. Et la suite.", state.hudMessages[1].text)
        assertFalse(state.hudMessages[1].final) // Tom can add a question before this reply has ended.
        state = withHudAudio(state, "r1", "La fin arrive après la nouvelle question.", "2")
        assertEquals("Un début de réponse. Et la suite. La fin arrive après la nouvelle question.", state.hudMessages[1].text)
        assertEquals("Première question", state.hudMessages[0].text)
        assertEquals(state, beginHudUtterance(state, "u2"))
    }

    @Test fun lateFinalUpdatesItsOwnUtteranceAndCorrectionsDoNotMoveIt() {
        var state = beginHudUtterance(VoiceState(conversation = true), "old")
        state = withLiveTranscript(state, "Ancien", true)
        state = beginHudUtterance(state, "new").copy(speechActive = true)
        state = withLiveTranscript(state, "Nouveau", true)
        state = withServerMessage(state, ChatMessage("m1", "tom", "Ancienne phrase finale"), true, "old")
        state = withServerMessage(state, ChatMessage("m1", "tom", "Ancienne phrase corrigée"), true, "old")
        assertEquals(listOf("old", "new"), state.hudMessages.map { it.id })
        assertEquals("Ancienne phrase corrigée", state.hudMessages.first().text)
        assertEquals("Nouveau", state.hudMessages.last().text)
        assertEquals("Nouveau", state.tomTranscript)
    }


    @Test fun finalBeforeLastPartialWithoutUtteranceIdKeepsTheLiveBubble() {
        var state = beginHudUtterance(VoiceState(conversation = true, speechActive = true), "u1")
        state = withLiveTranscript(state, "Ouais, l'apparition", true)
        state = withServerMessage(state.copy(speechActive = false),
            ChatMessage("m1", "tom", "Ouais, l'apparition est tough."), true)
        val final = state
        state = withLiveTranscript(state, "Ouais, l'apparition est", true)
        assertEquals(final, state)
        assertEquals("u1", state.hudMessages.single().id)
        assertEquals("Ouais, l'apparition est tough.", state.tomTranscript)
    }

    @Test fun finalWhileSpeechIsStillActiveAndBeforeAnyPartialUsesUtteranceId() {
        val initial = beginHudUtterance(VoiceState(conversation = true, speechActive = true), "u1")
        val final = withServerMessage(initial, ChatMessage("m1", "tom", "Phrase finale"), true, "u1")
        assertEquals("u1", final.hudMessages.single().id)
        assertEquals(final, withLiveTranscript(final, "Phrase", true))
        val receivedFirst = withServerMessage(VoiceState(conversation = true),
            ChatMessage("m1", "tom", "Phrase finale"), true, "u1")
        assertEquals(receivedFirst, beginHudUtterance(receivedFirst, "u1"))
    }

    @Test fun fallbackFinalUsesTheLastOpenBubbleEvenDuringNewSpeech() {
        var state = beginHudUtterance(VoiceState(conversation = true), "old")
        state = withLiveTranscript(state, "Ancien", true)
        state = beginHudUtterance(state, "new").copy(speechActive = true)
        state = withLiveTranscript(state, "Nouveau", true)
        state = withServerMessage(state, ChatMessage("m1", "tom", "Nouvelle phrase finale"), true, "null")
        assertEquals(listOf("old", "new"), state.hudMessages.map { it.id })
        assertFalse(state.hudMessages.first().final)
        assertTrue(state.hudMessages.last().final)
        assertEquals("Nouveau", state.tomTranscript)
    }

    @Test fun reconnectHistoryReconcilesKnownTurnsWithoutAddingOldBubbles() {
        var state = beginHudUtterance(VoiceState(conversation = true), "u1")
        state = withLiveTranscript(state, "Phrase", true)
        val message = ChatMessage("m1", "tom", "Phrase finale")
        state = withServerMessage(state, message, false, "u1")
        state = withServerMessage(state, ChatMessage("old", "tom", "Historique ancien"), false)
        state = withServerMessage(state, message, false, "u1")
        state = withServerMessage(state, message, true, "u1")
        assertEquals(HudMessage("u1", "tom", "Phrase finale", true, "m1"), state.hudMessages.single())
        assertEquals(state, withLiveTranscript(state, "Phrase tardive", true))
        state = withServerMessage(state, ChatMessage("old", "tom", "Historique ancien"), true)
        assertEquals(1, state.hudMessages.size)
    }

    @Test fun tomVideoWrongPartialThenFinalAndRepeatedFinalReplaceTheSameBubble() {
        for (utterance in listOf(null, "", "null", "video-1")) {
            var state = beginHudUtterance(VoiceState(conversation = true, speechActive = true), "video-1")
            state = withLiveTranscript(state, "Vas-y, je je diffère.", true)
            val final = ChatMessage("video-message-1", "tom",
                "Vas-y, je je vais te faire un petit enregistrement d'écran, tu vas voir.")
            // The final arrives while the microphone still reports active speech, then is replayed.
            state = withServerMessage(state, final, true, utterance)
            state = withLiveTranscript(state, "Vas-y, je je vais te faire un petit", true)
            state = withServerMessage(state.copy(speechActive = false), final, true, utterance)
            assertEquals(listOf(HudMessage("video-1", "tom", final.text, true, final.id)), state.hudMessages)
            assertEquals(final.text, state.tomTranscript)
            state = withHudAudio(state, "video-reply-1",
                "Ah, d'accord, tu parles de ma vitesse de parole, je parle trop lentement. " +
                    "Je l'accélère juste après le travail en cours. Envoie l'enregistrement, je regarde aussi le bug.", "0")
            assertEquals(listOf("video-1", "video-reply-1"), state.hudMessages.map { it.id })
        }
    }

    @Test fun siriusAudioAndMessageShareOneBubbleInEitherOrderAndHistory() {
        for (messageFirst in listOf(false, true)) {
            var state = VoiceState(conversation = true)
            val message = ChatMessage("message-r1", "sirius", "Une réponse. Et la suite.")
            if (messageFirst) state = withServerMessage(state, message, true, replyTo = "q1")
            state = withHudAudio(state, "audio-r1", "Une réponse.", "0", "q1")
            state = withHudAudio(state, "audio-r1", "Et la suite.", "1", "q1")
            val beforeReplay = state
            state = withHudAudio(state, "audio-r1", "Une réponse.", "0", "q1")
            assertEquals(beforeReplay, state)
            if (!messageFirst) state = withServerMessage(state, message, true, replyTo = "q1")
            state = withServerMessage(state, message, false, replyTo = "q1")
            assertEquals("Une réponse. Et la suite.", state.hudMessages.single().text)
            assertEquals("sirius", state.hudMessages.single().role)
            assertTrue(state.hudMessages.single().final)
            state = withHudAudio(state, "audio-r2", "Une réponse.", "0", "q2")
            assertEquals(2, state.hudMessages.size)
        }
    }

    @Test fun standaloneSiriusMessageAndUnindexedAudioReplayAppearOnce() {
        val message = ChatMessage("r1", "sirius", "Réponse sans audio")
        var state = withServerMessage(VoiceState(conversation = true), message, true)
        state = withServerMessage(state, message, true)
        assertEquals(listOf(HudMessage("r1", "sirius", message.text, true, "r1")), state.hudMessages)
        state = withHudAudio(state, "r2", "Réponse audio", "Réponse audio")
        val first = state
        state = withHudAudio(state, "r2", "Réponse audio", "Réponse audio")
        assertEquals(first, state)
        assertEquals(2, state.hudMessages.size)
    }

    @Test fun historyNeverEntersHudAndClosingClearsOnlyTheCurrentThread() {
        var state = beginHudUtterance(VoiceState(conversation = true), "u1")
        state = withServerMessage(state, ChatMessage("history", "tom", "Ancien historique"), false)
        assertEquals("", state.hudMessages.single().text)
        state = withHudAudio(state, "r1", "Réponse", "0")
        val closed = clearHud(state).copy(conversation = false)
        assertTrue(closed.hudMessages.isEmpty())
        assertNull(closed.hudTomId)
        assertEquals(state.messages, closed.messages)
        assertEquals(closed, withHudAudio(closed, "r2", "Tardif", "0"))
        assertTrue(withServerMessage(closed, ChatMessage("late", "tom", "Tardif"), true).hudMessages.isEmpty())
    }
}
