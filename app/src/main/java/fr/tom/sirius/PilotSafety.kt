package fr.tom.sirius

import java.util.Locale

/** Axis of a scrollable container, decided only from what accessibility reports about it. */
enum class ScrollAxis { VERTICAL, HORIZONTAL, SHAPE_VERTICAL, UNKNOWN }

/** Accessibility facts about one scrollable node. Page flags are the ACTION_PAGE_* variants. */
data class ScrollCandidate(
    val className: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
    val pageUp: Boolean = false,
    val pageDown: Boolean = false,
    val pageLeft: Boolean = false,
    val pageRight: Boolean = false,
    val forward: Boolean = false,
    val backward: Boolean = false,
    val rows: Int = -1,
    val columns: Int = -1,
    val containsFocus: Boolean = false,
)

enum class ScrollMethod { SCROLL_DOWN, SCROLL_UP, PAGE_DOWN, PAGE_UP, FORWARD, BACKWARD }

sealed class ScrollDecision {
    /** Accessibility action on candidates[index], only for a container known to be vertical. */
    data class Action(val index: Int, val method: ScrollMethod) : ScrollDecision()
    /** Real swipe at the center of the screen. finger is the direction the finger moves. */
    data class Gesture(val finger: String) : ScrollDecision()
}

/**
 * First real test (2026-10-05): ACTION_SCROLL_FORWARD on the first scrollable node moved Instagram's
 * horizontal pager and opened Tom's private messages. Forward/backward have no axis, so they are used only
 * when the container is vertical by its directional actions, its collection info or its class. Anything
 * else gets a vertical swipe, which a horizontal pager ignores.
 */
object ScrollChoice {
    private val horizontalNames = listOf("pager", "horizontal", "carousel", "gallery", "tablayout", "tabrow")
    private val verticalNames = setOf("android.widget.listview", "android.widget.expandablelistview", "android.widget.gridview",
        "android.widget.scrollview", "androidx.core.widget.nestedscrollview")

    private fun sideways(c: ScrollCandidate) = c.left || c.right || c.pageLeft || c.pageRight

    fun axis(c: ScrollCandidate): ScrollAxis {
        if (c.up || c.down || c.pageUp || c.pageDown) return ScrollAxis.VERTICAL
        if (sideways(c)) return ScrollAxis.HORIZONTAL
        if (c.rows > 1 && c.columns <= 1) return ScrollAxis.VERTICAL
        if (c.columns > 1 && c.rows <= 1) return ScrollAxis.HORIZONTAL
        val name = c.className.lowercase(Locale.ROOT)
        if (horizontalNames.any { name.contains(it) }) return ScrollAxis.HORIZONTAL
        if (name in verticalNames) return ScrollAxis.VERTICAL
        if (c.width > 0 && c.height > 0) {
            // A shape is only a hint: the decision below swipes vertically instead of trusting forward/backward.
            if (c.width * 5 >= c.height * 6) return ScrollAxis.HORIZONTAL
            if (c.height * 5 >= c.width * 6) return ScrollAxis.SHAPE_VERTICAL
        }
        return ScrollAxis.UNKNOWN
    }

    private fun method(c: ScrollCandidate, down: Boolean): ScrollMethod? {
        if (down && c.down) return ScrollMethod.SCROLL_DOWN
        if (!down && c.up) return ScrollMethod.SCROLL_UP
        if (down && c.pageDown) return ScrollMethod.PAGE_DOWN
        if (!down && c.pageUp) return ScrollMethod.PAGE_UP
        if (sideways(c)) return null
        return if (down && c.forward) ScrollMethod.FORWARD else if (!down && c.backward) ScrollMethod.BACKWARD else null
    }

    /** direction: "bas" shows what is below (finger moves up), "haut" what is above. */
    fun choose(candidates: List<ScrollCandidate>, direction: String): ScrollDecision {
        requireAction(direction == "haut" || direction == "bas", "arguments_invalides")
        val down = direction == "bas"
        val best = candidates.indices
            .filter { axis(candidates[it]) == ScrollAxis.VERTICAL }
            .mapNotNull { index -> method(candidates[index], down)?.let { index to it } }
            .maxWithOrNull(compareBy<Pair<Int, ScrollMethod>> { candidates[it.first].containsFocus }
                .thenBy { candidates[it.first].width.toLong() * candidates[it.first].height })
        return if (best != null) ScrollDecision.Action(best.first, best.second) else ScrollDecision.Gesture(if (down) "haut" else "bas")
    }
}

