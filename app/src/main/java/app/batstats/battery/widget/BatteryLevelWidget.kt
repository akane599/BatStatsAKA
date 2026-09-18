package app.batstats.battery.widget

import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent

class BatteryLevelWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.refresh(context, goAsync())
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetUpdater.ACTION_REFRESH) {
            WidgetUpdater.refresh(context, goAsync())
        }
    }
}