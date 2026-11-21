package com.simoneluconi.betterauto.prayer

import androidx.annotation.StringRes
import com.simoneluconi.betterauto.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/** The day's times, in order; [SUNRISE] is no prayer (Fajr ends there), shown and never announced. */
enum class Prayer {
    FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA;

    val isPrayer: Boolean get() = this != SUNRISE

    @get:StringRes
    val label: Int get() = when (this) {
        FAJR -> R.string.prayer_fajr
        SUNRISE -> R.string.prayer_sunrise
        DHUHR -> R.string.prayer_dhuhr
        ASR -> R.string.prayer_asr
        MAGHRIB -> R.string.prayer_maghrib
        ISHA -> R.string.prayer_isha
    }
}

/**
 * How Fajr and Isha are found: the sun's depression below the horizon in degrees, or (Umm al-Qura) Isha a fixed
 * time after Maghrib. The values are the authorities' own, as tabulated by PrayTimes.org.
 */
enum class PrayerMethod(val fajrAngle: Double, val ishaAngle: Double?, val ishaMinutes: Int?) {
    /** Egyptian General Authority of Survey. */
    EGYPTIAN(19.5, 17.5, null),
    /** Umm al-Qura, Makkah: Isha 90 minutes after Maghrib, 120 in Ramadan. */
    UMM_AL_QURA(18.5, null, 90),
    /** Muslim World League. */
    MWL(18.0, 17.0, null),
    /** Islamic Society of North America. */
    ISNA(15.0, 15.0, null),
    /** University of Islamic Sciences, Karachi. */
    KARACHI(18.0, 18.0, null),
}

/** Asr begins when a shadow is [shadow] times the object's length (plus its noon shadow). */
enum class AsrJuristic(val shadow: Int) {
    /** Shafi'i, Maliki, Hanbali. */
    SHAFII(1),
    HANAFI(2),
}

/** Where to compute for when neither the car nor the phone knows where it is. */
enum class PrayerCity(val latitude: Double, val longitude: Double, @param:StringRes val label: Int) {
    CAIRO(30.0444, 31.2357, R.string.city_cairo),
    ALEXANDRIA(31.2001, 29.9187, R.string.city_alexandria),
    MECCA(21.4225, 39.8262, R.string.city_mecca),
    MEDINA(24.4672, 39.6111, R.string.city_medina),
    RIYADH(24.7136, 46.6753, R.string.city_riyadh),
    DUBAI(25.2048, 55.2708, R.string.city_dubai),
    ROME(41.9028, 12.4964, R.string.city_rome),
    MILAN(45.4642, 9.1900, R.string.city_milan),
}

/** One day's times; a time is missing when the sun never gets there (polar day or night). */
class DayTimes(val date: LocalDate, val times: Map<Prayer, ZonedDateTime>) {
    operator fun get(prayer: Prayer): ZonedDateTime? = times[prayer]
}

/**
 * Prayer times computed on the phone, offline: the usual astronomical method (as described by PrayTimes.org,
 * written here from the published formulas). The sun's position comes from the US Naval Observatory's low-precision
 * formulas (good to about a minute for times this century); everything is worked out in UT hours of [LocalDate]'s
 * solar day at the place, then put in the given time zone, so a daylight-saving day comes out right.
 *
 * Where the sun doesn't go deep enough for Fajr or Isha (high latitudes in summer) they are set by the
 * angle-based rule: that angle's share (angle / 60) of the night, before sunrise and after sunset.
 */
object PrayerTimes {
    /** Sunrise and sunset: the sun's upper edge on the horizon, refraction included. */
    private const val HORIZON = 0.833

