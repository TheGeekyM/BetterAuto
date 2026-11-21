package com.simoneluconi.betterauto.map.nav

import android.content.Context
import com.simoneluconi.betterauto.BuildConfig
import com.simoneluconi.betterauto.map.MapFix
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes from Google's Routes API (https://developers.google.com/maps/documentation/routes): the live traffic in
 * the route chosen and in its time. Used when the build has a Google key of its own (`betterauto.placesKey`) and
 * no route server of the driver's is set; Valhalla otherwise, and whenever Google can't be reached.
 */
object GoogleRoutes {
    private const val URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELDS = "routes.duration,routes.distanceMeters,routes.description,routes.polyline.encodedPolyline," +
        "routes.travelAdvisory.speedReadingIntervals,routes.legs.steps.staticDuration," +
        "routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction"

    val available: Boolean get() = BuildConfig.PLACES_KEY.isNotEmpty()

    /** [alternatives]: other routes too (Google gives them only for a trip without stops). */
    fun request(context: Context, from: MapFix, places: List<Destination>, language: String, alternatives: Boolean = false): Request {
        fun waypoint(lat: Double, lon: Double) =
            JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", lat).put("longitude", lon)))
        val origin = waypoint(from.latitude, from.longitude)
        // Leave the way the car points, not behind it
        from.bearing?.let { origin.getJSONObject("location").put("heading", ((it.toInt() % 360) + 360) % 360) }
        val body = JSONObject()
            .put("origin", origin)
            .put("destination", places.last().let { waypoint(it.latitude, it.longitude) })
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("polylineQuality", "HIGH_QUALITY")
            .put("languageCode", language)
            .put("units", "METRIC")
            // The traffic along the line, for the map
            .put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE"))
        if (alternatives && places.size == 1) body.put("computeAlternativeRoutes", true)
        // Every stop a leg of its own, with an arrival
        if (places.size > 1) body.put("intermediates", JSONArray().apply { places.dropLast(1).forEach { put(waypoint(it.latitude, it.longitude)) } })
        return Request.Builder().url(URL)
            .header("X-Goog-Api-Key", BuildConfig.PLACES_KEY)
            .header("X-Goog-FieldMask", FIELDS)
            .header("X-Android-Package", context.packageName)
            .apply { PlaceSearch.signingSha1(context)?.let { header("X-Android-Cert", it) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /** Google's first route as ours; null when Google found no route. */
    internal fun parse(json: String): NavRoute? = parseAll(json).firstOrNull()

    /** Every route in Google's answer, its pick first. */
    internal fun parseAll(json: String): List<NavRoute> {
        val routes = JSONObject(json).optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { routeOf(routes.getJSONObject(it)) }
    }

    /**
     * One of Google's routes as ours: the steps' lines joined, one maneuver where each step starts, an arrival at
     * the end of every leg. Each step's time without traffic, scaled so they add up to the route's time with it.
     */
    private fun routeOf(route: JSONObject): NavRoute? {
        val legs = route.getJSONArray("legs")
        val points = ArrayList<Double>()
        val maneuvers = ArrayList<Maneuver>()
        val legEnds = IntArray(legs.length())
        val stretches = ArrayList<Triple<Int, Int, Double>>()
        for (l in 0 until legs.length()) {
            val steps = legs.getJSONObject(l).optJSONArray("steps") ?: JSONArray()
            for (s in 0 until steps.length()) {
                val step = steps.getJSONObject(s)
                val line = NavRoute.decodePolyline(step.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty(), 1e5)
                if (line.size < 4) continue
                // A step starts where the last one ended: that point once
                val joined = points.size >= 2 && points[points.size - 2] == line[0] && points[points.size - 1] == line[1]
                val begin = if (joined) points.size / 2 - 1 else points.size / 2
                for (i in (if (joined) 2 else 0) until line.size) points += line[i]
                stretches += Triple(begin, points.size / 2 - 1, seconds(step.optString("staticDuration")))
                val nav = step.optJSONObject("navigationInstruction")
                val text = nav?.optString("instructions").orEmpty()
                val instruction = text.substringBefore('\n').trim()
                val spoken = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
                val kind = kindOf(nav?.optString("maneuver").orEmpty())
                maneuvers += Maneuver(
                    kind = kind,
                    instruction = instruction,
                    street = Street.find(instruction)?.groupValues?.get(1)?.trim(),
                    spokenNow = spoken,
                    spokenSoon = spoken.replaceFirstChar { it.lowercase() },
                    beginIndex = begin,
                    roundaboutExit = if (kind == ManeuverKind.ROUNDABOUT) Exit.find(instruction)?.groupValues?.get(1)?.toInt() else null,
                )
            }
            if (points.isEmpty()) continue
            legEnds[l] = points.size / 2 - 1
            // Named and said by the stop or the destination later, in Navigation
            maneuvers += Maneuver(ManeuverKind.ARRIVE, "", null, "", "", legEnds[l], stop = l.takeIf { it < legs.length() - 1 })
        }
        if (points.size < 4) return null
        val duration = seconds(route.optString("duration"))
        val plain = NavRoute(points.toDoubleArray(), maneuvers, route.optDouble("distanceMeters"), duration, legEnds)
        val static = stretches.sumOf { it.third }
        val times = if (static > 0) NavRoute.secondsToPoints(plain.cumulative, stretches.map { (a, b, t) -> Triple(a, b, t * duration / static) }) else null
        return NavRoute(
            plain.points, maneuvers, plain.lengthM, duration, legEnds, times,
            traffic = trafficOf(route), description = route.optString("description").takeIf { it.isNotBlank() },
        )
    }

    /**
     * The slow and jammed stretches, cut out of the route's whole line: Google counts them by its points, which
     * are not quite the steps' (the line is drawn apart from the route's, on top of it).
     */
    private fun trafficOf(route: JSONObject): List<TrafficStretch> {
        val intervals = route.optJSONObject("travelAdvisory")?.optJSONArray("speedReadingIntervals") ?: return emptyList()
        val line = NavRoute.decodePolyline(route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty(), 1e5)
        val count = line.size / 2
        return (0 until intervals.length()).mapNotNull { i ->
            val interval = intervals.getJSONObject(i)
            val jam = when (interval.optString("speed")) {
                "SLOW" -> false
                "TRAFFIC_JAM" -> true
                else -> return@mapNotNull null
            }
            val from = interval.optInt("startPolylinePointIndex", 0).coerceIn(0, count)
            val to = interval.optInt("endPolylinePointIndex", 0).coerceIn(0, count - 1)
            if (to <= from) null else TrafficStretch(line.copyOfRange(2 * from, 2 * to + 2), jam)
        }
    }

    /** "3377s" into seconds. */
    private fun seconds(text: String) = text.removeSuffix("s").toDoubleOrNull() ?: 0.0

    // ponytail: street and exit read from English sentences only; other languages show the arrow's words on the card
    private val Street = Regex("\\b(?:onto|on) (.+)$")
    private val Exit = Regex("(\\d+)(?:st|nd|rd|th) exit")

    internal fun kindOf(maneuver: String): ManeuverKind = when (maneuver) {
        "TURN_SLIGHT_RIGHT", "RAMP_RIGHT", "FORK_RIGHT" -> ManeuverKind.SLIGHT_RIGHT
        "TURN_RIGHT" -> ManeuverKind.RIGHT
        "TURN_SHARP_RIGHT" -> ManeuverKind.SHARP_RIGHT
        "TURN_SLIGHT_LEFT", "RAMP_LEFT", "FORK_LEFT" -> ManeuverKind.SLIGHT_LEFT
        "TURN_LEFT" -> ManeuverKind.LEFT
        "TURN_SHARP_LEFT" -> ManeuverKind.SHARP_LEFT
        "UTURN_LEFT", "UTURN_RIGHT" -> ManeuverKind.U_TURN
        "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> ManeuverKind.ROUNDABOUT
        else -> ManeuverKind.STRAIGHT
    }
}
