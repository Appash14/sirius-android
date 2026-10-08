package fr.tom.sirius

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.os.Handler
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
class PilotOpeningTest {
    private val pkg = "org.telegram.messenger"
    private val home = "com.sec.android.app.launcher"
    private val app get() = ApplicationProvider.getApplicationContext<SiriusApp>()
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Suppress("DEPRECATION")
    private fun root(pkg: String) = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg; isVisibleToUser = true; isEnabled = true
        setBoundsInScreen(Rect(0, 0, 1080, 2340))
    }
    private fun setup(): SiriusAccessibilityService {
        app.pilot.setEnabled(true); app.pilot.automaticScreen = true
        val info = ApplicationInfo().apply {
            packageName = pkg; nonLocalizedLabel = "Telegram"; enabled = true; flags = ApplicationInfo.FLAG_INSTALLED
        }
        val activity = ActivityInfo().apply {
            packageName = pkg; name = "$pkg.MainActivity"; applicationInfo = info; exported = true; enabled = true
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; applicationInfo = info; activities = arrayOf(activity)
        })
        val resolve = ResolveInfo().apply { activityInfo = activity; nonLocalizedLabel = "Telegram" }
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), resolve)
        return Robolectric.setupService(SiriusAccessibilityService::class.java).also {
            app.pilot.attachAccessibility(it); shadowOf(it).setRootInActiveWindow(root(home))
        }
    }
    private fun command() = JSONObject().put("id", "open-test").put("action", "ouvrir")
        .put("args", JSONObject().put("paquet", pkg))

    @Test fun delayedAssistantHideMustFinishBeforeLaunchingSoItCannotRestoreHomeOverTheApp() {
        val service = setup()
        app.engine.assistantVisible = true
        var hides = 0
        app.engine.onDeviceAction = {
            hides++
            // Simulate Binder/OEM delay: onHide restores the launcher after 600 ms, beyond the old 150 ms.
            Handler(Looper.getMainLooper()).postDelayed({
                shadowOf(service).setRootInActiveWindow(root(home))
                app.engine.assistantVisible = false; app.engine.onDeviceAction = null
            }, 600)
        }
        val results = mutableListOf<JSONObject>()
        app.pilot.receive(command()) { results += it; true }
        idle(200)
        assertNull("launch before onHide would be covered by the restored launcher", shadowOf(app).nextStartedActivity)
        idle(500)
        assertFalse(app.engine.assistantVisible)
        assertNull("focus must settle after onHide", shadowOf(app).nextStartedActivity)
        idle(100)
        assertEquals(pkg, shadowOf(app).nextStartedActivity.component!!.packageName)
        shadowOf(service).setRootInActiveWindow(root(pkg))
        idle(700)
        assertEquals(1, hides); assertEquals(1, results.size)
        assertTrue(results.single().getBoolean("ok"))
        assertEquals(pkg, results.single().getJSONObject("data").getJSONObject("premier_plan").getString("paquet"))
        assertEquals(pkg, service.foregroundPackage())
        assertTrue("opening must never send HOME or BACK", shadowOf(service).globalActionsPerformed.isEmpty())
        app.engine.endConversation(); app.pilot.endSession()
        idle(500)
        assertEquals("ending the session must leave the target in front", pkg, service.foregroundPackage())
        assertNull("no second launch", shadowOf(app).nextStartedActivity)
    }

    @Test fun appThatOpensThenReturnsHomeCannotReportSuccess() {
        val service = setup()
        var result: JSONObject? = null
        app.pilot.receive(command()) { result = it; true }
        idle(200)
        assertEquals(pkg, shadowOf(app).nextStartedActivity.component!!.packageName)
        shadowOf(service).setRootInActiveWindow(root(pkg))
        idle(150)
        assertNull("do not confirm on the first transient target window", result)
        shadowOf(service).setRootInActiveWindow(root(home))
        idle(600)
        assertFalse(result!!.getBoolean("ok"))
        assertEquals("ouverture_non_confirmee", result!!.getJSONObject("data").getString("raison"))
        app.pilot.endSession()
    }

    @Test fun absentForegroundAndUnknownForegroundCannotReportSuccess() {
        for (unknown in listOf(false, true)) {
            val service = setup()
            if (unknown) shadowOf(service).setRootInActiveWindow(null)
            var result: JSONObject? = null
            app.pilot.receive(command().put("id", "absent-$unknown")) { result = it; true }
            idle(3_000)
            assertFalse(result!!.getBoolean("ok"))
            assertEquals("ouverture_non_confirmee", result!!.getJSONObject("data").getString("raison"))
            app.pilot.endSession()
        }
    }

    @Test fun unconfirmedOpeningInSequencePreventsTheFollowingHomeAction() {
        val service = setup()
        val steps = JSONArray().put(JSONObject().put("action", "ouvrir").put("args", JSONObject().put("paquet", pkg)))
            .put(JSONObject().put("action", "global").put("args", JSONObject().put("commande", "accueil")))
        var result: JSONObject? = null
        app.pilot.receive(JSONObject().put("id", "sequence-open").put("action", "sequence")
            .put("args", JSONObject().put("actions", steps))) { result = it; true }
        idle(3_000)
        assertFalse(result!!.getBoolean("ok"))
        assertEquals(0, result!!.getJSONObject("data").getInt("index_erreur"))
        assertEquals("ouverture_non_confirmee", result!!.getJSONObject("data").getString("raison"))
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        app.pilot.endSession()
    }

    @Test fun hideTimeoutDoesNotLaunchAndStopWhileWaitingWins() {
        for (stop in listOf(false, true)) {
            setup(); app.engine.assistantVisible = true; app.engine.onDeviceAction = { }
            var result: JSONObject? = null
            app.pilot.receive(command().put("id", "waiting-$stop")) { result = it; true }
            idle(200)
            assertNull(shadowOf(app).nextStartedActivity)
            if (stop) app.pilot.emergencyStop("pastille")
            idle(2_500)
            assertFalse(result!!.getBoolean("ok"))
            assertEquals(if (stop) STOPPED_BY_TOM else "assistant_masquage_non_confirme",
                result!!.getJSONObject("data").getString("raison"))
            assertNull(shadowOf(app).nextStartedActivity)
            app.engine.assistantVisible = false; app.engine.onDeviceAction = null; app.pilot.endSession()
        }
    }
}
