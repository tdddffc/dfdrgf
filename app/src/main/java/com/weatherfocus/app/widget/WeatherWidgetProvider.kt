package com.weatherfocus.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherfocus.app.worker.WidgetUpdateWorker
import com.weatherfocus.app.worker.WorkScheduler

/** The full weather widget. Resize it bigger for more info, up to a full-screen size. */
class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = WidgetUpdates.requestNow(context)
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) = WidgetUpdates.requestNow(context)
    override fun onEnabled(context: Context) = WorkScheduler.scheduleWidgetUpdates(context)
    override fun onDisabled(context: Context) = WidgetUpdates.cancelIfNoWidgets(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRenderer.ACTION_REFRESH) WidgetUpdates.requestNow(context)
    }
}

/** The alerts-only widget. */
class AlertsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = WidgetUpdates.requestNow(context)
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) = WidgetUpdates.requestNow(context)
    override fun onEnabled(context: Context) = WorkScheduler.scheduleWidgetUpdates(context)
    override fun onDisabled(context: Context) = WidgetUpdates.cancelIfNoWidgets(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRenderer.ACTION_REFRESH) WidgetUpdates.requestNow(context)
    }
}

object WidgetUpdates {
    /** Fetch fresh weather and redraw every widget, as soon as possible. */
    fun requestNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "weather_widget_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        )
    }

    fun cancelIfNoWidgets(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val any = mgr.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java)).isNotEmpty() ||
            mgr.getAppWidgetIds(ComponentName(context, AlertsWidgetProvider::class.java)).isNotEmpty()
        if (!any) WorkScheduler.cancelWidgetUpdates(context)
    }
}