data class SwipePlan(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val durationMs: Long)

/** Swipes stay away from the system gesture edges (home bar, back zones, status bar). */
object Swipes {
    const val VERTICAL_MS = 350L
    const val SIDEWAYS_MS = 300L
    fun plan(finger: String, width: Int, height: Int): SwipePlan {
        requireAction(width > 0 && height > 0, "ecran_indisponible")
        val w = width.toFloat(); val h = height.toFloat()
        return when (finger) {
            "haut" -> SwipePlan(w / 2, h * .72f, w / 2, h * .28f, VERTICAL_MS)
            "bas" -> SwipePlan(w / 2, h * .28f, w / 2, h * .72f, VERTICAL_MS)
            "gauche" -> SwipePlan(w * .8f, h / 2, w * .2f, h / 2, SIDEWAYS_MS)
            "droite" -> SwipePlan(w * .2f, h / 2, w * .8f, h / 2, SIDEWAYS_MS)
            else -> throw ActionFailure("arguments_invalides")
        }
    }
}

/** Local stop words. Both checks run on the phone: Sirius and the server are never needed to stop. */
object StopWords {
    const val DEFAULT_CODE = "Sirius halte"
    // Kept for the archived engine compiled by verifier_regressions.sh; current recognition uses the full vocabulary.
    const val GRAMMAR = "[\"stop\", \"stoppe\", \"arrête\", \"arrêtez\", \"[unk]\"]"
    private val commands = setOf("stop", "stoppe", "arrête", "arrêtez", "arrête toi", "arrêtez vous", "arrete", "arrete toi")
    private fun command(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("[!.,;:?]+$"), "").replace('-', ' ').trim().replace(Regex("\\s+"), " ")
    fun isStopUtterance(text: String): Boolean = command(text) in commands || command(text) == "stop stop"
    fun isStopRequest(text: String): Boolean = isStopUtterance(text)
    fun validCode(text: String): Boolean = command(text).split(' ').let {
        it.size in 2..5 && it.all { word -> word.length >= 2 && word.all(Char::isLetter) }
    }
    fun matchesCode(text: String, code: String): Boolean = validCode(code) && command(text) == command(code)
}

/** Telegram exposes a dialog's name followed by a sentence or a status, not a separate text node. */
internal object TelegramLabels {
    /** Placeholders Telegram writes into the node text of an empty composer (not reported as hint text). */
    private val placeholders = setOf("message", "messages", "ecrire un message", "write a message", "envoyer un message",
        "send a message", "broadcast", "silent broadcast", "diffusion", "diffusion silencieuse", "comment", "commentaire",
        "commenter", "reply", "repondre", "leave a comment", "laisser un commentaire")

    /**
     * Text really typed in the composer, empty when the field only shows its hint. Telegram (2.7 bug) puts the hint
     * "Message" in the node text with isShowingHintText false; a cursor after the first character means typed text.
     */
    fun typedText(text: CharSequence?, hint: CharSequence?, showingHint: Boolean, selectionStart: Int): String {
        val value = text?.toString().orEmpty()
        if (showingHint || value.isBlank()) return ""
        fun label(raw: String) = ActionPolicy.normalize(raw.trim()).trimEnd('.', '\u2026', ' ')
        val shown = label(value)
        if (!hint.isNullOrBlank() && shown == label(hint.toString())) return ""
        return if (shown in placeholders && selectionStart <= 0) "" else value
    }

    fun conversation(label: String, name: String): Boolean {
        val value = label.trim()
        return value == name || value.startsWith("$name. ") || value.startsWith("$name, ") ||
            value.startsWith("$name\n") || value == "$name." ||
            listOf("Bot. ", "Robot. ", "Groupe. ", "Group. ", "Canal. ", "Channel. ").any { prefix ->
                value.removePrefix(prefix).let { it != value && (it == name || it.startsWith("$name. ") || it.startsWith("$name, ")) }
            }
    }
}
