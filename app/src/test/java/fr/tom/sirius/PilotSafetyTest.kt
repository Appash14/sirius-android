package fr.tom.sirius

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Incident of 2026-10-05: "défiler bas" moved Instagram's horizontal pager and opened Tom's messages. */
class PilotSafetyTest {
    private val screen = ScrollCandidate(className = "android.widget.FrameLayout", width = 1440, height = 3120, forward = true, backward = true)
    private fun refused(reason: String, operation: () -> Unit) {
        try { operation(); fail("Accepté") } catch (failure: ActionFailure) { assertEquals(reason, failure.reason) }
    }

    @Test fun instagramPagerIsNeverScrolledAndTheVerticalFeedIsChosen() {
        val pager = screen
        val stories = ScrollCandidate(className = "androidx.recyclerview.widget.RecyclerView", width = 1440, height = 300, forward = true, rows = 1, columns = 12)
        val feed = ScrollCandidate(className = "androidx.recyclerview.widget.RecyclerView", width = 1440, height = 2600, forward = true, backward = true, rows = 40, columns = 1)
        assertEquals(ScrollDecision.Action(2, ScrollMethod.FORWARD), ScrollChoice.choose(listOf(pager, stories, feed), "bas"))
        assertEquals(ScrollDecision.Action(2, ScrollMethod.BACKWARD), ScrollChoice.choose(listOf(pager, stories, feed), "haut"))
        // Without the feed, only a vertical swipe remains: forward/backward of an unknown container is never used.
        assertEquals(ScrollDecision.Gesture("haut"), ScrollChoice.choose(listOf(pager, stories), "bas"))
        assertEquals(ScrollDecision.Gesture("bas"), ScrollChoice.choose(listOf(pager), "haut"))
        assertEquals(ScrollDecision.Gesture("haut"), ScrollChoice.choose(emptyList(), "bas"))
    }

    @Test fun horizontalSignalsAreRejectedWhateverTheShape() {
        val horizontal = listOf(
            ScrollCandidate(className = "androidx.viewpager.widget.ViewPager", width = 1440, height = 3120, forward = true),
            ScrollCandidate(className = "androidx.viewpager2.widget.ViewPager2", width = 1440, height = 3120, pageLeft = true, pageRight = true, forward = true),
            ScrollCandidate(className = "android.view.View", width = 1440, height = 3120, left = true, right = true, forward = true, backward = true),
            ScrollCandidate(className = "android.widget.HorizontalScrollView", width = 1440, height = 3120, forward = true),
            ScrollCandidate(className = "android.view.ViewGroup", width = 1440, height = 400, forward = true),
            ScrollCandidate(className = "androidx.recyclerview.widget.RecyclerView", width = 1440, height = 3120, forward = true, rows = 1, columns = 8),
        )
        for (candidate in horizontal) {
            assertEquals(candidate.toString(), ScrollAxis.HORIZONTAL, ScrollChoice.axis(candidate))
            assertEquals(candidate.toString(), ScrollDecision.Gesture("haut"), ScrollChoice.choose(listOf(candidate), "bas"))
        }
        // A 2D container with a sideways action never gets forward/backward, only its explicit vertical actions.
        val map = ScrollCandidate(width = 1440, height = 3120, up = true, right = true, forward = true)
        assertEquals(ScrollDecision.Gesture("haut"), ScrollChoice.choose(listOf(map), "bas"))
        assertEquals(ScrollDecision.Action(0, ScrollMethod.SCROLL_UP), ScrollChoice.choose(listOf(map), "haut"))
    }

    @Test fun explicitVerticalActionsCollectionsAndClassesAreUsed() {
        val reels = ScrollCandidate(className = "androidx.viewpager2.widget.ViewPager2", width = 1440, height = 3120, pageDown = true, pageUp = true, forward = true)
        assertEquals(ScrollAxis.VERTICAL, ScrollChoice.axis(reels))
        assertEquals(ScrollDecision.Action(0, ScrollMethod.PAGE_DOWN), ScrollChoice.choose(listOf(reels), "bas"))
        val compose = ScrollCandidate(width = 1440, height = 2000, down = true, up = true, forward = true, backward = true)
        assertEquals(ScrollDecision.Action(0, ScrollMethod.SCROLL_DOWN), ScrollChoice.choose(listOf(compose), "bas"))
        assertEquals(ScrollDecision.Action(0, ScrollMethod.SCROLL_UP), ScrollChoice.choose(listOf(compose), "haut"))
        val list = ScrollCandidate(className = "android.widget.ScrollView", width = 1440, height = 900, forward = true)
        assertEquals(ScrollDecision.Action(0, ScrollMethod.FORWARD), ScrollChoice.choose(listOf(list), "bas"))
        // At the top of a list there is no backward: swipe instead of acting on anything else.
        assertEquals(ScrollDecision.Gesture("bas"), ScrollChoice.choose(listOf(list), "haut"))
        // A grid (rows and columns) or an unknown tall container is only vertical by its shape: swipe, never forward.
        assertEquals(ScrollAxis.SHAPE_VERTICAL, ScrollChoice.axis(screen))
        val grid = ScrollCandidate(className = "androidx.recyclerview.widget.RecyclerView", width = 1440, height = 2600, forward = true, rows = 10, columns = 3)
        assertEquals(ScrollDecision.Gesture("haut"), ScrollChoice.choose(listOf(grid), "bas"))
        assertEquals(ScrollAxis.UNKNOWN, ScrollChoice.axis(ScrollCandidate(width = 1000, height = 1000, forward = true)))
        refused("arguments_invalides") { ScrollChoice.choose(listOf(compose), "gauche") }
    }

