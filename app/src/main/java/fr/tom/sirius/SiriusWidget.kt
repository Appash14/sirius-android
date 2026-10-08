package fr.tom.sirius

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

class SiriusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        manager.updateAppWidget(id, RemoteViews(mapOf(
            SizeF(56f, 56f) to views(context, false),
            SizeF(110f, 56f) to views(context, true)
        )))
    }

    companion object {
        internal fun views(context: Context, wide: Boolean): RemoteViews = RemoteViews(context.packageName,
            if (wide) R.layout.widget_sirius_wide else R.layout.widget_sirius).apply {
            setOnClickPendingIntent(R.id.widget_talk, PendingIntent.getActivity(context, 61,
                VoiceEntry.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }
}
