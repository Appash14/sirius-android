package fr.tom.sirius

import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
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
class PilotWritingAndScreenTest {
    @Suppress("DEPRECATION")
    private fun node(text: String = "") = AccessibilityNodeInfo.obtain().apply {
        packageName = "com.example.messages"; isVisibleToUser = true; isEnabled = true
        this.text = text; setBoundsInScreen(Rect(20, 100, 500, 250))
    }
    @Test fun oversizedTelegramTreeUsesTheExistingWireSchemaAndStaysUnder64KiB() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        app.pilot.setEnabled(true)
        val service = Robolectric.setupService(SiriusAccessibilityService::class.java)
        app.pilot.attachAccessibility(service)
        val root = node()
        repeat(450) { index ->
            val row = node("é😀\\\"\n".repeat(400)).apply {
                isClickable = true; viewIdResourceName = "id-$index" + "x".repeat(2000)
                contentDescription = "texte".repeat(400)
            }
            shadowOf(root).addChild(row)
        }
        shadowOf(service).setRootInActiveWindow(root)
        val output = service.screen()
        assertTrue(output.getBoolean("tronque"))
        assertTrue(output.toString().toByteArray(Charsets.UTF_8).size < 64 * 1024)
        val rows = output.getJSONArray("noeuds")
        assertTrue(rows.length() in 1..400)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            assertFalse(row.has("texte_descendants"))
            for (key in listOf("texte", "description", "id")) assertTrue(row.getString(key).length <= 1000)
        }
    }
    @Test fun screenCollectsCellDescendantsAndKeepsOpaqueClickableRectangles() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        app.pilot.setEnabled(true)
        val service = Robolectric.setupService(SiriusAccessibilityService::class.java)
        app.pilot.attachAccessibility(service)
        val root = node().apply { isClickable = true }
        val name = node("Conversation exemple").apply { isVisibleToUser = false }
        val preview = node().apply { contentDescription = "Aperçu accessible" }
        val secret = node("Mot de passe privé").apply { isPassword = true }
        val opaque = node().apply { isClickable = true }
        shadowOf(root).addChild(name); shadowOf(root).addChild(preview)
        shadowOf(secret).addChild(node("Descendant privé"))
        shadowOf(root).addChild(secret); shadowOf(root).addChild(opaque)
        shadowOf(service).setRootInActiveWindow(root)
        val output = service.screen()
        val rows = output.getJSONArray("noeuds")
        val label = rows.getJSONObject(0).getString("description")
        assertTrue(label.contains("Conversation exemple")); assertTrue(label.contains("Aperçu accessible"))
        assertFalse(output.toString().contains("Mot de passe privé"))
        assertFalse(output.toString().contains("Descendant privé"))
        assertTrue((0 until rows.length()).any {
            val row = rows.getJSONObject(it)
            row.getBoolean("cliquable") && row.getString("description").isBlank() && row.getJSONArray("rectangle").length() == 4
        })
    }
    @Test fun writingDoesNotWaitForConfirmationAndStillChecksBankingAndLockscreen() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        app.pilot.setEnabled(true)
        val service = Robolectric.setupService(SiriusAccessibilityService::class.java)
        app.pilot.attachAccessibility(service)
        val field = node().apply { isEditable = true; isFocused = true }
        shadowOf(field).setOnPerformActionListener { action, arguments ->
            action == AccessibilityNodeInfo.ACTION_SET_TEXT && arguments?.getCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString() == "Texte exemple"
        }
        shadowOf(service).setRootInActiveWindow(field)
        val results = mutableListOf<JSONObject>()
        fun write(id: String) = app.pilot.receive(JSONObject().put("id", id).put("action", "ecrire")
            .put("args", JSONObject().put("texte", "Texte exemple"))) { results += it; true }
        write("write")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        // Exercise ACTION_SET_TEXT itself without the notification permission or a confirmation tap.
        assertEquals(1, results.size)
        assertTrue(results.single().getBoolean("ok"))
        field.packageName = "com.revolut.revolut"
        write("bank")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals("application_bloquee", results.last().getJSONObject("data").getString("raison"))
        field.packageName = "com.example.messages"
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        write("locked")
        assertEquals("verrouille", results.last().getJSONObject("data").getString("raison"))
        app.pilot.endSession()
    }
}
