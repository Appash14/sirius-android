package fr.tom.sirius

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
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
class TelegramMessageTest {
    private val pkg = "org.telegram.messenger"
    @Suppress("DEPRECATION")
    private fun node(text: String = "", top: Int = 90) = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg; this.text = text; isVisibleToUser = true; isEnabled = true
        setBoundsInScreen(Rect(0, top, 200, top + 20))
    }
    private fun setup(app: SiriusApp): SiriusAccessibilityService {
        app.pilot.setEnabled(true); app.pilot.automaticScreen = true
        val info = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = "Telegram"; enabled = true; flags = ApplicationInfo.FLAG_INSTALLED }
        val activity = ActivityInfo().apply { packageName = pkg; name = "$pkg.MainActivity"; applicationInfo = info; exported = true; enabled = true }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = info; activities = arrayOf(activity) })
        val resolve = ResolveInfo().apply { activityInfo = activity; nonLocalizedLabel = "Telegram" }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), resolve)
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), resolve)
        return Robolectric.setupService(SiriusAccessibilityService::class.java).also { app.pilot.attachAccessibility(it) }
    }
    private fun command() = JSONObject().put("id", "telegram-test").put("action", "envoyer_message")
        .put("args", JSONObject().put("appli", "telegram").put("conversation", "Sirius").put("texte", "Salut Tom"))

    @Test fun listAndSearchBothSendOnceToTheVerifiedConversationWithoutRoundTrips() {
        for (search in listOf(false, true)) {
            val app = ApplicationProvider.getApplicationContext<SiriusApp>()
            val service = setup(app)
            val root = node(); val row = node("Sirius. Dernier message").apply { isClickable = true }
            val composer = node().apply { isEditable = true; isFocused = true }
            val chat = node(); shadowOf(chat).addChild(node("Sirius", top = 0)); shadowOf(chat).addChild(composer)
            val send = node("Envoyer", top = 140).apply { isClickable = true }; shadowOf(chat).addChild(send)
            var sends = 0
            shadowOf(composer).setOnPerformActionListener { action, args ->
                if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) false else {
                    composer.text = args!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE); true
                }
            }
            shadowOf(send).setOnPerformActionListener { action, _ ->
                if (action != AccessibilityNodeInfo.ACTION_CLICK) false else { sends++; composer.text = ""; true }
            }
            shadowOf(row).setOnPerformActionListener { action, _ ->
                if (action != AccessibilityNodeInfo.ACTION_CLICK) false else { shadowOf(service).setRootInActiveWindow(chat); true }
            }
            if (search) {
                val button = node("Rechercher", top = 0).apply { isClickable = true }
                shadowOf(root).addChild(button)
                val results = node(); val field = node().apply { isEditable = true; hintText = "Rechercher" }
                shadowOf(results).addChild(field)
                shadowOf(button).setOnPerformActionListener { _, _ -> shadowOf(service).setRootInActiveWindow(results); true }
                shadowOf(field).setOnPerformActionListener { action, args ->
                    if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) false else {
                        field.text = args!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                        shadowOf(results).addChild(row); true
                    }
                }
            } else {
                shadowOf(root).addChild(row)
                shadowOf(root).addChild(node("Rechercher", top = 0).apply { isClickable = true })
            }
            shadowOf(service).setRootInActiveWindow(root)
            var result: JSONObject? = null
            app.pilot.receive(command().put("id", "telegram-$search")) { result = it; true }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNotNull(result); assertTrue(result.toString(), result!!.getBoolean("ok"))
            assertEquals(1, sends); assertEquals("envoye", result!!.getJSONObject("data").getString("statut"))
            assertTrue(result!!.getJSONObject("data").has("ecran"))
            app.pilot.endSession()
        }
    }

    @Test fun preservesAnExistingDraftAndNeverClicksSend() {
        val app = ApplicationProvider.getApplicationContext<SiriusApp>(); val service = setup(app)
        val root = node(); shadowOf(root).addChild(node("Sirius", top = 0))
        val composer = node("Brouillon de Tom").apply { isEditable = true }
        shadowOf(root).addChild(composer); shadowOf(service).setRootInActiveWindow(root)
        var result: JSONObject? = null
        app.pilot.receive(command()) { result = it; true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals("brouillon_present", result!!.getJSONObject("data").getString("raison"))
        assertEquals("Brouillon de Tom", composer.text.toString()); app.pilot.endSession()
    }

    @Test fun hintReportedAsTextIsNotADraftAndTheMessageIsSentOnce() {
        // 2.7 bug: Telegram reports its hint "Message" as the text of an empty composer, without isShowingHintText.
        assertEquals("", TelegramLabels.typedText("Message", null, false, 0))
        assertEquals("", TelegramLabels.typedText("Message", null, false, -1))
        assertEquals("", TelegramLabels.typedText("Diffusion", null, false, 0))
        assertEquals("", TelegramLabels.typedText("Salut", "Salut", false, 5))
        assertEquals("", TelegramLabels.typedText("Brouillon", "Message", true, 0))
        assertEquals("Message", TelegramLabels.typedText("Message", null, false, 7))
        assertEquals("Brouillon de Tom", TelegramLabels.typedText("Brouillon de Tom", "Message", false, 0))
        val app = ApplicationProvider.getApplicationContext<SiriusApp>(); val service = setup(app)
        val chat = node(); shadowOf(chat).addChild(node("Sirius", top = 0))
        val composer = node("Message").apply { isEditable = true; isFocused = true }
        shadowOf(chat).addChild(composer)
        val send = node("Envoyer", top = 140).apply { isClickable = true }; shadowOf(chat).addChild(send)
        var sends = 0
        shadowOf(composer).setOnPerformActionListener { action, args ->
            if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) false else {
                composer.text = args!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE); true
            }
        }
        shadowOf(send).setOnPerformActionListener { action, _ ->
            if (action != AccessibilityNodeInfo.ACTION_CLICK) false else { sends++; composer.text = "Message"; true }
        }
        shadowOf(service).setRootInActiveWindow(chat)
        var result: JSONObject? = null
        app.pilot.receive(command()) { result = it; true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertNotNull(result); assertTrue(result.toString(), result!!.getBoolean("ok"))
        assertEquals(1, sends); assertEquals("envoye", result!!.getJSONObject("data").getString("statut"))
        app.pilot.endSession()
    }

    @Test fun recipientLabelsAreExactAndMessageArgumentsAreStrict() {
        for (label in listOf("Sirius", "Sirius. Bonjour", "Sirius, @sirius", "Bot. Sirius. Bonjour"))
            assertTrue(label, TelegramLabels.conversation(label, "Sirius"))
        for (label in listOf("Sirius bis", "Sirius officiel. Bonjour", "Tom. Sirius", "bonjour Sirius", "Sirius2"))
            assertFalse(label, TelegramLabels.conversation(label, "Sirius"))
        for (args in listOf(JSONObject().put("appli", "sms").put("conversation", "Sirius").put("texte", "hello"),
            JSONObject().put("appli", "telegram").put("conversation", "").put("texte", "hello"),
            JSONObject().put("appli", "telegram").put("conversation", "Sirius").put("texte", "x".repeat(4001)))) {
            try { PhoneAction.parse(command().put("args", args)); fail() }
            catch (e: ActionFailure) { assertEquals("arguments_invalides", e.reason) }
        }
    }
}