    @Test fun focusedThenLargestVerticalContainerWins() {
        val small = ScrollCandidate(width = 1440, height = 600, down = true)
        val large = ScrollCandidate(width = 1440, height = 2400, down = true)
        val focused = ScrollCandidate(width = 1440, height = 300, down = true, containsFocus = true)
        assertEquals(ScrollDecision.Action(1, ScrollMethod.SCROLL_DOWN), ScrollChoice.choose(listOf(small, large), "bas"))
        assertEquals(ScrollDecision.Action(2, ScrollMethod.SCROLL_DOWN), ScrollChoice.choose(listOf(small, large, focused), "bas"))
    }

    @Test fun swipesAreCenteredVerticalAndAwayFromSystemEdges() {
        val up = Swipes.plan("haut", 1440, 3120)
        assertEquals(SwipePlan(720f, 3120 * .72f, 720f, 3120 * .28f, 350), up)
        assertTrue(up.startY > up.endY)
        val down = Swipes.plan("bas", 1440, 3120)
        assertTrue(down.startY < down.endY); assertEquals(720f, down.startX); assertEquals(350, down.durationMs)
        val left = Swipes.plan("gauche", 1440, 3120)
        assertEquals(1560f, left.startY); assertTrue(left.startX > left.endX); assertTrue(left.endX >= 1440 * .2f)
        assertTrue(Swipes.plan("droite", 1440, 3120).let { it.startX < it.endX && it.startY == it.endY })
        refused("arguments_invalides") { Swipes.plan("diagonale", 1440, 3120) }
        refused("ecran_indisponible") { Swipes.plan("haut", 0, 3120) }
    }

    @Test fun glisserIsStrictAndSidewaysNeedsAnExplicitRequest() {
        fun parse(args: JSONObject) = PhoneAction.parse(JSONObject().put("id", "glisser-test").put("action", "glisser").put("args", args))
        for (direction in listOf("haut", "bas")) assertEquals("glisser", parse(JSONObject().put("direction", direction)).action)
        for (direction in listOf("gauche", "droite")) assertEquals("glisser", parse(JSONObject().put("direction", direction).put("explicite", true)).action)
        val invalid = listOf(JSONObject().put("direction", "gauche"), JSONObject().put("direction", "droite").put("explicite", false),
            JSONObject().put("direction", "gauche").put("explicite", "true"), JSONObject().put("direction", "haut").put("explicite", true),
            JSONObject().put("direction", "diagonale"), JSONObject().put("direction", "bas").put("x", 3), JSONObject())
        for (args in invalid) refused("arguments_invalides") { parse(args) }
        // defiler stays vertical only.
        refused("arguments_invalides") { PhoneAction.parse(JSONObject().put("id", "d").put("action", "defiler").put("args", JSONObject().put("direction", "gauche"))) }
    }

    @Test fun stopWordsAreLocalAndConservative() {
        for (text in listOf("stop", "Stop !", "arrête", "ARRÊTEZ", "stop stop", "stoppe")) assertTrue(text, StopWords.isStopUtterance(text))
        for (text in listOf("[unk] stop", "arrête de scroller", "", "[unk]", "stop stop stop stop")) assertFalse(text, StopWords.isStopUtterance(text))
        for (text in listOf("Stop.", "Arrête !", "Arrête-toi", "stoppe")) assertTrue(text, StopWords.isStopRequest(text))
        for (text in listOf("N'arrête pas", "ouvre Instagram", "Le bus s'arrête où exactement ce soir ?", "Pourquoi ça s'est arrêté d'un coup le pilotage ?", "Pourquoi tu arrêtes ?", "arrêté", "arreter", "Sirius pourquoi arrête", "")) assertFalse(text, StopWords.isStopRequest(text))
    }

    @Test fun stopWinsOverTheSwitchButNotOverTheKeyguard() {
        refused("verrouille") { ActionPolicy.guard(enabled = false, locked = true, stopped = true) }
        refused(STOPPED_BY_TOM) { ActionPolicy.guard(enabled = false, locked = false, stopped = true) }
        refused(STOPPED_BY_TOM) { ActionPolicy.guard(enabled = true, locked = false, stopped = true) }
        refused("pilotage_desactive") { ActionPolicy.guard(enabled = false, locked = false) }
        assertEquals("arrêté par toi", reasonLabel(STOPPED_BY_TOM))
        val announcer = PilotAnnouncer()
        val stopped = JSONObject(announcer.frame(false, false, stopped = true)!!)
        assertEquals(STOPPED_BY_TOM, stopped.getString("raison")); assertFalse(stopped.getBoolean("enabled"))
        assertNull(announcer.frame(false, false, stopped = true))
        assertFalse(JSONObject(announcer.frame(false, false)!!).has("raison"))
        assertFalse(JSONObject(VoiceProtocol.pilotage(true, false, stopped = true)).has("raison"))
    }
}
