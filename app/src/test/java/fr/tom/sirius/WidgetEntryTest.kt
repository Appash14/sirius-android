package fr.tom.sirius

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetEntryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test fun bothWidgetSizesLaunchTheExplicitVoiceEntryWithAnImmutableIntent() {
        for (wide in listOf(false, true)) {
            val view = SiriusWidget.views(context, wide).apply(context, FrameLayout(context))
            assertTrue(view.findViewById<View>(R.id.widget_talk).performClick())
            val launched = shadowOf(context).nextStartedActivity
            assertEquals(VoiceEntry.TALK, launched.action)
            assertEquals(VoiceEntryActivity::class.java.name, launched.component!!.className)
            assertTrue(launched.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            val pending = PendingIntent.getActivity(context, 61, VoiceEntry.intent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            assertNotNull(pending)
            assertTrue(pending.isImmutable)
            if (wide) {
                val texts = (view as android.view.ViewGroup).getChildAt(1) as TextView
                assertEquals("Parler à Sirius", texts.text.toString())
            }
        }
    }

    @Test fun launcherShortcutsDeclareTalkAndMorningBriefingOnTheSafeActivity() {
        val found = mutableMapOf<String, String>()
        val labels = mutableListOf<String>()
        context.resources.getXml(R.xml.shortcuts).use { xml ->
            var currentId = ""
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                when (xml.name) {
                    "shortcut" -> {
                        currentId = xml.getAttributeValue(androidNamespace, "shortcutId")
                        labels += context.getString(xml.getAttributeResourceValue(androidNamespace, "shortcutShortLabel", 0))
                    }
                    "intent" -> {
                        assertEquals(context.packageName, xml.getAttributeValue(androidNamespace, "targetPackage"))
                        assertEquals(VoiceEntryActivity::class.java.name, xml.getAttributeValue(androidNamespace, "targetClass"))
                        found[currentId] = xml.getAttributeValue(androidNamespace, "action")
                    }
                }
            }
        }
        assertEquals(mapOf("parler" to VoiceEntry.TALK, "briefing" to VoiceEntry.BRIEFING), found)
        assertEquals(listOf("Parler", "Briefing du matin"), labels)
        val activity = context.packageManager.getActivityInfo(android.content.ComponentName(context, MainActivity::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertEquals(R.xml.shortcuts, activity.metaData.getInt("android.app.shortcuts"))
    }

    @Test fun widgetStartsAtOneCellAndCanResizeHorizontallyWithoutPeriodicNetworkWork() {
        context.resources.getXml(R.xml.widget_sirius_info).use { xml ->
            while (xml.next() != XmlPullParser.START_TAG) { }
            assertEquals(1, xml.getAttributeIntValue(androidNamespace, "targetCellWidth", -1))
            assertEquals(1, xml.getAttributeIntValue(androidNamespace, "targetCellHeight", -1))
            assertEquals(1, xml.getAttributeIntValue(androidNamespace, "resizeMode", -1))
            assertEquals(0, xml.getAttributeIntValue(androidNamespace, "updatePeriodMillis", -1))
            assertEquals(1, xml.getAttributeIntValue(androidNamespace, "widgetCategory", -1))
        }
    }
}
