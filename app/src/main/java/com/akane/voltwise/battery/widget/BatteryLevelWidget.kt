package com.akane.voltwise.battery.widget

import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent

class BatteryLevelWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.invalidate(BatteryLevelWidget::class.java)
        WidgetUpdater.refresh(context, goAsync())
    }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetUpdater.invalidate(BatteryLevelWidget::class.java)
    override fun onEnabled(context: Context) = WidgetUpdater.invalidate(BatteryLevelWidget::class.java)
    override fun onDisabled(context: Context) = WidgetUpdater.invalidate(BatteryLevelWidget::class.java)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetUpdater.ACTION_REFRESH) {
            WidgetUpdater.refresh(context, goAsync())
        }
    }
}