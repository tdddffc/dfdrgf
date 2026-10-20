package com.weatherfocus.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.weatherfocus.app.data.prefs.SettingsRepository
import com.weatherfocus.app.data.repository.WeatherRepository
import com.weatherfocus.app.widget.WidgetRenderer
import kotlinx.coroutines.flow.first

/** Fetches fresh weather and redraws all home-screen widgets. */
class WidgetUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val settings = SettingsRepository(applicationContext).settingsFlow.first()
        val bundle = WeatherRepository().loadWeather(settings)
        WidgetRenderer.renderAll(applicationContext, settings, bundle)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
