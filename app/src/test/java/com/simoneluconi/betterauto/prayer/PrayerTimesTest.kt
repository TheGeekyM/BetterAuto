package com.simoneluconi.betterauto.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Reference times: the Egyptian General Authority of Survey method (Fajr 19.5°, Isha 17.5°) as computed by
 * api.aladhan.com for Cairo (30.0444, 31.2357); Oslo with the Muslim World League method and its angle-based
 * high-latitude rule. Within 2 minutes.
 */
class PrayerTimesTest {
    private val cairoLat = 30.0444
    private val cairoLon = 31.2357

    private fun assertAbout(expected: String, actual: ZonedDateTime?, what: String, tolerance: Long = 2) {
        assertNotNull("$what missing", actual)
        val diff = Duration.between(LocalTime.parse(expected), actual!!.toLocalTime()).toMinutes()
        assertTrue("$what: expected $expected, got ${actual.toLocalTime()}", abs(diff) <= tolerance)
    }

    private fun abs(x: Long) = if (x < 0) -x else x

    @Test
    fun cairoEgyptianInMarch() {
        val day = PrayerTimes.day(LocalDate.of(2026, 3, 15), cairoLat, cairoLon, ZoneId.of("Africa/Cairo"), PrayerMethod.EGYPTIAN, AsrJuristic.SHAFII)
        assertAbout("04:39", day[Prayer.FAJR], "Fajr")
        assertAbout("06:05", day[Prayer.SUNRISE], "Sunrise")
        assertAbout("12:04", day[Prayer.DHUHR], "Dhuhr")
        assertAbout("15:28", day[Prayer.ASR], "Asr")
        assertAbout("18:03", day[Prayer.MAGHRIB], "Maghrib")
        assertAbout("19:20", day[Prayer.ISHA], "Isha")
    }

    @Test
    fun cairoInSummerTime() {
        // Egypt's daylight saving (UTC+3) given explicitly: the JDK's own zone data may predate its return in 2023
        val day = PrayerTimes.day(LocalDate.of(2026, 7, 1), cairoLat, cairoLon, ZoneOffset.ofHours(3), PrayerMethod.EGYPTIAN, AsrJuristic.SHAFII)
        assertAbout("04:12", day[Prayer.FAJR], "Fajr")
        assertAbout("05:57", day[Prayer.SUNRISE], "Sunrise")
        assertAbout("12:59", day[Prayer.DHUHR], "Dhuhr")
        assertAbout("16:35", day[Prayer.ASR], "Asr")
        assertAbout("20:00", day[Prayer.MAGHRIB], "Maghrib")
        assertAbout("21:33", day[Prayer.ISHA], "Isha")
    }

    @Test
    fun hanafiAsrIsLater() {
        val date = LocalDate.of(2026, 3, 15)
        val zone = ZoneId.of("Africa/Cairo")
        val shafii = PrayerTimes.day(date, cairoLat, cairoLon, zone, PrayerMethod.EGYPTIAN, AsrJuristic.SHAFII)
        val hanafi = PrayerTimes.day(date, cairoLat, cairoLon, zone, PrayerMethod.EGYPTIAN, AsrJuristic.HANAFI)
        assertAbout("16:21", hanafi[Prayer.ASR], "Hanafi Asr")
        assertTrue(hanafi[Prayer.ASR]!!.isAfter(shafii[Prayer.ASR]))
        // Nothing else changes
        for (p in Prayer.entries.filter { it != Prayer.ASR }) assertEquals(p.name, shafii[p], hanafi[p])
    }

