package com.weatherfocus.app.widget

import com.weatherfocus.app.data.model.DayForecast
import com.weatherfocus.app.data.model.KpPoint
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What one info tile shows. [label] / [emoji] override the tile's default ones when the text depends on
 * the situation (e.g. "Rain from" vs "Snow until"). [empty] means "nothing worth showing right now":
 * tiles that hide themselves are left out of the widget when this is true.
 */
data class TileOut(
    val value: String,
    val label: String? = null,
    val emoji: String? = null,
    val empty: Boolean = false
)

/** Calculations behind the "smart" tiles and the what-to-wear icons. All forecast-based estimates. */
object WidgetExtras {
    private const val DEG = PI / 180.0
    private const val HOUR = 3_600_000L

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    private fun clock(millis: Long): String = String.format("%02d:00", hourOf(millis))

    /** The next moment (after [now]) when the local clock reads [hour]:00. */
    private fun nextClock(now: Long, hour: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** Rounds a 0..1 chance to the nearest 5% and never promises more than 95% - it is only a forecast. */
    private fun pct5(p: Double): Int = ((minOf(p, 0.95) * 100.0) / 5.0).roundToInt() * 5

    /* ---------------- Aurora ---------------- */

    /** Height of the sun above the horizon in degrees for the given instant and place. */
    fun sunElevationDeg(millis: Long, lat: Double, lon: Double): Double {
        val n = millis / 86_400_000.0 + 2440587.5 - 2451545.0
        val meanLon = (280.460 + 0.9856474 * n) % 360.0
        val g = ((357.528 + 0.9856003 * n) % 360.0) * DEG
        val lambda = (meanLon + 1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG
        val eps = (23.439 - 0.0000004 * n) * DEG
        val decl = asin(sin(eps) * sin(lambda))
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val gmst = (280.46061837 + 360.98564736629 * n) % 360.0
        val ha = (gmst + lon) * DEG - ra
        val latR = lat * DEG
        val s = sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(ha)
        return asin(s.coerceIn(-1.0, 1.0)) / DEG
    }

    /** Approximate geomagnetic latitude (centred-dipole model) - what decides how far south aurora reaches. */
    private fun geomagneticLat(lat: Double, lon: Double): Double {
        val poleLat = 80.7 * DEG
        val poleLon = -72.7 * DEG
        val la = lat * DEG
        val lo = lon * DEG
        val s = sin(la) * sin(poleLat) + cos(la) * cos(poleLat) * cos(lo - poleLon)
        return asin(s.coerceIn(-1.0, 1.0)) / DEG
    }

    private fun kpAt(kp: List<KpPoint>, millis: Long): Double? =
        kp.lastOrNull { it.startMillis <= millis && millis < it.startMillis + 3 * HOUR }?.kp

    /**
     * Chance (%) of seeing aurora from this location during the dark hours of the next ~20 hours.
     * Combines NOAA's Kp forecast (how far south the oval reaches), whether the sun is far enough
     * below the horizon, and the forecast cloud cover. Rough guide, not a promise.
     */
    fun aurora(kp: List<KpPoint>, upcoming: List<HourSlot>, lat: Double, lon: Double): TileOut {
        val none = TileOut("0%", "Aurora chance", empty = true)
        if (kp.isEmpty()) return none
        // Kp needed for roughly a 50% geomagnetic chance: ~1.4 at Tromso, ~3.4 at Trondheim, ~5 around Oslo.
        val needed = (5.0 - 0.45 * (geomagneticLat(lat, lon) - 59.5)).coerceIn(0.5, 9.0)
        var best = 0.0
        var bestAt = 0L
        for (slot in upcoming.take(20)) {
            val mid = slot.millis + 30 * 60_000L
            if (sunElevationDeg(mid, lat, lon) > -6.0) continue
            val k = kpAt(kp, mid) ?: continue
            val geo = 1.0 / (1.0 + exp(-(k - needed) / 0.8))
            val clear = 1.0 - 0.9 * ((slot.cloudPercent ?: 50) / 100.0)
            val p = geo * clear
            if (p > best) {
                best = p
                bestAt = slot.millis
            }
        }
        val pct = pct5(best)
        if (pct < 5) return none
        return TileOut("$pct%", "Aurora ${clock(bestAt)}")
    }

    /* ---------------- Frost on the car ---------------- */

    /** Chance (%) of a frosted car / windscreen tonight until 09:00, from temperature, dew point, cloud and wind. */
    fun frostOnCar(upcoming: List<HourSlot>, now: Long): TileOut {
        val none = TileOut("0%", "Frost on car", empty = true)
        val end = nextClock(now, 9)
        var best = 0.0
        for (s in upcoming) {
            if (s.millis > end) break
            val h = hourOf(s.millis)
            if (h in 10..19) continue
            val t = s.temp ?: continue
            var p = when {
                t <= -1.0 -> 0.90
                t <= 0.0 -> 0.85
                t <= 1.0 -> 0.65
                t <= 2.0 -> 0.45
                t <= 3.0 -> 0.20
                else -> 0.0
            }
            if (p == 0.0) continue
            val cloud = s.cloudPercent
            if (cloud != null) {
                if (cloud < 30) p = minOf(0.98, p + 0.12) else if (cloud >= 80) p *= 0.65
            }
            val wind = s.windSpeed
            if (wind != null && wind >= 15.0 && t > -3.0) p *= 0.65
            val dew = s.dewPoint
            if (dew != null && t > 0.0 && (t - dew) > 5.0) p *= 0.6
            if ((s.rainMm ?: 0.0) >= 0.1 && t <= 0.5) p = max(p, 0.90)
            best = max(best, p)
        }
        val pct = pct5(best)
        if (pct < 10) return none
        return TileOut("$pct%", "Frost on car")
    }

    /* ---------------- Morning fog ---------------- */

    /** Chance (%) of fog between 03:00 and 10:00 (the next such morning), from dew-point spread, wind and visibility. */
    fun morningFog(upcoming: List<HourSlot>, now: Long): TileOut {
        val none = TileOut("0%", "Morning fog", empty = true)
        val end = nextClock(now, 10)
        val start = end - 7 * HOUR
        var best = 0.0
        for (s in upcoming) {
            if (s.millis < start) continue
            if (s.millis >= end) break
            var p = 0.0
            val t = s.temp
            val d = s.dewPoint
            if (t != null && d != null) {
                val spread = t - d
                p = when {
                    spread <= 0.5 -> 0.85
                    spread <= 1.5 -> 0.60
                    spread <= 2.5 -> 0.30
                    spread <= 3.5 -> 0.12
                    else -> 0.0
                }
                val wind = s.windSpeed
                if (wind != null) {
                    if (wind >= 15.0) p *= 0.3 else if (wind >= 10.0) p *= 0.6
                }
            }
            val code = s.code
            if (code == 45 || code == 48) p = max(p, 0.95)
            val vis = s.visibilityM
            if (vis != null) {
                if (vis < 1000.0) p = max(p, 0.90) else if (vis < 3000.0) p = max(p, 0.50)
            }
            best = max(best, p)
        }
        val pct = pct5(best)
        if (pct < 10) return none
        return TileOut("$pct%", "Morning fog")
    }

    /* ---------------- Next rain / snow ---------------- */

    private fun isWet(s: HourSlot): Boolean {
        if ((s.rainMm ?: 0.0) >= 0.1 || (s.snowCm ?: 0.0) >= 0.1) return true
        val c = s.code ?: return false
        val precipCode = c in 51..67 || c in 71..77 || c in 80..86 || c in 95..99
        return precipCode && (s.rainChance ?: 0) >= 50
    }

    private fun kindOf(s: HourSlot): String {
        val c = s.code
        return when {
            (s.snowCm ?: 0.0) >= 0.1 || c in 71..77 || c == 85 || c == 86 -> "Snow"
            c == 56 || c == 57 || c == 66 || c == 67 -> "Ice rain"
            else -> "Rain"
        }
    }

    private fun kindEmoji(kind: String): String = when (kind) {
        "Snow" -> "\uD83C\uDF28\uFE0F"
        "Ice rain" -> "\uD83E\uDDCA"
        else -> "\uD83C\uDF27\uFE0F"
    }

    /** When precipitation next starts (or, if it is falling now, when it stops) within the next ~36 hours. */
    fun nextPrecip(upcoming: List<HourSlot>, now: Long): TileOut {
        val none = TileOut("None", "Next rain/snow", empty = true)
        val window = upcoming.take(36)
        val idx = window.indexOfFirst { isWet(it) }
        if (idx < 0) return none
        val first = window[idx]
        val kind = kindOf(first)
        val emoji = kindEmoji(kind)
        if (first.millis <= now + 30 * 60_000L) {
            val dry = window.drop(idx).firstOrNull { !isWet(it) }
                ?: return TileOut("Ongoing", "$kind now", emoji)
            return TileOut(clock(dry.millis), "$kind until", emoji)
        }
        val ahead = first.millis - now
        val value = if (ahead >= 20 * HOUR) "Tmrw ${hourOf(first.millis)}h" else clock(first.millis)
        return TileOut(value, "$kind from", emoji)
    }

    /* ---------------- Daylight gained / lost ---------------- */

    private fun dayLengthMinutes(lat: Double, dayOfYear: Int): Double? {
        val decl = 0.4093 * sin(2 * PI * (dayOfYear - 81) / 365.0)
        val latR = lat * DEG
        val cosH = (sin(-0.833 * DEG) - sin(latR) * sin(decl)) / (cos(latR) * cos(decl))
        if (cosH <= -1.0 || cosH >= 1.0) return null
        return 2.0 * kotlin.math.acos(cosH) / DEG / 15.0 * 60.0
    }

    /** How much longer (or shorter) the day gets compared with the day before, from Open-Meteo's daylight length. */
    fun daylightChange(days: List<DayForecast>, lat: Double, now: Long): TileOut {
        val real = days.filter { !it.isOutlookEstimate }
        val today = real.getOrNull(0)?.daylightHours
        val tomorrow = real.getOrNull(1)?.daylightHours
        val diffMin: Double? = if (today != null && tomorrow != null) {
            (tomorrow - today) * 60.0
        } else {
            val doy = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
            val a = dayLengthMinutes(lat, doy)
            val b = dayLengthMinutes(lat, doy - 1)
            if (a != null && b != null) a - b else null
        }
        if (diffMin == null) return TileOut("--", "Daylight change")
        val totalSec = (abs(diffMin) * 60.0).roundToInt()
        val text = "${totalSec / 60}m ${String.format("%02d", totalSec % 60)}s"
        return when {
            totalSec < 5 -> TileOut("0m", "Daylight steady")
            diffMin > 0 -> TileOut("+$text", "Light gained")
            else -> TileOut("\u2212$text", "Light lost")
        }
    }

    /* ---------------- What to wear today ---------------- */

    /**
     * Clothing icons for the next ~14 hours (so a morning glance covers the day): main layer by the coldest
     * feels-like temperature, plus umbrella, boots, hat, scarf, gloves and sun gear when the weather calls for it.
     */
    fun wearIcons(feelsNow: Double?, upcoming: List<HourSlot>, maxIcons: Int): String {
        if (maxIcons <= 0) return ""
        val window = upcoming.take(14)
        val feel = (window.mapNotNull { it.feelsLike } + listOfNotNull(feelsNow)).minOrNull() ?: return ""
        val maxTemp = window.mapNotNull { it.temp }.maxOrNull() ?: feel
        val rainChance = window.mapNotNull { it.rainChance }.maxOrNull() ?: 0
        val rainMm = window.mapNotNull { it.rainMm }.sum()
        val snowCm = window.mapNotNull { it.snowCm }.sum()
        val uv = window.mapNotNull { it.uv }.maxOrNull() ?: 0.0

        val out = mutableListOf<String>()
        // main layer
        when {
            feel <= 10.0 -> out += "\uD83E\uDDE5"        // coat
            feel <= 16.0 -> out += "\uD83E\uDDE5"        // light jacket
            feel <= 22.0 -> out += "\uD83D\uDC55"        // t-shirt
            else -> { out += "\uD83D\uDC55"; out += "\uD83E\uDE73" } // t-shirt + shorts
        }
        if (rainChance >= 40 || rainMm >= 0.5) out += "\u2614"          // umbrella
        if (snowCm >= 0.5 || (feel <= -10.0)) out += "\uD83E\uDD7E"     // boots
        if (feel <= 4.0) out += "\uD83E\uDDE2"                          // hat / cap
        if (feel <= 4.0) out += "\uD83E\uDDE3"                          // scarf
        if (feel <= -3.0) out += "\uD83E\uDDE4"                         // gloves
        if (uv >= 3.0 && maxTemp > 5.0) out += "\uD83D\uDD76\uFE0F"     // sunglasses
        if (uv >= 6.0) out += "\uD83E\uDDF4"                            // sunscreen
        return out.take(maxIcons).joinToString("")
    }
}