    fun day(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId, method: PrayerMethod, asr: AsrJuristic): DayTimes {
        val jd0 = date.toEpochDay() + 2440587.5
        val lonHours = longitude / 15.0
        // Guesses in local solar hours, refined twice (each pass takes the sun where the last one put the time)
        var fajr = 5.0 - lonHours
        var sunrise = 6.0 - lonHours
        var dhuhr = 12.0 - lonHours
        var asrTime = 13.0 - lonHours
        var sunset = 18.0 - lonHours
        var isha = 18.0 - lonHours
        repeat(2) {
            fajr = sunAngleTime(jd0, fajr.or(5.0 - lonHours), method.fajrAngle, latitude, longitude, before = true)
            sunrise = sunAngleTime(jd0, sunrise.or(6.0 - lonHours), HORIZON, latitude, longitude, before = true)
            dhuhr = noon(jd0, dhuhr, longitude)
            asrTime = asrTime(jd0, asrTime.or(13.0 - lonHours), asr.shadow, latitude, longitude)
            sunset = sunAngleTime(jd0, sunset.or(18.0 - lonHours), HORIZON, latitude, longitude, before = false)
            isha = method.ishaAngle?.let { sunAngleTime(jd0, isha.or(18.0 - lonHours), it, latitude, longitude, before = false) } ?: Double.NaN
        }
        if (method.ishaMinutes != null) {
            val ramadan = runCatching { HijrahDate.from(date).get(ChronoField.MONTH_OF_YEAR) == 9 }.getOrDefault(false)
            isha = sunset + (if (ramadan) 120 else method.ishaMinutes) / 60.0
        }
        // High latitudes: no deeper than the angle's share of the night
        if (!sunrise.isNaN() && !sunset.isNaN()) {
            val night = 24.0 - (sunset - sunrise)
            val fajrPortion = method.fajrAngle / 60.0 * night
            if (fajr.isNaN() || sunrise - fajr > fajrPortion) fajr = sunrise - fajrPortion
            method.ishaAngle?.let { angle ->
                val ishaPortion = angle / 60.0 * night
                if (isha.isNaN() || isha - sunset > ishaPortion) isha = sunset + ishaPortion
            }
        } else {
            fajr = Double.NaN
            isha = Double.NaN
        }
        val hours = mapOf(
            Prayer.FAJR to fajr, Prayer.SUNRISE to sunrise, Prayer.DHUHR to dhuhr,
            Prayer.ASR to asrTime, Prayer.MAGHRIB to sunset, Prayer.ISHA to isha,
        )
        val midnight = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val times = hours.filterValues { !it.isNaN() }.mapValues { (_, h) ->
            // To the nearest minute, as timetables print them
            val minutes = (h * 60.0).roundToLong()
            Instant.ofEpochSecond(midnight.epochSecond + minutes * 60).atZone(zone)
        }
        return DayTimes(date, times)
    }

    /**
     * The next prayer after [now] (Sunrise left out), and its time: looked for from yesterday (an Isha past
     * midnight in a northern summer) to tomorrow. [times] gives a day's times.
     */
    fun next(now: ZonedDateTime, times: (LocalDate) -> DayTimes): Pair<Prayer, ZonedDateTime>? =
        around(now, times).firstOrNull { it.second.isAfter(now) }

    /** The latest prayer at or before [now], and its time. */
    fun last(now: ZonedDateTime, times: (LocalDate) -> DayTimes): Pair<Prayer, ZonedDateTime>? =
        around(now, times).lastOrNull { !it.second.isAfter(now) }

    private fun around(now: ZonedDateTime, times: (LocalDate) -> DayTimes): List<Pair<Prayer, ZonedDateTime>> {
        val today = now.toLocalDate()
        return listOf(today.minusDays(1), today, today.plusDays(1)).flatMap { date ->
            times(date).times.filterKeys { it.isPrayer }.map { (p, t) -> p to t }
        }.sortedBy { it.second }
    }

    /** UT hours of the sun's transit, near [guess]. */
    private fun noon(jd0: Double, guess: Double, longitude: Double): Double =
        12.0 - longitude / 15.0 - equationOfTime(jd0 + guess / 24.0)

    /** UT hours when the sun is [angle] degrees below the horizon, before or after noon; NaN when it never is. */
    private fun sunAngleTime(jd0: Double, guess: Double, angle: Double, latitude: Double, longitude: Double, before: Boolean): Double {
        val jd = jd0 + guess / 24.0
        val decl = declination(jd)
        val transit = 12.0 - longitude / 15.0 - equationOfTime(jd)
        val lat = Math.toRadians(latitude)
        val cosH = (-sin(Math.toRadians(angle)) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat))
        if (cosH < -1.0 || cosH > 1.0) return Double.NaN
        val t = Math.toDegrees(acos(cosH)) / 15.0
        return if (before) transit - t else transit + t
    }

    private fun asrTime(jd0: Double, guess: Double, shadow: Int, latitude: Double, longitude: Double): Double {
        val decl = Math.toDegrees(declination(jd0 + guess / 24.0))
        // The sun's altitude when the shadow is [shadow] lengths plus the noon one: acot(shadow + tan|lat - decl|)
        val altitude = Math.toDegrees(atan(1.0 / (shadow + tan(Math.toRadians(abs(latitude - decl))))))
        return sunAngleTime(jd0, guess, -altitude, latitude, longitude, before = false)
    }

    /** The sun's declination in radians at Julian day [jd]. */
    internal fun declination(jd: Double): Double = sun(jd).first

    /** Apparent minus mean solar time, in hours. */
    internal fun equationOfTime(jd: Double): Double = sun(jd).second

    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = Math.toRadians(fixAngle(357.529 + 0.98560028 * d))
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = Math.toRadians(fixAngle(q + 1.915 * sin(g) + 0.020 * sin(2 * g)))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = fixHour(Math.toDegrees(atan2(cos(e) * sin(l), cos(l))) / 15.0)
        var eqt = q / 15.0 - ra
        // Both in [0, 24): their difference may have wrapped
        if (eqt > 12) eqt -= 24
        if (eqt < -12) eqt += 24
        return asin(sin(e) * sin(l)) to eqt
    }

    private fun fixAngle(a: Double) = ((a % 360.0) + 360.0) % 360.0
    private fun fixHour(h: Double) = ((h % 24.0) + 24.0) % 24.0
    private fun Double.or(fallback: Double) = if (isNaN()) fallback else this
}
