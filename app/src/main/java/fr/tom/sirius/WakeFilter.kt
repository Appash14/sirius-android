package fr.tom.sirius

import org.json.JSONArray
import org.json.JSONObject

/** Minimum confidence for both words. Moyenne by default after the S25 feedback of 2026-10-05. */
enum class WakeSensitivity(val label: String, val minConf: Double) {
    FAIBLE("Faible", .97), MOYENNE("Moyenne", .92), ELEVEE("Élevée", .85)
}

/**
 * Wake word gate. Only a FINAL Vosk result with per-word scores (setWords) can wake Sirius:
 * "dis" then "sirius", both above the confidence floor, with plausible durations. Decoy words in the
 * grammar give near sounds somewhere else to land than "dis sirius".
 */
object WakeFilter {
    data class Span(val start: Double, val end: Double)
    val DECOYS = listOf("dis", "sirius", "service", "sérieux", "série")
    fun grammar(): String = JSONArray(listOf("dis sirius") + DECOYS + "[unk]").toString()

    fun accepts(result: String, minConf: Double): Boolean = acceptedSpan(result, minConf) != null

    fun acceptedSpan(result: String, minConf: Double): Span? {
        if (!minConf.isFinite() || minConf !in .85..1.0) return null
        val words = runCatching { JSONObject(result).optJSONArray("result") }.getOrNull() ?: return null
        for (i in 0 until words.length() - 1) {
            val first = words.optJSONObject(i) ?: continue
            val second = words.optJSONObject(i + 1) ?: continue
            if (first.optString("word") != "dis" || second.optString("word") != "sirius") continue
            val dis = first.optDouble("end", 0.0) - first.optDouble("start", 0.0)
            val sirius = second.optDouble("end", 0.0) - second.optDouble("start", 0.0)
            val gap = second.optDouble("start", 0.0) - first.optDouble("end", 0.0)
            if (first.optDouble("conf", 0.0) >= minConf && second.optDouble("conf", 0.0) >= minConf &&
                first.optDouble("start", -1.0) >= 0.0 &&
                dis in .09..0.8 && sirius in .25..1.3 && gap in -.05..0.45)
                return Span(first.getDouble("start"), second.getDouble("end"))
        }
        return null
    }

    /** Debug output contains only grammar words and numeric scores, never an arbitrary transcription. */
    fun scores(result: String): String {
        val words = runCatching { JSONObject(result).optJSONArray("result") }.getOrNull() ?: return "no word scores"
        return (0 until words.length()).mapNotNull { i -> words.optJSONObject(i)?.let { word ->
            val name = word.optString("word")
            if (name in DECOYS) "$name conf=${word.optDouble("conf")} duration=${word.optDouble("end") - word.optDouble("start")}" else null
        } }.joinToString("; ")
    }
}
