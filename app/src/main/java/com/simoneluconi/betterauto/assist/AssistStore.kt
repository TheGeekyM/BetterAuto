package com.simoneluconi.betterauto.assist

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Where the car was left: the last good position of the drive, when, and the road's name when the map knew it. */
data class ParkedCar(val latitude: Double, val longitude: Double, val at: Long, val road: String?)

/**
 * What the driving assistant remembers between drives, on this phone only (the process may be gone between two):
 * when the last drive ended, the rest timer, the trips set and when (the habit the brief guesses from), where the
 * car was parked. Positions are never logged.
 */
internal object AssistStore {
    private var prefs: SharedPreferences? = null
    private val _parked = MutableStateFlow<ParkedCar?>(null)
    val parked: StateFlow<ParkedCar?> = _parked

    private fun prefs(context: Context): SharedPreferences = prefs ?: context.applicationContext
        .getSharedPreferences("assist", Context.MODE_PRIVATE).also {
            prefs = it
            _parked.value = decodeParked(it.getString(KEY_PARKED, null))
        }

    fun init(context: Context) {
        prefs(context)
    }

    fun lastEndAt(context: Context): Long = prefs(context).getLong(KEY_LAST_END, 0L)

    fun setLastEnd(context: Context, at: Long) = prefs(context).edit { putLong(KEY_LAST_END, at) }

    /** A brief was due and not said yet. */
    fun briefOwed(context: Context): Boolean = prefs(context).getBoolean(KEY_BRIEF_OWED, false)

    fun setBriefOwed(context: Context, owed: Boolean) = prefs(context).edit { putBoolean(KEY_BRIEF_OWED, owed) }

    fun restTimer(context: Context): RestTimer = prefs(context).let {
        RestTimer(it.getLong(KEY_REST_START, 0L), it.getLong(KEY_REST_MOVING, 0L), it.getLong(KEY_REST_REMINDED, 0L))
    }

    fun saveRest(context: Context, timer: RestTimer) = prefs(context).edit {
        putLong(KEY_REST_START, timer.stretchStart).putLong(KEY_REST_MOVING, timer.lastMovingAt).putLong(KEY_REST_REMINDED, timer.lastReminderAt)
    }

    fun tripStarts(context: Context): List<TripStart> = runCatching { decodeTrips(prefs(context).getString(KEY_TRIPS, null)) }.getOrDefault(emptyList())

    fun addTripStart(context: Context, trip: TripStart) {
        val list = (tripStarts(context) + trip).takeLast(MAX_TRIPS)
        prefs(context).edit { putString(KEY_TRIPS, encodeTrips(list)) }
    }

    fun setParked(context: Context, car: ParkedCar?) {
        _parked.value = car
        prefs(context).edit { if (car == null) remove(KEY_PARKED) else putString(KEY_PARKED, encodeParked(car)) }
    }

    internal fun encodeTrips(list: List<TripStart>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("name", it.name).put("lat", it.latitude).put("lon", it.longitude).put("at", it.at)) }
    }.toString()

    internal fun decodeTrips(json: String?): List<TripStart> {
        if (json.isNullOrBlank()) return emptyList()
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TripStart(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.getLong("at"))
        }
    }

    internal fun encodeParked(car: ParkedCar): String =
        JSONObject().put("lat", car.latitude).put("lon", car.longitude).put("at", car.at).put("road", car.road ?: JSONObject.NULL).toString()

    internal fun decodeParked(json: String?): ParkedCar? = runCatching {
        if (json.isNullOrBlank()) return null
        val o = JSONObject(json)
        ParkedCar(o.getDouble("lat"), o.getDouble("lon"), o.getLong("at"), o.optString("road").takeIf { it.isNotBlank() && it != "null" })
    }.getOrNull()

    private const val KEY_LAST_END = "last_end"
    private const val KEY_BRIEF_OWED = "brief_owed"
    private const val KEY_REST_START = "rest_start"
    private const val KEY_REST_MOVING = "rest_moving"
    private const val KEY_REST_REMINDED = "rest_reminded"
    private const val KEY_TRIPS = "trip_starts"
    private const val KEY_PARKED = "parked"
    private const val MAX_TRIPS = 80
}
