package com.simoneluconi.betterauto.prayer

import android.content.Context
import android.location.LocationManager
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.settings.LauncherSettings
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Where the times are for: the car's position as the map last had it ([MapLocation], car GPS or the phone's), else
 * the phone's last known location, else the city chosen in the settings ([city] set). Rounded to about a kilometre:
 * the times move by seconds, and the widget isn't recomputed with every fix.
 */
data class PrayerPlace(val latitude: Double, val longitude: Double, val city: PrayerCity?) {
    companion object {
        fun of(context: Context, settings: LauncherSettings): PrayerPlace =
            of(MapLocation.fix.value?.let { it.latitude to it.longitude } ?: lastKnown(context), settings.prayerCity.value)

        fun of(position: Pair<Double, Double>?, city: PrayerCity): PrayerPlace =
            position?.let { (lat, lon) -> PrayerPlace(round(lat), round(lon), null) } ?: PrayerPlace(city.latitude, city.longitude, city)

        internal fun round(degrees: Double) = (degrees * 100).roundToInt() / 100.0

        /** The phone's last position by any provider; null without the permission or any fix. */
        fun lastKnown(context: Context): Pair<Double, Double>? = runCatching {
            val lm = context.getSystemService(LocationManager::class.java) ?: return null
            lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }
}

/** The day's times at [place] with the chosen method, in the phone's time zone. */
internal fun prayerDay(date: LocalDate, place: PrayerPlace, settings: LauncherSettings, zone: ZoneId = ZoneId.systemDefault()): DayTimes =
    PrayerTimes.day(date, place.latitude, place.longitude, zone, settings.prayerMethod.value, settings.asrJuristic.value)
