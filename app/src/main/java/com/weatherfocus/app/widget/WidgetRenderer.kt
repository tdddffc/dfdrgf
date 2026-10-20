package com.weatherfocus.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.weatherfocus.app.MainActivity
import com.weatherfocus.app.R
import com.weatherfocus.app.data.alert.CustomWeatherAlertEngine
import com.weatherfocus.app.data.model.AppSettings
import com.weatherfocus.app.data.model.ConditionGroup
import com.weatherfocus.app.data.model.CustomAlertMatch
import com.weatherfocus.app.data.model.DayForecast
import com.weatherfocus.app.data.model.WidgetStyle
import com.weatherfocus.app.data.model.WidgetTile
import com.weatherfocus.app.data.repository.WeatherBundle
import com.weatherfocus.app.ui.WeatherFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds the RemoteViews for the two home-screen widgets (the full weather widget and the alerts-only
 * widget) from the latest weather bundle and the user's widget settings. The weather widget adapts to
 * its size: tiny shows the header only, bigger adds alerts, the hourly strip, info tiles and the
 * multi-day list, and a full-screen size adds the complete alert list.
 */
object WidgetRenderer {
    const val ACTION_REFRESH = "com.weatherfocus.app.WIDGET_REFRESH"

    fun renderAll(context: Context, settings: AppSettings, bundle: WeatherBundle) {
        val mgr = AppWidgetManager.getInstance(context)
        val alerts = CustomWeatherAlertEngine.evaluate(bundle.hourlyForAlerts, settings.customAlertRules)
        mgr.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java)).forEach { id ->
            val (w, h) = sizeOf(mgr, id)
            mgr.updateAppWidget(id, weatherViews(context, settings, bundle, alerts, w, h))
        }
        mgr.getAppWidgetIds(ComponentName(context, AlertsWidgetProvider::class.java)).forEach { id ->
            val (w, h) = sizeOf(mgr, id)
            mgr.updateAppWidget(id, alertsViews(context, settings, alerts, w, h))
        }
    }

    private fun sizeOf(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300))
        return w to h
    }

    private data class Palette(val text: Int, val bgPanel: Int, val bgTile: Int, val alertBg: Int, val alertText: Int, val alertSub: Int)

    private fun paletteFor(style: WidgetStyle) = when (style) {
        WidgetStyle.LIGHT -> Palette(
            Color.parseColor("#1D2A4D"), R.drawable.widget_panel_light, R.drawable.widget_tile_light,
            R.drawable.widget_alert_light, Color.parseColor("#7A1F1F"), Color.parseColor("#A14A4A")
        )
        WidgetStyle.DARK -> Palette(
            Color.parseColor("#EEF0F4"), R.drawable.widget_panel_dark, R.drawable.widget_tile_dark,
            R.drawable.widget_alert_dark, Color.parseColor("#FFE3E3"), Color.parseColor("#FFB4B4")
        )
        WidgetStyle.WEATHER -> Palette(
            Color.WHITE, R.drawable.widget_panel_weather, R.drawable.widget_tile_weather,
            R.drawable.widget_alert_light, Color.parseColor("#7A1F1F"), Color.parseColor("#A14A4A")
        )
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun refreshIntent(context: Context, provider: Class<*>, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, provider).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Background drawable: fixed for dark/light, otherwise the worst weather in the look-ahead window. */
    private fun backgroundFor(settings: AppSettings, bundle: WeatherBundle, slots: List<HourSlot>, now: Long): Int {
        when (settings.widget.style) {
            WidgetStyle.DARK -> return R.drawable.widget_bg_dark
            WidgetStyle.LIGHT -> return R.drawable.widget_bg_light
            WidgetStyle.WEATHER -> Unit
        }
        val cur = bundle.current
        var worst = cur.conditionGroup
        val hours = settings.widget.lookaheadHours
        if (hours > 0) {
            val end = now + hours * 3_600_000L
            slots.filter { it.millis in now..end }.forEach {
                val g = WidgetData.groupOfCode(it.code)
                if (WidgetData.severity(g) > WidgetData.severity(worst)) worst = g
            }
        }
        val night = WidgetData.isNight(now, cur.sunrise, cur.sunset)
        return when (worst) {
            ConditionGroup.CLEAR -> if (night) R.drawable.widget_bg_night else R.drawable.widget_bg_clear
            ConditionGroup.CLOUDY, ConditionGroup.UNKNOWN -> R.drawable.widget_bg_cloudy
            ConditionGroup.RAIN -> R.drawable.widget_bg_rain
            ConditionGroup.THUNDER -> R.drawable.widget_bg_thunder
            ConditionGroup.SNOW -> R.drawable.widget_bg_snow
            ConditionGroup.FOG -> R.drawable.widget_bg_fog
        }
    }

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun tileOut(
        tile: WidgetTile, bundle: WeatherBundle, upcoming: List<HourSlot>, now: Long, useF: Boolean,
        lat: Double, lon: Double
    ): TileOut {
        val cur = bundle.current
        val next12 = upcoming.take(12)
        val next24 = upcoming.take(24)
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val todayKey = dayFmt.format(Date(now))
        // "Today" tiles count what is still to come today (from the current hour on).
        val restOfToday = upcoming.filter { dayFmt.format(Date(it.millis)) == todayKey }
        val first = upcoming.firstOrNull()
        return when (tile) {
            WidgetTile.WIND -> TileOut(cur.windSpeedKmh?.let { "${it.roundToInt()} km/h" } ?: "--")
            WidgetTile.WIND_DIR -> TileOut(first?.windDirDeg?.let { WidgetData.compass(it) } ?: "--")
            WidgetTile.COLDEST -> TileOut(
                next24.mapNotNull { it.feelsLike }.minOrNull()?.let { WeatherFormat.tempNoUnit(it, useF) } ?: "--"
            )
            WidgetTile.FROST_HOURS -> {
                val n = next24.mapNotNull { it.temp }.takeIf { it.isNotEmpty() }?.count { it <= 0.0 }
                TileOut(n?.let { "$it h" } ?: "--", empty = n == null || n == 0)
            }
            WidgetTile.FROST_CAR -> WidgetExtras.frostOnCar(upcoming, now)
            WidgetTile.HUMIDITY -> TileOut(cur.humidity?.let { "$it%" } ?: "--")
            WidgetTile.DEW_POINT -> TileOut(first?.dewPoint?.let { WeatherFormat.tempNoUnit(it, useF) } ?: "--")
            WidgetTile.SNOW -> {
                val cm = restOfToday.mapNotNull { it.snowCm }.takeIf { it.isNotEmpty() }?.sum()
                TileOut(cm?.let { "${fmt1(it)} cm" } ?: "--", empty = cm == null || cm < 0.1)
            }
            WidgetTile.PRECIP -> {
                val mm = restOfToday.mapNotNull { it.rainMm }.takeIf { it.isNotEmpty() }?.sum()
                TileOut(mm?.let { "${fmt1(it)} mm" } ?: "--", empty = mm == null || mm < 0.1)
            }
            WidgetTile.RAIN_CHANCE -> TileOut(next12.mapNotNull { it.rainChance }.maxOrNull()?.let { "$it%" } ?: "--")
            WidgetTile.NEXT_PRECIP -> WidgetExtras.nextPrecip(upcoming, now)
            WidgetTile.CLOUDS -> TileOut(first?.cloudPercent?.let { "$it%" } ?: "--")
            WidgetTile.UV -> TileOut(first?.uv?.let { fmt1(it) } ?: "--")
            WidgetTile.PRESSURE -> TileOut(cur.pressure?.let { "$it hPa" } ?: "--")
            WidgetTile.VISIBILITY -> TileOut(
                first?.visibilityM?.let { if (it >= 1000) "${fmt1(it / 1000)} km" else "${it.roundToInt()} m" } ?: "--"
            )
            WidgetTile.MORNING_FOG -> WidgetExtras.morningFog(upcoming, now)
            WidgetTile.AURORA -> WidgetExtras.aurora(bundle.kpForecast, upcoming, lat, lon)
            WidgetTile.DAYLIGHT_CHANGE -> WidgetExtras.daylightChange(bundle.monthDays, lat, now)
            WidgetTile.LAST_YEAR -> bundle.lastYearToday?.let {
                TileOut(
                    "${WeatherFormat.tempNoUnit(it.maxTemp, useF)}/${WeatherFormat.tempNoUnit(it.minTemp, useF)}",
                    "Last year",
                    WidgetData.emojiForCode(it.weatherCode, false)
                )
            } ?: TileOut("--", "Last year")
        }
    }

    private fun weatherViews(
        context: Context, settings: AppSettings, bundle: WeatherBundle,
        alerts: List<CustomAlertMatch>, widthDp: Int, heightDp: Int
    ): RemoteViews {
        val ws = settings.widget
        val pal = paletteFor(ws.style)
        val rv = RemoteViews(context.packageName, R.layout.widget_weather)
        val now = System.currentTimeMillis()
        val useF = settings.useFahrenheit
        val cur = bundle.current
        val all = WidgetData.slots(bundle.hourlyForAlerts)
        val upcoming = WidgetData.upcoming(all, now)
        val show = android.view.View.VISIBLE
        val hide = android.view.View.GONE

        rv.setInt(R.id.root, "setBackgroundResource", backgroundFor(settings, bundle, all, now))
        WidgetIds.weatherTexts.forEach { rv.setTextColor(it, pal.text) }
        rv.setTextColor(R.id.refresh, pal.text)

        // ----- header + main -----
        val night = WidgetData.isNight(now, cur.sunrise, cur.sunset)
        rv.setTextViewText(R.id.loc, "\uD83D\uDCCD ${settings.cityName}, ${settings.countryLabel}")
        rv.setTextViewText(R.id.updated, "Updated ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))} \u00B7 tap to open")
        val emoji = upcoming.firstOrNull()?.code?.let { WidgetData.emojiForCode(it, night) }
            ?: WidgetData.emojiForGroup(cur.conditionGroup, night)
        rv.setTextViewText(R.id.emoji, emoji)
        rv.setTextViewText(R.id.temp, WeatherFormat.tempNoUnit(cur.temp, useF))
        rv.setTextViewText(R.id.cond, cur.description ?: "")
        rv.setTextViewText(R.id.feels, "Feels ${WeatherFormat.tempNoUnit(cur.feelsLike, useF)}")
        rv.setTextViewText(R.id.hilo, "H ${WeatherFormat.tempNoUnit(cur.tempMax, useF)} \u00B7 L ${WeatherFormat.tempNoUnit(cur.tempMin, useF)}")

        // What to wear today: icons in the free space between the temperature and "Feels like" (wide widgets only).
        val wearMax = when {
            !ws.showWear || heightDp < 200 -> 0
            widthDp >= 420 -> 5
            widthDp >= 340 -> 4
            widthDp >= 300 -> 3
            else -> 0
        }
        rv.setTextViewText(R.id.wear, WidgetExtras.wearIcons(cur.feelsLike, upcoming, wearMax))

        // ----- alerts, directly under the temperature -----
        // Big widget: every active alert gets its own row. Smaller sizes: just the first one, with "+N more".
        val bigAlerts = heightDp >= 420
        val alertRows = when {
            !ws.showAlert || alerts.isEmpty() || heightDp < 130 -> 0
            bigAlerts -> minOf(alerts.size, 6)
            else -> 1
        }
        rv.setViewVisibility(R.id.alert_box, if (alertRows > 0) show else hide)
        for (i in 0 until 6) {
            val a = alerts.getOrNull(i)
            val visible = i < alertRows && a != null
            rv.setViewVisibility(WidgetIds.awRow[i], if (visible) show else hide)
            if (visible && a != null) {
                val (e, title) = WidgetData.splitEmoji(a.label)
                val more = if (!bigAlerts && alerts.size > 1) "  (+${alerts.size - 1} more)" else ""
                rv.setInt(WidgetIds.awRow[i], "setBackgroundResource", pal.alertBg)
                rv.setTextViewText(WidgetIds.awE[i], e)
                rv.setTextViewText(WidgetIds.awT[i], title + more)
                rv.setTextViewText(WidgetIds.awS[i], a.detail)
                rv.setTextColor(WidgetIds.awT[i], pal.alertText)
                rv.setTextColor(WidgetIds.awS[i], pal.alertSub)
            }
        }

        // ----- hourly strip -----
        val showHours = ws.showHours && heightDp >= 210
        rv.setViewVisibility(R.id.hours_box, if (showHours) show else hide)
        if (showHours) {
            rv.setInt(R.id.hours_box, "setBackgroundResource", pal.bgPanel)
            val n = if (widthDp < 300) minOf(ws.hoursCount, 4) else ws.hoursCount.coerceIn(3, 8)
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            for (i in 0 until 8) {
                val slot = upcoming.getOrNull(i)
                val visible = i < n && slot != null
                rv.setViewVisibility(WidgetIds.hourBox[i], if (visible) show else hide)
                if (visible && slot != null) {
                    rv.setTextViewText(WidgetIds.hourT[i], if (i == 0) "Now" else timeFmt.format(Date(slot.millis)))
                    rv.setTextViewText(WidgetIds.hourE[i], WidgetData.emojiForCode(slot.code, WidgetData.isNight(slot.millis, cur.sunrise, cur.sunset)))
                    rv.setTextViewText(WidgetIds.hourV[i], WeatherFormat.tempNoUnit(slot.temp, useF))
                    rv.setTextViewText(WidgetIds.hourP[i], slot.rainChance?.let { "\uD83D\uDCA7 $it%" } ?: "")
                }
            }
        }

        // ----- next days: one row of small tiles (icon + high / low) -----
        val realDays: List<DayForecast> = bundle.monthDays.filter { !it.isOutlookEstimate }.drop(1).ifEmpty { bundle.next3Days }
        val dayCount = minOf(if (widthDp < 300) minOf(ws.daysCount, 5) else ws.daysCount, realDays.size)
        val showDays = ws.showDays && dayCount > 0 && heightDp >= 300
        rv.setViewVisibility(R.id.days_box, if (showDays) show else hide)
        if (showDays) {
            for (i in 0 until 7) {
                val d = realDays.getOrNull(i)
                val visible = i < dayCount && d != null
                rv.setViewVisibility(WidgetIds.dayBox[i], if (visible) show else hide)
                if (visible && d != null) {
                    rv.setInt(WidgetIds.dayBox[i], "setBackgroundResource", pal.bgTile)
                    rv.setTextViewText(WidgetIds.dayD[i], d.dayLabel)
                    rv.setTextViewText(WidgetIds.dayE[i], WidgetData.emojiForGroup(d.conditionGroup, false))
                    rv.setTextViewText(WidgetIds.dayT[i], WeatherFormat.tempNoUnit(d.maxTemp, useF))
                    rv.setTextViewText(WidgetIds.dayL[i], WeatherFormat.tempNoUnit(d.minTemp, useF))
                }
            }
        }

        // ----- info tiles: 4 per row, more rows as the widget gets taller (up to 16 tiles) -----
        val tileRows = when {
            heightDp >= 640 -> 4
            heightDp >= 540 -> 3
            heightDp >= 440 -> 2
            heightDp >= 340 -> 1
            else -> 0
        }
        // Work out every picked tile, drop the "smart" ones that have nothing to show, then fill the grid.
        val computed = ws.tiles.map { it to tileOut(it, bundle, upcoming, now, useF, settings.latitude, settings.longitude) }
        val chosen = computed
            .filter { (tile, out) -> !(ws.hideEmptyTiles && tile.hidesWhenEmpty && out.empty) }
            .take(minOf(16, tileRows * 4))
        val showTiles = ws.showTiles && chosen.isNotEmpty()
        rv.setViewVisibility(R.id.tiles_box, if (showTiles) show else hide)
        if (showTiles) {
            for (r in 0 until 4) {
                rv.setViewVisibility(WidgetIds.tileRow[r], if (chosen.size > r * 4) show else hide)
            }
            for (i in 0 until 16) {
                val entry = chosen.getOrNull(i)
                rv.setViewVisibility(WidgetIds.tileBox[i], if (entry != null) show else android.view.View.INVISIBLE)
                if (entry != null) {
                    val (tile, out) = entry
                    rv.setInt(WidgetIds.tileBox[i], "setBackgroundResource", pal.bgTile)
                    rv.setTextViewText(WidgetIds.tileE[i], out.emoji ?: tile.emoji)
                    rv.setTextViewText(WidgetIds.tileV[i], out.value)
                    rv.setTextViewText(WidgetIds.tileL[i], out.label ?: tile.label)
                }
            }
        }

        // ----- sunrise / sunset (sun and moon) -----
        val showSun = ws.showSun && heightDp >= 250
        rv.setViewVisibility(R.id.foot_box, if (showSun) show else hide)
        if (showSun) {
            rv.setTextViewText(R.id.sunrise, "\u2600\uFE0F ${cur.sunrise ?: "--"}")
            rv.setTextViewText(R.id.sunset, "\uD83C\uDF19 ${cur.sunset ?: "--"}")
            rv.setTextViewText(R.id.daylight, WidgetData.daylightText(cur.sunrise, cur.sunset)?.let { "$it daylight" } ?: "")
        }

        rv.setOnClickPendingIntent(R.id.root, openAppIntent(context))
        rv.setOnClickPendingIntent(R.id.refresh, refreshIntent(context, WeatherWidgetProvider::class.java, 1))
        return rv
    }

    private fun alertsViews(context: Context, settings: AppSettings, alerts: List<CustomAlertMatch>, widthDp: Int, heightDp: Int): RemoteViews {
        val ws = settings.widget
        val pal = paletteFor(if (ws.style == WidgetStyle.WEATHER) WidgetStyle.DARK else ws.style)
        val rv = RemoteViews(context.packageName, R.layout.widget_alerts)
        val now = System.currentTimeMillis()

        rv.setInt(R.id.root, "setBackgroundResource", if (ws.style == WidgetStyle.LIGHT) R.drawable.widget_bg_light else R.drawable.widget_bg_dark)
        WidgetIds.alertTexts.forEach { rv.setTextColor(it, pal.text) }
        rv.setTextColor(R.id.refresh, pal.text)
        rv.setTextViewText(R.id.a_sub, "${settings.cityName} · updated ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))}")

        val rules = settings.customAlertRules
        val maxRows = when {
            heightDp >= 300 -> 5
            heightDp >= 220 -> 4
            heightDp >= 160 -> 3
            heightDp >= 110 -> 2
            else -> 1
        }
        if (alerts.isEmpty()) {
            rv.setViewVisibility(R.id.a_empty, android.view.View.VISIBLE)
            rv.setTextViewText(
                R.id.a_empty,
                if (rules.enabled) "✅ No alerts in the next ${rules.horizonHours} hours. Enjoy!"
                else "Custom alerts are off. Turn them on in the app: Settings → Custom Weather Alerts."
            )
        } else {
            rv.setViewVisibility(R.id.a_empty, android.view.View.GONE)
        }
        for (i in 0 until 5) {
            val a = alerts.getOrNull(i)
            val visible = a != null && i < maxRows
            rv.setViewVisibility(WidgetIds.alRow[i], if (visible) android.view.View.VISIBLE else android.view.View.GONE)
            if (visible && a != null) {
                val (e, title) = WidgetData.splitEmoji(a.label)
                rv.setInt(WidgetIds.alRow[i], "setBackgroundResource", pal.bgPanel)
                rv.setTextViewText(WidgetIds.alE[i], e)
                rv.setTextViewText(WidgetIds.alT[i], title)
                rv.setTextViewText(WidgetIds.alS[i], a.detail)
            }
        }
        rv.setOnClickPendingIntent(R.id.root, openAppIntent(context))
        rv.setOnClickPendingIntent(R.id.refresh, refreshIntent(context, AlertsWidgetProvider::class.java, 2))
        return rv
    }
}