    @Test
    fun ummAlQuraIshaIsAfterMaghrib() {
        val zone = ZoneOffset.ofHours(3)
        val plain = PrayerTimes.day(LocalDate.of(2026, 1, 10), 21.4225, 39.8262, zone, PrayerMethod.UMM_AL_QURA, AsrJuristic.SHAFII)
        assertEquals(90, Duration.between(plain[Prayer.MAGHRIB], plain[Prayer.ISHA]).toMinutes())
        // 1 Ramadan 1447 is about 18 February 2026
        val ramadan = PrayerTimes.day(LocalDate.of(2026, 3, 1), 21.4225, 39.8262, zone, PrayerMethod.UMM_AL_QURA, AsrJuristic.SHAFII)
        assertEquals(120, Duration.between(ramadan[Prayer.MAGHRIB], ramadan[Prayer.ISHA]).toMinutes())
    }

    @Test
    fun highLatitudeSummerStaysInOrder() {
        // Oslo at midsummer: the sun never gets 18° below the horizon
        val day = PrayerTimes.day(LocalDate.of(2026, 6, 21), 59.9139, 10.7522, ZoneOffset.ofHours(2), PrayerMethod.MWL, AsrJuristic.SHAFII)
        val order = Prayer.entries.map { assertNotNull(it.name, day[it]); day[it]!! }
        assertEquals(order.sorted(), order)
        assertAbout("02:21", day[Prayer.FAJR], "Fajr")
        assertAbout("03:54", day[Prayer.SUNRISE], "Sunrise")
        assertAbout("13:19", day[Prayer.DHUHR], "Dhuhr")
        assertAbout("22:44", day[Prayer.MAGHRIB], "Maghrib")
        // Past midnight
        assertAbout("00:12", day[Prayer.ISHA], "Isha")
        assertEquals(LocalDate.of(2026, 6, 22), day[Prayer.ISHA]!!.toLocalDate())
        // The Egyptian angles are deeper still: the same rule keeps them in order
        val egyptian = PrayerTimes.day(LocalDate.of(2026, 6, 21), 59.9139, 10.7522, ZoneOffset.ofHours(2), PrayerMethod.EGYPTIAN, AsrJuristic.HANAFI)
        val times = Prayer.entries.map { egyptian[it]!! }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun polarDayHasNoSunsetAndNoCrash() {
        // Tromsø at midsummer: the sun never sets
        val day = PrayerTimes.day(LocalDate.of(2026, 6, 21), 69.6492, 18.9553, ZoneOffset.ofHours(2), PrayerMethod.EGYPTIAN, AsrJuristic.SHAFII)
        assertNull(day[Prayer.SUNRISE])
        assertNull(day[Prayer.MAGHRIB])
        assertNull(day[Prayer.FAJR])
        assertNull(day[Prayer.ISHA])
        assertNotNull(day[Prayer.DHUHR])
    }

    @Test
    fun chimeIsShortSoftAndEndsInSilence() {
        val rate = 48_000
        val chime = azanChime(rate)
        assertEquals(rate * 420 / 1000 * 3, chime.size)
        assertTrue(chime.all { it in -0.4f..0.4f })
        assertTrue(abs((chime.last() * 1000).toLong()) <= 1)
    }

    @Test
    fun nextPrayerAfterIshaIsTomorrowsFajr() {
        val zone = ZoneId.of("Africa/Cairo")
        val times = { d: LocalDate -> PrayerTimes.day(d, cairoLat, cairoLon, zone, PrayerMethod.EGYPTIAN, AsrJuristic.SHAFII) }
        val late = ZonedDateTime.of(2026, 3, 15, 23, 0, 0, 0, zone)
        val (prayer, at) = PrayerTimes.next(late, times)!!
        assertEquals(Prayer.FAJR, prayer)
        assertEquals(LocalDate.of(2026, 3, 16), at.toLocalDate())
        // Sunrise is never "next"
        val dawn = ZonedDateTime.of(2026, 3, 15, 5, 0, 0, 0, zone)
        assertEquals(Prayer.DHUHR, PrayerTimes.next(dawn, times)!!.first)
        assertEquals(Prayer.FAJR, PrayerTimes.last(dawn, times)!!.first)
    }
}
