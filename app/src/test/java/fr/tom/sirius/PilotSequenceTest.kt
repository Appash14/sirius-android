package fr.tom.sirius

import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SiriusApp::class)
class PilotSequenceTest {
    @Suppress("DEPRECATION")
    private fun field() = AccessibilityNodeInfo.obtain().apply {
        packageName = "com.example.messages"; isVisibleToUser = true; isEnabled = true
        isEditable = true; isFocused = true; setBoundsInScreen(Rect(0, 100, 500, 300))
    }
    private fun step(action: String, args: JSONObject = JSONObject()) = JSONObject().put("action", action).put("args", args)
    private fun sequence(vararg steps: JSONObject) = JSONObject().put("id", "seq-test").put("action", "sequence")
        .put("args", JSONObject().put("actions", JSONArray(steps.toList())))
    private fun write(text: String) = step("ecrire", JSONObject().put("texte", text))

    @Test fun runsLocallyAndReturnsOneFreshBoundedScreenWithoutCapture() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        app.pilot.setEnabled(true); app.pilot.automaticScreen = true
        val service = Robolectric.setupService(SiriusAccessibilityService::class.java)
        app.pilot.attachAccessibility(service)
        val field = field(); shadowOf(service).setRootInActiveWindow(field)
        val written = mutableListOf<String>()
        shadowOf(field).setOnPerformActionListener { action, args ->
            if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) false else {
                val value = args!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString()
                written += value; field.text = value; true
            }
        }
        val results = mutableListOf<JSONObject>()
        app.pilot.receive(sequence(write("premier"), write("second"))) { results += it; true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(listOf("premier", "second"), written); assertEquals(1, results.size)
        val result = results.single(); assertTrue(result.getBoolean("ok"))
        val data = result.getJSONObject("data"); assertEquals(2, data.getJSONArray("etapes").length())
        assertEquals("second", data.getJSONObject("ecran").getJSONArray("noeuds").getJSONObject(0).getString("texte"))
        assertEquals("com.example.messages", data.getJSONObject("premier_plan").getString("paquet"))
        assertFalse(data.has("capture")); assertFalse(data.has("image"))
        app.pilot.endSession()
    }

    @Test fun firstErrorSkipsTheRestAndReturnsItsIndexAndCurrentScreen() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        app.pilot.setEnabled(true); app.pilot.automaticScreen = true
        val service = Robolectric.setupService(SiriusAccessibilityService::class.java)
        app.pilot.attachAccessibility(service)
        val field = field(); shadowOf(service).setRootInActiveWindow(field)
        var writes = 0
        shadowOf(field).setOnPerformActionListener { _, _ -> writes++; false }
        var result: JSONObject? = null
        app.pilot.receive(sequence(write("premier"), write("interdit"))) { result = it; true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(1, writes); assertFalse(result!!.getBoolean("ok"))
        val data = result!!.getJSONObject("data")
        assertEquals("action_echouee", data.getString("raison")); assertEquals(0, data.getInt("index_erreur"))
        assertTrue(data.has("ecran")); app.pilot.endSession()
    }

    @Test fun stopAndLockBetweenStepsPreventEveryRemainingGestureAndPrivateScreen() {
        for (stop in listOf(true, false)) {
            val app = ApplicationProvider.getApplicationContext<SiriusApp>()
            app.pilot.setEnabled(true); app.pilot.automaticScreen = true
            val service = Robolectric.setupService(SiriusAccessibilityService::class.java)
            app.pilot.attachAccessibility(service)
            val field = field(); shadowOf(service).setRootInActiveWindow(field)
            var writes = 0
            shadowOf(field).setOnPerformActionListener { _, _ -> writes++; true }
            var result: JSONObject? = null
            val command = sequence(write("premier"), write("interdit")).put("id", "seq-$stop")
            app.pilot.receive(command) { result = it; true }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(1, writes)
            if (stop) app.pilot.emergencyStop("pastille") else
                shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertEquals(1, writes)
            assertEquals(if (stop) STOPPED_BY_TOM else "verrouille", result!!.getJSONObject("data").getString("raison"))
            assertFalse(result!!.getJSONObject("data").has("ecran"))
            shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
            app.pilot.endSession()
        }
    }

    @Test fun rejectsNestedSequencesAndUnsafeArgumentsBeforeTheFirstStep() {
        val invalid = listOf(sequence(), sequence(step("sequence", JSONObject().put("actions", JSONArray()))),
            sequence(step("glisser", JSONObject().put("direction", "gauche"))),
            sequence(step("lancer_intent", JSONObject().put("uri", "file:///private"))),
            sequence(step("shell", JSONObject())))
        for (json in invalid) {
            try { PhoneAction.parse(json); fail(json.toString()) } catch (_: ActionFailure) { }
        }
        val tooMany = JSONArray(); repeat(21) { tooMany.put(step("ecran")) }
        try { PhoneAction.parse(sequence(step("ecran")).put("args", JSONObject().put("actions", tooMany))); fail() }
        catch (e: ActionFailure) { assertEquals("arguments_invalides", e.reason) }
    }
}
