package com.simoneluconi.betterauto.assist

import android.content.Context
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.nav.Destination
import com.simoneluconi.betterauto.map.nav.PlaceSearch
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.phone.PhoneCountry
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.car.UnitSystem
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.voice.CommandParser
import com.simoneluconi.betterauto.voice.VoiceAssistant
import java.util.Locale

/** The fuel is low: the nearest station around the car, said with how far it is, and offered as a stop. */
internal object FuelStop {
    /** Farther than this is no "nearest station" worth offering. */
    private const val MAX_KM = 25.0
    /** How long the yes or no is listened for. */
    private const val LISTEN_MS = 6_000L

    suspend fun offer(context: Context) {
        val res = AppLocales.voiceContext(context).resources
        val station = nearest(context)
        if (station == null) {
            EventLog.log("Assist: fuel low, no station found nearby")
            AssistVoice.say(context, res.getString(R.string.assist_fuel_low))
            return
        }
        val (place, km) = station
        val imperial = when (LauncherSettings.get(context).unitSystem.value) {
            UnitSystem.IMPERIAL -> true
            UnitSystem.METRIC -> false
            UnitSystem.AUTO -> PhoneCountry.of(context) in ImperialCountries
        }
        EventLog.log("Assist: fuel low, a station offered")
        val text = res.getString(R.string.assist_fuel_station, spokenDistance(context, km, imperial))
        // Once said, the microphone opens for a yes or a no
        AssistVoice.say(context, text) {
            // Not the full check: the end of our own line may still be in the car's queue
            if (!AssistVoice.talkFree()) return@say
            VoiceAssistant.ask(context, VoiceAssistant.Question(res.getString(R.string.assist_fuel_hint), LISTEN_MS, yesNo = true) { words ->
                val yes = words?.let(CommandParser::yesNo)
                EventLog.log("Assist: fuel station answer ${when (yes) { true -> "yes"; false -> "no"; null -> "none" }}")
                when (yes) {
                    true -> VoiceAssistant.report(context, words.orEmpty(), take(context, place))
                    false -> VoiceAssistant.report(context, words.orEmpty(), res.getString(R.string.assist_fuel_declined))
                    null -> Unit
                }
            })
        }
    }

    /** Yes: the station as a stop on the trip, or the place to drive to with none; what to say back. */
    private fun take(context: Context, station: Destination): String {
        val res = AppLocales.voiceContext(context).resources
        Trip.init(context)
        if (Trip.places.value.isEmpty()) {
            Trip.newDestination(station)
            LauncherSettings.get(context).setSection(Section.MAP)
            return res.getString(R.string.voice_navigating, station.name)
        }
        val fix = MapLocation.fix.value
        val added = Trip.addStop(station, fix?.latitude, fix?.longitude)
        return res.getString(if (added) R.string.voice_stop_added else R.string.voice_stop_already, station.name)
    }

    private suspend fun nearest(context: Context): Pair<Destination, Double>? {
        if (MapLocation.fix.value == null) return null
        val query = when (AppLocales.voice(context).language) {
            "ar" -> "محطة بنزين"
            "it" -> "distributore di benzina"
            else -> "gas station"
        }
        // A text search, not sorted by distance: the closest of what it found, within reach
        return runCatching { PlaceSearch.search(context, query, limit = 8) }.getOrNull().orEmpty()
            .mapNotNull { r -> r.distanceM?.let { r.destination to it / 1000.0 } }
            .filter { it.second <= MAX_KM }
            .minByOrNull { it.second }
    }

    private fun spokenDistance(context: Context, km: Double, imperial: Boolean): String {
        val res = AppLocales.voiceContext(context).resources
        val locale = AppLocales.voice(context)
        val value = if (imperial) km / 1.609344 else km
        return when (val d = BriefText.distance(value)) {
            is BriefText.Distance.Metres ->
                if (imperial) res.getString(R.string.assist_miles, number(0.1 * (d.value / 100).coerceAtLeast(1), locale))
                else res.getString(R.string.assist_metres, d.value)
            is BriefText.Distance.Km -> res.getString(if (imperial) R.string.assist_miles else R.string.assist_km, number(d.value, locale))
        }
    }

    /** "2" or "1.5", in the voice's digits. */
    private fun number(value: Double, locale: Locale): String =
        if (value == Math.floor(value)) String.format(locale, "%d", value.toLong()) else String.format(locale, "%.1f", value)

    private val ImperialCountries = setOf("US", "GB", "LR", "MM")
}
