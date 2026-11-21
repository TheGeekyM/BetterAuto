package com.simoneluconi.betterauto.assist

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * When the driving assistant speaks: the timers, thresholds and debounces behind each of its lines. Pure (every
 * clock is passed in, wall-clock ms), so the rules are tested without a car.
 */

/** How low the fuel may go before the assistant offers a station: a share of the tank, or the range left. */
enum class FuelReserve(val percent: Int, val rangeKm: Int) { LOW(10, 40), NORMAL(15, 60), HIGH(25, 100) }

/** The arrival brief: only at the start of a drive, not when the link comes back after a short drop. */
object BriefRule {
    const val QUICK_RECONNECT_MS = 10 * 60_000L

    /**
     * [lastEndAt]: when the last session ended (0: never); [owed]: that session was due one but ended before it was
     * said (a wireless dongle drops the first session while it sets up): the next one says it.
     */
    fun due(lastEndAt: Long, now: Long, owed: Boolean = false): Boolean = owed || lastEndAt <= 0L || now - lastEndAt >= QUICK_RECONNECT_MS
}

/**
 * Continuous driving: a stretch starts with the first move and goes on through short stops; a stop of [RESET_MS]
 * or more (the car parked, or switched off and back) starts a new one. The first reminder comes at [FIRST_MS],
 * the next ones every [AGAIN_MS]. The state is three times, kept on the phone so a switched-off car or a dropped
 * link does not lose the stretch.
 */
class RestTimer(
    var stretchStart: Long = 0L,
    var lastMovingAt: Long = 0L,
    var lastReminderAt: Long = 0L,
) {
    /** True when a reminder is due now (it then counts as given). [moving]: as far as the car or the GPS says. */
    fun update(now: Long, moving: Boolean): Boolean {
        if (!moving) {
            // Parked long enough: the stretch is over, the next move starts a new one
            if (stretchStart != 0L && now - lastMovingAt >= RESET_MS) reset()
            return false
        }
        if (stretchStart == 0L || now - lastMovingAt >= RESET_MS) {
            stretchStart = now
            lastReminderAt = 0L
        }
        lastMovingAt = now
        val due = if (lastReminderAt == 0L) now - stretchStart >= FIRST_MS else now - lastReminderAt >= AGAIN_MS
        if (due) lastReminderAt = now
        return due
    }

    fun reset() {
        stretchStart = 0L
        lastMovingAt = 0L
        lastReminderAt = 0L
    }

    /** The reminder just given was the first of this stretch. */
    val firstReminder: Boolean get() = lastReminderAt != 0L && lastReminderAt - stretchStart < FIRST_MS + AGAIN_MS

    companion object {
        const val FIRST_MS = 2 * 60 * 60_000L
        const val AGAIN_MS = 45 * 60_000L
        const val RESET_MS = 15 * 60_000L
    }
}

/**
 * Over the road's limit: by more than [MARGIN_KMH] for [HOLD_MS] in a row, a chime, then at most one every
 * [REPEAT_MS]. Only with a known limit: an unknown one says nothing and breaks the run.
 */
class SpeedAlert {
    private var overSince: Long? = null
    private var lastChimeAt: Long? = null

    /** Over the limit right now (the sign pulses), held for [HOLD_MS] first so a moment's overshoot doesn't flash. */
    var over = false
        private set

    /** True when the chime is due now. */
    fun update(now: Long, speedKmh: Double?, limitKmh: Double?): Boolean {
        if (speedKmh == null || limitKmh == null || limitKmh <= 0 || speedKmh <= limitKmh + MARGIN_KMH) {
            overSince = null
            over = false
            return false
        }
        val since = overSince ?: now.also { overSince = it }
        if (now - since < HOLD_MS) return false
        over = true
        lastChimeAt?.let { if (now - it < REPEAT_MS) return false }
        lastChimeAt = now
        return true
    }

    companion object {
        const val MARGIN_KMH = 5.0
        const val HOLD_MS = 3_000L
        const val REPEAT_MS = 60_000L
    }
}

/**
 * The fuel getting low: below the reserve (either the level or the range the car gives) on [CONFIRM_READINGS]
 * readings in a row (a tank sloshing in a bend reads low for a moment), said once; again only after the tank was
 * filled (the level [REFILL_PERCENT] points or the range [REFILL_KM] above the reserve).
 */
class FuelAlert {
    private var lowReadings = 0
    var warned = false
        private set

    fun update(levelPercent: Int?, rangeKm: Int?, reserve: FuelReserve): Boolean {
        if (levelPercent == null && rangeKm == null) return false
        val low = (levelPercent != null && levelPercent <= reserve.percent) || (rangeKm != null && rangeKm <= reserve.rangeKm)
        val refilled = (levelPercent != null && levelPercent >= reserve.percent + REFILL_PERCENT) ||
            (rangeKm != null && rangeKm >= reserve.rangeKm + REFILL_KM)
        if (!low) {
            lowReadings = 0
            if (refilled) warned = false
            return false
        }
        if (warned) return false
        if (++lowReadings < CONFIRM_READINGS) return false
        warned = true
        return true
    }

    companion object {
        const val CONFIRM_READINGS = 3
        const val REFILL_PERCENT = 10
        const val REFILL_KM = 80
    }
}

/** How the brief joins its pieces, per language: "صباح الخير، الجو ٢٤ درجة، وأول ميعاد…" / "Good morning. It's…, and…". */
object BriefText {
    fun compose(greeting: String, facts: List<String>, locale: Locale): String {
        if (facts.isEmpty()) return greeting
        return when (locale.language) {
            // Every fact after the first takes "و"
            "ar" -> greeting + "، " + facts.joinToString("، و")
            else -> {
                val and = if (locale.language == "it") " e " else if (facts.size > 2) ", and " else " and "
                val joined = if (facts.size == 1) facts[0] else facts.dropLast(1).joinToString(", ") + and + facts.last()
                greeting + ". " + joined.replaceFirstChar { it.titlecase(locale) } + "."
            }
        }
    }

