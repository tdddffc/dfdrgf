package com.weatherfocus.app.data.alert

import com.weatherfocus.app.data.model.AlertRuleType
import com.weatherfocus.app.data.model.CustomAlertMatch
import com.weatherfocus.app.data.model.CustomAlertRules
import com.weatherfocus.app.data.model.OpenMeteoHourly
import com.weatherfocus.app.data.repository.WeatherCodeMapper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Evaluates the user's custom weather alert rules (Settings > Custom Weather Alert Rules)
 * against Open-Meteo's free hourly forecast. For each enabled rule this scans forward from
 * "now" out to [CustomAlertRules.horizonHours] and reports the first hour the threshold is
 * met, flagged [CustomAlertMatch.leadWarning] once it's inside the configured lead time.
 */
object CustomWeatherAlertEngine {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val weekdayDateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val hourlyTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)

    private data class Point(val atMillis: Long, val temp: Double?, val windSpeed: Double?, val uvi: Double?, val pop: Int?, val code: Int?,
        val feelsLike: Double? = null, val humidity: Int? = null, val rain: Double? = null,
        val snowCm: Double? = null, val visibility: Double? = null, val gust: Double? = null)

    fun evaluate(hourly: OpenMeteoHourly?, rules: CustomAlertRules, nowMillis: Long = System.currentTimeMillis()): List<CustomAlertMatch> {
        if (!rules.enabled || hourly?.time == null) return emptyList()

        val horizonMillis = TimeUnit.HOURS.toMillis(rules.horizonHours.coerceIn(1, 48).toLong())
        val leadMillis = TimeUnit.HOURS.toMillis(rules.leadTimeHours.coerceIn(1, 24).toLong())
        val windowEnd = nowMillis + horizonMillis

        val allPoints = hourly.time.indices.mapNotNull { i ->
            val millis = runCatching { hourlyTimeFormat.parse(hourly.time[i])?.time }.getOrNull() ?: return@mapNotNull null
            if (millis > windowEnd) return@mapNotNull null
            Point(
                atMillis = millis,
                temp = hourly.temperature_2m?.getOrNull(i),
                windSpeed = hourly.wind_speed_10m?.getOrNull(i),
                uvi = hourly.uv_index?.getOrNull(i),
                pop = hourly.precipitation_probability?.getOrNull(i),
                code = hourly.weather_code?.getOrNull(i),
                feelsLike = hourly.apparent_temperature?.getOrNull(i),
                humidity = hourly.relative_humidity_2m?.getOrNull(i),
                rain = hourly.rain?.getOrNull(i),
                snowCm = hourly.snowfall?.getOrNull(i),
                visibility = hourly.visibility?.getOrNull(i),
                gust = hourly.wind_gusts_10m?.getOrNull(i)
            )
        }.sortedBy { it.atMillis }
        // Only hours from "now" onward can trigger; earlier ones are kept purely as "previous hour"
        // context for the transition rules (frost arriving, refreezing, rapid drop).
        val points = allPoints.filter { it.atMillis >= nowMillis }

        val results = mutableListOf<CustomAlertMatch>()
        fun isImminent(atMillis: Long) = atMillis - nowMillis <= leadMillis
        fun whenText(atMillis: Long) = "${dayLabel(atMillis, nowMillis)}, ${timeFormat.format(Date(atMillis))}"

        if (rules.tempAboveEnabled) {
            points.firstOrNull { (it.temp ?: Double.MIN_VALUE) >= rules.tempAboveValue }?.let { p ->
                results += CustomAlertMatch(
                    AlertRuleType.TEMP_ABOVE, "Temperature above ${fmt(rules.tempAboveValue)}\u00B0C",
                    "${whenText(p.atMillis)} \u2014 Forecast ${p.temp?.let { fmt(it) } ?: "\u2014"}\u00B0C",
                    dayLabel(p.atMillis, nowMillis), p.atMillis, isImminent(p.atMillis)
                )
            }
        }
        if (rules.tempBelowEnabled) {
            points.firstOrNull { (it.temp ?: Double.MAX_VALUE) <= rules.tempBelowValue }?.let { p ->
                results += CustomAlertMatch(
                    AlertRuleType.TEMP_BELOW, "Temperature below ${fmt(rules.tempBelowValue)}\u00B0C",
                    "${whenText(p.atMillis)} \u2014 Forecast ${p.temp?.let { fmt(it) } ?: "\u2014"}\u00B0C",
                    dayLabel(p.atMillis, nowMillis), p.atMillis, isImminent(p.atMillis)
                )
            }
        }
        if (rules.uvIndexEnabled) {
            points.firstOrNull { (it.uvi ?: -1.0) >= rules.uvIndexValue }?.let { p ->
                results += CustomAlertMatch(
                    AlertRuleType.UV_INDEX, "UV index above ${fmt(rules.uvIndexValue)}",
                    "${whenText(p.atMillis)} \u2014 Forecast UV ${p.uvi?.let { fmt(it) } ?: "\u2014"}",
                    dayLabel(p.atMillis, nowMillis), p.atMillis, isImminent(p.atMillis)
                )
            }
        }
        if (rules.windSpeedEnabled) {
            points.firstOrNull { (it.windSpeed ?: -1.0) >= rules.windSpeedValue }?.let { p ->
                results += CustomAlertMatch(
                    AlertRuleType.WIND_SPEED, "Wind speed above ${fmt(rules.windSpeedValue)} km/h",
                    "${whenText(p.atMillis)} \u2014 Forecast ${p.windSpeed?.let { fmt(it) } ?: "\u2014"} km/h",
                    dayLabel(p.atMillis, nowMillis), p.atMillis, isImminent(p.atMillis)
                )
            }
        }
        if (rules.rainProbEnabled) {
            points.firstOrNull { (it.pop ?: -1) >= rules.rainProbValue }?.let { p ->
                results += CustomAlertMatch(
                    AlertRuleType.RAIN_PROBABILITY, "Rain probability above ${rules.rainProbValue}%",
                    "${whenText(p.atMillis)} \u2014 Forecast ${p.pop ?: 0}%",
                    dayLabel(p.atMillis, nowMillis), p.atMillis, isImminent(p.atMillis)
                )
            }
        }
        if (rules.thunderstormEnabled) {
            points.firstOrNull { WeatherCodeMapper.isThunder(it.code) }?.let { p ->
                results += CustomAlertMatch(
                    AlertRuleType.THUNDERSTORM, "Thunderstorm expected",
                    "Expected ${whenText(p.atMillis)}", dayLabel(p.atMillis, nowMillis), p.atMillis, isImminent(p.atMillis)
                )
            }
        }
        if (rules.snowEnabled) {
            points.firstOrNull { WeatherCodeMapper.isSnow(it.code) }?.let { p ->
                results += CustomAlertMatch(
                    AlertRuleType.SNOW, "Snow expected",
                    "Expected ${whenText(p.atMillis)}", dayLabel(p.atMillis, nowMillis), p.atMillis, isImminent(p.atMillis)
                )
            }
        }

        // ---------------- Winter / Nordic rules ----------------
        fun add(type: AlertRuleType, label: String, detail: String, p: Point) {
            results += CustomAlertMatch(
                type, label, "${whenText(p.atMillis)} \u2014 $detail",
                dayLabel(p.atMillis, nowMillis), p.atMillis, isImminent(p.atMillis)
            )
        }
        fun previousOf(p: Point): Point? = allPoints.lastOrNull { it.atMillis < p.atMillis }
        fun isWet(p: Point): Boolean =
            (p.rain ?: 0.0) > 0.1 || (p.code != null && p.code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82))
        fun isSnowing(p: Point): Boolean = (p.snowCm ?: 0.0) > 0.0 || WeatherCodeMapper.isSnow(p.code)

        if (rules.freezingRainEnabled) {
            points.firstOrNull { p ->
                val t = p.temp ?: return@firstOrNull false
                (p.code != null && p.code in listOf(56, 57, 66, 67)) || (t <= rules.freezingRainMaxTemp && isWet(p))
            }?.let { p ->
                add(AlertRuleType.FREEZING_RAIN, "\uD83E\uDDCA Freezing rain \u2013 extremely slippery",
                    "Rain at ${p.temp?.let { fmt(it) } ?: "\u2014"}\u00B0C, expect glaze ice", p)
            }
        }
        if (rules.blackIceEnabled) {
            points.firstOrNull { p ->
                val t = p.temp ?: return@firstOrNull false
                val prev = previousOf(p)
                val prevT = prev?.temp
                val wetNearFreezing = t <= rules.blackIceMaxTemp && t >= rules.blackIceMaxTemp - 4.0 && isWet(p)
                val refreeze = prev != null && prevT != null && prevT > 0.0 && t <= 0.0 &&
                    (isWet(prev) || isWet(p) || (p.humidity ?: 0) >= 80)
                wetNearFreezing || refreeze
            }?.let { p ->
                add(AlertRuleType.BLACK_ICE, "\u26A0\uFE0F Black ice risk",
                    "${p.temp?.let { fmt(it) } ?: "\u2014"}\u00B0C and wet surfaces \u2013 slippery roads/paths", p)
            }
        }
        if (rules.frostArrivesEnabled) {
            points.firstOrNull { p ->
                val t = p.temp ?: return@firstOrNull false
                val prevT = previousOf(p)?.temp ?: return@firstOrNull false
                prevT > rules.frostValue && t <= rules.frostValue
            }?.let { p ->
                add(AlertRuleType.FROST_ARRIVES, "\u2744\uFE0F Frost arriving (\u2264 ${fmt(rules.frostValue)}\u00B0C)",
                    "Dropping to ${p.temp?.let { fmt(it) } ?: "\u2014"}\u00B0C \u2013 frost on car & roads", p)
            }
        }
        if (rules.heavySnowEnabled) {
            points.firstOrNull { (it.snowCm ?: 0.0) >= rules.heavySnowValue }?.let { p ->
                add(AlertRuleType.HEAVY_SNOW, "\uD83C\uDF28\uFE0F Heavy snowfall (\u2265 ${fmt(rules.heavySnowValue)} cm/h)",
                    "About ${p.snowCm?.let { fmt(it) } ?: "\u2014"} cm of snow this hour", p)
            }
        }
        if (rules.snowstormEnabled) {
            points.firstOrNull { p ->
                isSnowing(p) && maxOf(p.windSpeed ?: 0.0, (p.gust ?: 0.0) * 0.7) >= rules.snowstormWindValue
            }?.let { p ->
                add(AlertRuleType.SNOWSTORM, "\uD83C\uDF2C\uFE0F Snowstorm / drifting snow",
                    "Snow with wind ${p.windSpeed?.let { fmt(it) } ?: "\u2014"} km/h" +
                        (p.gust?.let { ", gusts ${fmt(it)} km/h" } ?: "") + " \u2013 poor visibility", p)
            }
        }
        if (rules.windGustEnabled) {
            points.firstOrNull { (it.gust ?: -1.0) >= rules.windGustValue }?.let { p ->
                add(AlertRuleType.WIND_GUST, "\uD83D\uDCA8 Strong wind gusts (\u2265 ${fmt(rules.windGustValue)} km/h)",
                    "Gusts up to ${p.gust?.let { fmt(it) } ?: "\u2014"} km/h", p)
            }
        }
        if (rules.windChillEnabled) {
            points.firstOrNull { (it.feelsLike ?: Double.MAX_VALUE) <= rules.windChillValue }?.let { p ->
                add(AlertRuleType.WIND_CHILL, "\uD83E\uDD76 Wind chill \u2264 ${fmt(rules.windChillValue)}\u00B0C",
                    "Feels like ${p.feelsLike?.let { fmt(it) } ?: "\u2014"}\u00B0C (air ${p.temp?.let { fmt(it) } ?: "\u2014"}\u00B0C)", p)
            }
        }
        if (rules.lowVisibilityEnabled) {
            points.firstOrNull { (it.visibility ?: Double.MAX_VALUE) <= rules.lowVisibilityValue }?.let { p ->
                add(AlertRuleType.LOW_VISIBILITY, "\uD83C\uDF2B\uFE0F Low visibility (\u2264 ${fmt(rules.lowVisibilityValue)} m)",
                    "Visibility about ${p.visibility?.let { fmt(it) } ?: "\u2014"} m \u2013 drive carefully", p)
            }
        }
        if (rules.rapidDropEnabled) {
            points.firstOrNull { p ->
                val t = p.temp ?: return@firstOrNull false
                val ref = allPoints.filter { it.atMillis < p.atMillis && p.atMillis - it.atMillis <= TimeUnit.HOURS.toMillis(6) }
                    .mapNotNull { it.temp }.maxOrNull() ?: return@firstOrNull false
                ref - t >= rules.rapidDropValue
            }?.let { p ->
                add(AlertRuleType.RAPID_TEMP_DROP, "\uD83D\uDCC9 Rapid temperature drop",
                    "Falling \u2265 ${fmt(rules.rapidDropValue)}\u00B0C within 6h, down to ${p.temp?.let { fmt(it) } ?: "\u2014"}\u00B0C", p)
            }
        }

        return results.sortedBy { it.triggerAtMillis }
    }

    private fun dayLabel(triggerMillis: Long, nowMillis: Long): String {
        val startOfToday = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val diffDays = ((triggerMillis - startOfToday) / TimeUnit.DAYS.toMillis(1)).toInt()
        return when {
            diffDays <= 0 -> "Today"
            diffDays == 1 -> "Tomorrow"
            diffDays in 2..6 -> "In $diffDays days"
            else -> weekdayDateFormat.format(Date(triggerMillis))
        }
    }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.US, "%.1f", value)
}
