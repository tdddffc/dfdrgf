package com.weatherfocus.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.weatherfocus.app.data.alert.CustomWeatherAlertEngine
import com.weatherfocus.app.data.prefs.SettingsRepository
import com.weatherfocus.app.data.repository.WeatherRepository
import com.weatherfocus.app.notification.NotificationHelper
import kotlinx.coroutines.flow.first

/** Periodically re-checks the user's custom weather alert rules and fires a notification for anything inside the lead-time window. */
class WeatherAlertCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val settingsRepo = SettingsRepository(applicationContext)
            val settings = settingsRepo.settingsFlow.first()
            if (!settings.customAlertRules.enabled) return Result.success()

            val bundle = WeatherRepository().loadWeather(settings)
            val matches = CustomWeatherAlertEngine.evaluate(bundle.hourlyForAlerts, settings.customAlertRules)
            // Don't re-notify the same forecast hour every time the hourly check runs.
            val prefs = applicationContext.getSharedPreferences("weather_alert_dedup", Context.MODE_PRIVATE)
            val cutoff = System.currentTimeMillis() - 12L * 60 * 60 * 1000
            val seen = (prefs.getStringSet("keys", emptySet()) ?: emptySet())
                .filter { it.substringAfterLast("|").toLongOrNull()?.let { t -> t > cutoff } ?: false }
                .toMutableSet()
            matches.filter { it.leadWarning }.forEach {
                val key = "${it.type}|${it.triggerAtMillis}"
                if (key !in seen) {
                    NotificationHelper.showAlert(applicationContext, it, settings.doNotDisturb)
                    seen += key
                }
            }
            prefs.edit().putStringSet("keys", seen).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
