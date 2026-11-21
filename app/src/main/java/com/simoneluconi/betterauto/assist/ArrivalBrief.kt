package com.simoneluconi.betterauto.assist

import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.home.Agenda
import com.simoneluconi.betterauto.home.AgendaDay
import com.simoneluconi.betterauto.home.CalendarEvents
import com.simoneluconi.betterauto.map.MapFix
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.MapSource
import com.simoneluconi.betterauto.map.nav.Destination
import com.simoneluconi.betterauto.map.nav.Destinations
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.phone.PhoneCountry
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.car.TemperatureUnit
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.voice.cloud.Persona
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * The few words at the start of a drive: a greeting for the time of day with the driver's name, the temperature
 * outside, the next appointment today and, when the driver probably drives to work or home now, how long the road
 * takes. Each piece only when it is known; the weather comes from the car's own outside temperature (the phone's
 * weather widget is another app's picture, with no values to read).
 */
internal object ArrivalBrief {
    private const val FIX_WAIT_MS = 10_000L
    /** The road's time is worth a late greeting only this much. */
    private const val ROUTE_WAIT_MS = 8_000L
    private const val FIX_FRESH_MS = 30_000L
    private const val MAX_ETA_MIN = 120

    /** The text to say, put together in the voice language. [sessionStart]: wall clock, car values older are stale. */
    suspend fun compose(context: Context, sessionStart: Long): String {
        val res = AppLocales.voiceContext(context).resources
        val locale = AppLocales.voice(context)
        val settings = LauncherSettings.get(context)
        val now = ZonedDateTime.now()
        val who = Persona.callName(settings.driverName.value, settings.nicknames.value)
        val greeting = when (Persona.dayPart(now.hour)) {
            Persona.DayPart.MORNING -> if (who != null) res.getString(R.string.assist_brief_morning_named, who) else res.getString(R.string.assist_brief_morning)
            Persona.DayPart.EVENING -> if (who != null) res.getString(R.string.assist_brief_evening_named, who) else res.getString(R.string.assist_brief_evening)
            Persona.DayPart.NIGHT -> if (who != null) res.getString(R.string.assist_brief_night_named, who) else res.getString(R.string.assist_brief_night)
        }
        val facts = ArrayList<String>()
        // Outside temperature, only as the car says it in this session
        VehicleData.snapshot.value.environment?.takeIf { it.at >= sessionStart }?.value?.temperatureC?.let { c ->
            val f = when (settings.temperatureUnit.value) {
                TemperatureUnit.FAHRENHEIT -> true
                TemperatureUnit.CELSIUS -> false
                TemperatureUnit.AUTO -> PhoneCountry.of(context) == "US"
            }
            facts += res.getString(R.string.assist_brief_temperature, (if (f) c * 9 / 5 + 32 else c).roundToInt())
        }
        nextEvent(context, now)?.let { begin ->
            val at = Instant.ofEpochMilli(begin).atZone(now.zone)
            facts += res.getString(R.string.assist_brief_event, BriefText.time(at.hour, at.minute, locale, !DateFormat.is24HourFormat(context)))
        }
        // A trip already set is the navigation's to announce
        if (Trip.places.value.isEmpty()) eta(context, now)?.let { (place, minutes) ->
            facts += when (place.kind) {
                PlaceKind.WORK -> res.getString(R.string.assist_brief_eta_work, minutes)
                PlaceKind.HOME -> res.getString(R.string.assist_brief_eta_home, minutes)
                PlaceKind.NAMED -> res.getString(R.string.assist_brief_eta_place, place.name, minutes)
            }
        }
        EventLog.log("Assist: brief with ${facts.size} piece(s)")
        return BriefText.compose(greeting, facts, locale)
    }

    /** The start of the next timed appointment still ahead today; null when none (or no calendar permission). */
    private suspend fun nextEvent(context: Context, now: ZonedDateTime): Long? {
        val instances = CalendarEvents.demo.value ?: run {
            if (!CalendarEvents.canRead(context)) return null
            val (from, to) = Agenda.queryWindow(now)
            CalendarEvents.instances(context, from, to, LauncherSettings.get(context).hiddenCalendars.value)
        }
        val nowMs = now.toInstant().toEpochMilli()
        return Agenda.select(instances, now)
            .firstOrNull { it.day == AgendaDay.TODAY && !it.instance.allDay && it.instance.begin > nowMs }
            ?.instance?.begin
    }

    /** Where the driver probably goes now and how many minutes it takes, with today's traffic where the route has it. */
    private suspend fun eta(context: Context, now: ZonedDateTime): Pair<LikelyPlace, Int>? {
        val fix = withTimeoutOrNull(FIX_WAIT_MS) {
            var found: MapFix? = null
            while (found == null) {
                found = MapLocation.fix.value?.takeIf { it.source != MapSource.ESTIMATED && SystemClock.elapsedRealtime() - it.at < FIX_FRESH_MS }
                if (found == null) delay(1_000)
            }
            found
        } ?: return null
        Destinations.init(context)
        val country = PhoneCountry.of(context)
        val home = SmartHome.linked.value.firstNotNullOfOrNull { it.catalog.value?.home }?.let { it.latitude to it.longitude }
        val place = LikelyDestination.pick(
            history = AssistStore.tripStarts(context),
            recents = Destinations.recents.value.map { Triple(it.name, it.latitude, it.longitude) },
            home = home,
            now = now.toInstant().toEpochMilli(),
            minuteOfDay = now.hour * 60 + now.minute,
            weekend = weekend(now, country),
            here = fix.latitude to fix.longitude,
            minuteOf = { ms -> Instant.ofEpochMilli(ms).atZone(now.zone).let { it.hour * 60 + it.minute to weekend(it, country) } },
        ) ?: return null
        val seconds = withTimeoutOrNull(ROUTE_WAIT_MS) {
            Navigation.eta(context, fix, Destination(place.name, null, place.latitude, place.longitude))
        } ?: return null
        val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (minutes > MAX_ETA_MIN) null else place to minutes
    }

    /** Friday and Saturday in Egypt and much of the Arab world, Saturday and Sunday elsewhere. */
    private fun weekend(t: ZonedDateTime, country: String): Boolean {
        val day = t.dayOfWeek.value
        return if (country.uppercase() in FridayWeekend) day == 5 || day == 6 else day >= 6
    }

    private val FridayWeekend = setOf("EG", "SA", "AE", "KW", "QA", "BH", "OM", "JO", "IQ", "SY", "YE", "LY", "SD", "DZ")
}
