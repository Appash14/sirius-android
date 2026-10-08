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
class PilotTouchTest {
    @Test fun coordinatesDispatchWithoutApprovalButNeverOnABankOrLockedScreen() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>()
        app.pilot.setEnabled(true)
        val service = Robolectric.setupService(SiriusAccessibilityService::class.java)
        app.pilot.attachAccessibility(service)
        val shadow = shadowOf(service)
        @Suppress("DEPRECATION")
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.instagram.android"; className = "android.widget.FrameLayout"
            isVisibleToUser = true; isEnabled = true
            setBoundsInScreen(Rect(0, 0, 1080, 2340))
        }
        shadow.setRootInActiveWindow(root)
        fun command(id: String) = JSONObject().put("id", id).put("action", "toucher")
            .put("args", JSONObject().put("x", 100).put("y", 200))
        val results = mutableListOf<JSONObject>()
        app.pilot.receive(command("ordinary")) { results += it; true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        val gesture = shadow.gesturesDispatched.single()
        assertTrue("No confirmation should be pending", results.isEmpty())
        gesture.callback().onCompleted(gesture.description())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertTrue(results.single().getBoolean("ok"))
        root.packageName = "com.revolut.revolut"
        app.pilot.receive(command("bank")) { results += it; true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals("application_bloquee", results.last().getJSONObject("data").getString("raison"))
        root.packageName = "com.instagram.android"
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        app.pilot.receive(command("locked")) { results += it; true }
        assertEquals("verrouille", results.last().getJSONObject("data").getString("raison"))
        assertEquals(1, shadow.gesturesDispatched.size)
        app.pilot.endSession()
    }
}
