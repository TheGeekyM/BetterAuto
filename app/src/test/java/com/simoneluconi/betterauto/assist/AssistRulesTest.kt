package com.simoneluconi.betterauto.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AssistRulesTest {
    private val min = 60_000L
    private val hour = 60 * min

    @Test
    fun briefOnlyAfterAPauseOfTenMinutes() {
        assertTrue(BriefRule.due(0L, 1_000_000L))
        assertFalse(BriefRule.due(1_000_000L, 1_000_000L + 9 * min))
        assertTrue(BriefRule.due(1_000_000L, 1_000_000L + 10 * min))
        // The first session dropped before the brief was said: the reconnection says it
        assertTrue(BriefRule.due(1_000_000L, 1_000_000L + 30_000L, owed = true))
    }

    /** A wall clock: 0 is "never" in the stored state. */
    private val t0 = 1_700_000_000_000L

    /** Drives [from]..[to] (ms after [t0]) ticking every 15 s; returns the times (after [t0]) a reminder came. */
    private fun RestTimer.drive(from: Long, to: Long, moving: Boolean = true): List<Long> {
        val out = ArrayList<Long>()
        var t = from
        while (t <= to) {
            if (update(t0 + t, moving)) out += t
            t += 15_000L
        }
        return out
    }

    @Test
    fun restReminderAfterTwoHoursThenEvery45Minutes() {
        val timer = RestTimer()
        val reminders = timer.drive(0L, 4 * hour)
        assertEquals(listOf(2 * hour, 2 * hour + 45 * min, 2 * hour + 90 * min), reminders)
    }

    @Test
    fun theFirstReminderIsTold() {
        val timer = RestTimer()
        timer.drive(0L, 2 * hour)
        assertTrue(timer.firstReminder)
        timer.drive(2 * hour + 15_000L, 2 * hour + 45 * min)
        assertFalse(timer.firstReminder)
    }

    @Test
    fun aShortStopKeepsTheStretchALongOneResetsIt() {
        val timer = RestTimer()
        timer.drive(0L, hour)
        // Ten minutes at a red light and a coffee to go: still the same drive
        timer.drive(hour + 15_000L, hour + 10 * min, moving = false)
        assertEquals(listOf(2 * hour), timer.drive(hour + 10 * min + 15_000L, 2 * hour))
        // A real break
        timer.drive(2 * hour + 15_000L, 2 * hour + 20 * min, moving = false)
        assertEquals(0L, timer.stretchStart)
        val start = 2 * hour + 20 * min + 15_000L
        assertEquals(listOf(start + 2 * hour), timer.drive(start, start + 2 * hour + min))
    }

    @Test
    fun aCarSwitchedOffLongResetsEvenWithNoStandingReading() {
        val timer = RestTimer()
        timer.drive(0L, 90 * min)
        // The session ended: no readings, then the next drive comes an hour later
        assertTrue(timer.drive(150 * min, 150 * min + 100 * min).isEmpty())
        assertEquals(t0 + 150 * min, timer.stretchStart)
    }

    @Test
    fun speedChimeAfterThreeSecondsOverByMoreThanFive() {
        val alert = SpeedAlert()
        assertFalse(alert.update(0L, 56.0, 50.0))
        assertFalse(alert.update(2_000L, 56.0, 50.0))
        assertFalse(alert.over)
        assertTrue(alert.update(3_000L, 56.0, 50.0))
        assertTrue(alert.over)
        // Not again within a minute, though still over
        assertFalse(alert.update(30_000L, 60.0, 50.0))
        assertTrue(alert.over)
        assertTrue(alert.update(63_000L, 60.0, 50.0))
    }

    @Test
    fun speedWithinTheMarginOrUnknownLimitIsSilent() {
        val alert = SpeedAlert()
        for (t in 0L..10_000L step 1_000L) assertFalse(alert.update(t, 55.0, 50.0))
        for (t in 0L..10_000L step 1_000L) assertFalse(alert.update(t, 120.0, null))
        assertFalse(alert.over)
    }

    @Test
    fun aDipUnderTheLimitStartsTheThreeSecondsAgain() {
        val alert = SpeedAlert()
        alert.update(0L, 60.0, 50.0)
        alert.update(2_000L, 60.0, 50.0)
        alert.update(2_500L, 52.0, 50.0)
        assertFalse(alert.update(4_000L, 60.0, 50.0))
        assertTrue(alert.update(7_000L, 60.0, 50.0))
    }

    @Test
    fun fuelWarnsOnceAfterThreeLowReadingsAndAgainAfterRefuelling() {
        val alert = FuelAlert()
        val reserve = FuelReserve.NORMAL
        assertFalse(alert.update(20, null, reserve))
        assertFalse(alert.update(15, null, reserve))
        assertFalse(alert.update(15, null, reserve))
        assertTrue(alert.update(14, null, reserve))
        assertFalse(alert.update(13, null, reserve))
        // A slosh up to 18 % is no refill
        alert.update(18, null, reserve)
        assertFalse((1..5).any { alert.update(12, null, reserve) })
        // Filled up
        alert.update(80, null, reserve)
        repeat(2) { assertFalse(alert.update(10, null, reserve)) }
        assertTrue(alert.update(10, null, reserve))
    }

    @Test
    fun fuelByRangeAloneAndNothingWithoutData() {
        val alert = FuelAlert()
        repeat(10) { assertFalse(alert.update(null, null, FuelReserve.NORMAL)) }
        repeat(2) { assertFalse(alert.update(null, 55, FuelReserve.NORMAL)) }
        assertTrue(alert.update(null, 55, FuelReserve.NORMAL))
    }

    @Test
    fun briefJoinsThePiecesPerLanguage() {
        val ar = Locale.forLanguageTag("ar-EG")
        assertEquals(
            "صباح الخير يا سام، الجو ٢٤ درجة، وأول ميعاد الساعة ١٠، والطريق للشغل ٢٢ دقيقة",
            BriefText.compose("صباح الخير يا سام", listOf("الجو ٢٤ درجة", "أول ميعاد الساعة ١٠", "الطريق للشغل ٢٢ دقيقة"), ar),
        )
        assertEquals(
            "Good morning, Sam. It's 24 degrees outside, your next appointment is at 10:00 AM, and it's 22 minutes to work.",
            BriefText.compose("Good morning, Sam", listOf("it's 24 degrees outside", "your next appointment is at 10:00 AM", "it's 22 minutes to work"), Locale.US),
        )
        assertEquals("Good evening. It's 8 minutes home.", BriefText.compose("Good evening", listOf("it's 8 minutes home"), Locale.US))
        assertEquals(
            "Buongiorno. Fuori ci sono 12 gradi e a casa ci vogliono 9 minuti.",
            BriefText.compose("Buongiorno", listOf("fuori ci sono 12 gradi", "a casa ci vogliono 9 minuti"), Locale.ITALY),
        )
        assertEquals("مساء الخير", BriefText.compose("مساء الخير", emptyList(), ar))
    }

    @Test
    fun timesAsSaidAloud() {
        val ar = Locale.forLanguageTag("ar-EG")
        // Arabic digits or Latin ones, as the JVM's locale data has them
        fun latin(s: String) = s.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")
        assertEquals("10", latin(BriefText.time(10, 0, ar, twelveHour = false)))
        assertEquals("3:30", latin(BriefText.time(15, 30, ar, twelveHour = false)))
        assertEquals("3:05 PM", BriefText.time(15, 5, Locale.US, twelveHour = true))
        assertEquals("15:05", BriefText.time(15, 5, Locale.ITALY, twelveHour = false))
        assertEquals("12:00 AM", BriefText.time(0, 0, Locale.US, twelveHour = true))
    }

    @Test
    fun distancesAsSaidAloud() {
        assertEquals(BriefText.Distance.Metres(400), BriefText.distance(0.42))
        assertEquals(BriefText.Distance.Metres(100), BriefText.distance(0.01))
        assertEquals(BriefText.Distance.Km(2.0), BriefText.distance(1.96))
        assertEquals(BriefText.Distance.Km(1.5), BriefText.distance(1.52))
        assertEquals(BriefText.Distance.Km(12.0), BriefText.distance(12.4))
    }

    // Neutral places around the demo car's Milan point
    private val here = 45.4642 to 9.19
    private val office = TripStart("Office", 45.4800, 9.2200, 0)
    private val gym = TripStart("Gym", 45.4500, 9.1500, 0)

    private fun day(d: Int, h: Int, m: Int = 0) = ((d * 24L + h) * 60 + m) * min

    /** Days 0..4 are weekdays, 5 and 6 the weekend, in this test's calendar. */
    private val minuteOf: (Long) -> Pair<Int, Boolean> = { ms -> ((ms / min) % 1440).toInt() to ((ms / (1440 * min)) % 7 >= 5) }

    @Test
    fun theHabitOfThisTimeOfDayWins() {
        val history = listOf(office.copy(at = day(0, 8)), office.copy(at = day(1, 8, 20)), gym.copy(at = day(2, 18)), gym.copy(at = day(3, 18)))
        val now = day(7, 8, 30)
        val morning = LikelyDestination.pick(history, emptyList(), null, now, 8 * 60 + 30, weekend = false, here = here, minuteOf = minuteOf)
        assertEquals("Office", morning?.name)
        assertEquals(PlaceKind.WORK, morning?.kind)
        val evening = LikelyDestination.pick(history, emptyList(), null, day(7, 18), 18 * 60, weekend = false, here = here, minuteOf = minuteOf)
        assertEquals("Gym", evening?.name)
        assertEquals(PlaceKind.NAMED, evening?.kind)
    }

    @Test
    fun onceIsNoHabitAndWeekendsCountApart() {
        val once = listOf(gym.copy(at = day(2, 18)))
        assertNull(LikelyDestination.pick(once, emptyList(), null, day(7, 18), 18 * 60, weekend = false, here = here, minuteOf = minuteOf))
        val weekdays = listOf(office.copy(at = day(0, 8)), office.copy(at = day(1, 8)))
        assertNull(LikelyDestination.pick(weekdays, emptyList(), null, day(12, 8), 8 * 60, weekend = true, here = here, minuteOf = minuteOf))
    }

    @Test
    fun withNoHabitWorkInTheMorningHomeLater() {
        val recents = listOf(Triple("Lavoro", 45.48, 9.22), Triple("Casa", 45.44, 9.16))
        val morning = LikelyDestination.pick(emptyList(), recents, null, day(1, 7), 7 * 60, weekend = false, here = here, minuteOf = minuteOf)
        assertEquals(PlaceKind.WORK, morning?.kind)
        val evening = LikelyDestination.pick(emptyList(), recents, null, day(1, 19), 19 * 60, weekend = false, here = here, minuteOf = minuteOf)
        assertEquals(PlaceKind.HOME, evening?.kind)
        assertNull(LikelyDestination.pick(emptyList(), recents, null, day(1, 13), 13 * 60, weekend = false, here = here, minuteOf = minuteOf))
        // The smart home's place counts as home
        val linked = LikelyDestination.pick(emptyList(), emptyList(), 45.44 to 9.16, day(1, 19), 19 * 60, weekend = false, here = here, minuteOf = minuteOf)
        assertEquals(PlaceKind.HOME, linked?.kind)
    }

    @Test
    fun neverWhereTheCarAlreadyIs() {
        val history = listOf(office.copy(at = day(0, 8)), office.copy(at = day(1, 8)))
        val atOffice = 45.4801 to 9.2201
        assertNull(LikelyDestination.pick(history, emptyList(), null, day(7, 8), 8 * 60, weekend = false, here = atOffice, minuteOf = minuteOf))
    }

    @Test
    fun placeKindsByNameInEveryLanguage() {
        assertEquals(PlaceKind.WORK, LikelyDestination.kindOf("الشغل", 0.0, 0.0, null))
        assertEquals(PlaceKind.HOME, LikelyDestination.kindOf("البيت", 0.0, 0.0, null))
        assertEquals(PlaceKind.WORK, LikelyDestination.kindOf("Ufficio Centrale", 0.0, 0.0, null))
        assertEquals(PlaceKind.NAMED, LikelyDestination.kindOf("Homebase Pizza", 0.0, 0.0, null))
    }

    @Test
    fun storedTripsAndParkingReadBack() {
        val trips = listOf(TripStart("Office", 45.48, 9.22, 123L))
        assertEquals(trips, AssistStore.decodeTrips(AssistStore.encodeTrips(trips)))
        val car = ParkedCar(45.46, 9.19, 456L, "Via Esempio")
        assertEquals(car, AssistStore.decodeParked(AssistStore.encodeParked(car)))
        assertEquals(car.copy(road = null), AssistStore.decodeParked(AssistStore.encodeParked(car.copy(road = null))))
        assertNull(AssistStore.decodeParked("{broken"))
    }
}
