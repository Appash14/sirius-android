package fr.tom.sirius

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeFilterTest {
    private fun result(vararg words: Array<Any>) = """{"result":[""" + words.joinToString(",") {
        """{"word":"${it[0]}","conf":${it[1]},"start":${it[2]},"end":${it[3]}}"""
    } + """],"text":"x"}"""

    @Test fun clearWakeIsAccepted() {
        val clear = result(arrayOf("dis", 1.0, 0.30, 0.52), arrayOf("sirius", 0.99, 0.55, 1.10))
        assertTrue(WakeFilter.accepts(clear, WakeSensitivity.FAIBLE.minConf))
    }

    @Test fun mediumAcceptsClearWordsBelowTheLowSensitivityFloor() {
        val clear = result(arrayOf("dis", 0.96, 0.30, 0.52), arrayOf("sirius", 0.94, 0.55, 1.10))
        assertTrue(WakeFilter.accepts(clear, WakeSensitivity.MOYENNE.minConf))
        assertFalse(WakeFilter.accepts(clear, WakeSensitivity.FAIBLE.minConf))
        val uncertain = result(arrayOf("dis", 0.99, 0.30, 0.52), arrayOf("sirius", 0.91, 0.55, 1.10))
        assertFalse(WakeFilter.accepts(uncertain, WakeSensitivity.MOYENNE.minConf))
    }
    @Test fun diagnosticsContainOnlyKnownWordsAndScores() {
        val private = result(arrayOf("nettoyer le PC", 1.0, 0.0, 0.3), arrayOf("service", 0.94, 0.4, 1.0))
        val log = WakeFilter.scores(private)
        assertFalse(log.contains("nettoyer"))
        assertTrue(log.contains("service conf=0.94"))
        assertFalse(WakeFilter.accepts(private, WakeSensitivity.MOYENNE.minConf))
    }

    @Test fun doubtfulWakeIsRefused() {
        val doubtful = result(arrayOf("dis", 0.91, 0.30, 0.52), arrayOf("sirius", 0.88, 0.55, 1.10))
        assertFalse(WakeFilter.accepts(doubtful, WakeSensitivity.FAIBLE.minConf))
        assertTrue(WakeFilter.accepts(doubtful, WakeSensitivity.ELEVEE.minConf))
    }

    @Test fun implausibleTimingIsRefused() {
        val tooShort = result(arrayOf("dis", 1.0, 0.30, 0.33), arrayOf("sirius", 1.0, 0.34, 0.45))
        val farApart = result(arrayOf("dis", 1.0, 0.30, 0.52), arrayOf("sirius", 1.0, 1.60, 2.10))
        assertFalse(WakeFilter.accepts(tooShort, WakeSensitivity.ELEVEE.minConf))
        assertFalse(WakeFilter.accepts(farApart, WakeSensitivity.ELEVEE.minConf))
    }

    @Test fun otherWordsOrPartialsNeverWake() {
        assertFalse(WakeFilter.accepts(result(arrayOf("sirius", 1.0, 0.3, 0.9)), .5))
        assertFalse(WakeFilter.accepts(result(arrayOf("dis", 1.0, 0.3, 0.5), arrayOf("service", 1.0, 0.55, 1.0)), .5))
        assertFalse(WakeFilter.accepts("""{"partial":"dis sirius"}""", .5))
        assertFalse(WakeFilter.accepts("""{"text":"dis sirius"}""", .5))
        assertFalse(WakeFilter.accepts("pas du json", .5))
    }

    @Test fun grammarKeepsTheWakePhraseDecoysAndUnknown() {
        val grammar = JSONArray(WakeFilter.grammar())
        assertEquals("dis sirius", grammar.getString(0))
        assertEquals("[unk]", grammar.getString(grammar.length() - 1))
        assertTrue((0 until grammar.length()).map { grammar.getString(it) }.containsAll(listOf("service", "sérieux", "série")))
    }
}