    /**
     * A time as said aloud: "10" or "10:30" in Arabic (Egyptians say the twelve-hour time), "10:30 AM" in English
     * with a twelve-hour phone, "10:30" otherwise.
     */
    fun time(hour: Int, minute: Int, locale: Locale, twelveHour: Boolean): String = when {
        locale.language == "ar" -> {
            val h = (hour + 11) % 12 + 1
            if (minute == 0) String.format(locale, "%d", h) else String.format(locale, "%d:%02d", h, minute)
        }
        twelveHour -> String.format(locale, "%d:%02d %s", (hour + 11) % 12 + 1, minute, if (hour < 12) "AM" else "PM")
        else -> String.format(locale, "%d:%02d", hour, minute)
    }

    /** A distance as said aloud: metres in hundreds below a kilometre, a decimal below 10 km, whole beyond. */
    fun distance(km: Double): Distance = when {
        km < 0.95 -> Distance.Metres((km * 10).roundToInt().coerceAtLeast(1) * 100)
        km < 9.95 -> Distance.Km((km * 10).roundToInt() / 10.0)
        else -> Distance.Km(km.roundToInt().toDouble())
    }

    sealed interface Distance {
        data class Metres(val value: Int) : Distance
        data class Km(val value: Double) : Distance
    }
}

/** Where the car was driven to, and when the trip was set: the habit the brief guesses tonight's destination from. */
data class TripStart(val name: String, val latitude: Double, val longitude: Double, val at: Long)

/** What a likely destination is to the driver: work, home, or a place by its name. */
enum class PlaceKind { WORK, HOME, NAMED }

data class LikelyPlace(val name: String, val latitude: Double, val longitude: Double, val kind: PlaceKind)

/**
 * Where the driver probably goes now: the place most often set at about this time of day (two times at least,
 * weekdays and weekends apart), else a recent place called work in the morning or home later on. Never where the
 * car already is.
 */
object LikelyDestination {
    private const val WINDOW_MIN = 90
    private const val MIN_TIMES = 2
    private const val SAME_PLACE_M = 150.0
    private const val HERE_M = 1_000.0
    private const val HISTORY_DAYS = 60

    private val WorkWords = setOf("work", "office", "lavoro", "ufficio", "شغل", "الشغل", "المكتب", "مكتب")
    private val HomeWords = setOf("home", "casa", "بيت", "البيت", "المنزل")

    /**
     * [hourMinute]/[weekend] describe now; [history] the trips set before; [recents] the recent places (name, lat,
     * lon); [home] the smart home's place, when linked; [here] where the car is, when known.
     */
    fun pick(
        history: List<TripStart>,
        recents: List<Triple<String, Double, Double>>,
        home: Pair<Double, Double>?,
        now: Long,
        minuteOfDay: Int,
        weekend: Boolean,
        here: Pair<Double, Double>?,
        minuteOf: (Long) -> Pair<Int, Boolean>,
    ): LikelyPlace? {
        fun away(lat: Double, lon: Double) = here == null || metres(here.first, here.second, lat, lon) > HERE_M
        val recent = history.filter { now - it.at in 0..HISTORY_DAYS * 86_400_000L }
            .filter { val (m, w) = minuteOf(it.at); w == weekend && circularGap(m, minuteOfDay) <= WINDOW_MIN }
        // Grouped by place: the same address set under two names is one habit
        val groups = ArrayList<MutableList<TripStart>>()
        for (t in recent) {
            val group = groups.firstOrNull { g -> metres(g[0].latitude, g[0].longitude, t.latitude, t.longitude) < SAME_PLACE_M }
            if (group != null) group += t else groups += mutableListOf(t)
        }
        groups.filter { it.size >= MIN_TIMES && away(it[0].latitude, it[0].longitude) }
            .maxWithOrNull(compareBy<List<TripStart>> { it.size }.thenBy { g -> g.maxOf { it.at } })
            ?.let { g ->
                val last = g.maxBy { it.at }
                return LikelyPlace(last.name, last.latitude, last.longitude, kindOf(last.name, last.latitude, last.longitude, home))
            }
        // No habit yet: work on weekday mornings, home from the afternoon
        val hour = minuteOfDay / 60
        val wanted = when {
            !weekend && hour in 5..10 -> PlaceKind.WORK
            hour in 15..23 -> PlaceKind.HOME
            else -> return null
        }
        recents.firstOrNull { (name, lat, lon) -> kindOf(name, lat, lon, home) == wanted && away(lat, lon) }
            ?.let { (name, lat, lon) -> return LikelyPlace(name, lat, lon, wanted) }
        if (wanted == PlaceKind.HOME && home != null && away(home.first, home.second)) {
            return LikelyPlace("home", home.first, home.second, PlaceKind.HOME)
        }
        return null
    }

    fun kindOf(name: String, lat: Double, lon: Double, home: Pair<Double, Double>?): PlaceKind {
        val words = name.lowercase(Locale.ROOT).split(' ', ',', '-', '.').filter { it.isNotBlank() }
        return when {
            words.any { it in WorkWords } -> PlaceKind.WORK
            words.any { it in HomeWords } -> PlaceKind.HOME
            home != null && metres(home.first, home.second, lat, lon) < SAME_PLACE_M -> PlaceKind.HOME
            else -> PlaceKind.NAMED
        }
    }

    private fun circularGap(a: Int, b: Int): Int = abs(a - b).let { minOf(it, 1440 - it) }

    internal fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * 6_371_000.0
    }
}
