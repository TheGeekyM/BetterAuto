package com.simoneluconi.betterauto.map.nav

import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import com.simoneluconi.betterauto.BuildConfig
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.distanceM
import com.simoneluconi.betterauto.smarthome.SmartHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A place found by a search: a [Destination] and how far it is from the car, when known. */
data class PlaceResult(val destination: Destination, val distanceM: Double?)

/**
 * Finds places by name or address: shops, restaurants, cinemas ("Roadhouse", "Cinema Giometti") as well as
 * streets and towns. Asks two sources together, both near the car or else where the phone last was, and puts the
 * two lists together: Google Places when the build has a key of its own (`betterauto.placesKey` in
 * local.properties; the best where OpenStreetMap is thin), else the phone's geocoder (whichever the phone has: on a
 * Chinese ROM Xiaomi's, which answers with places in China, so only what is near is kept); and Photon
 * (OpenStreetMap, https://photon.komoot.io). Also reads what another app shares: map links,
 * coordinates, an address in a message, a contact card.
 */
object PlaceSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Places matching [query], best first, near the car when its position is known. */
    suspend fun search(context: Context, query: String, limit: Int = 8): List<PlaceResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        home(q)?.let { return@withContext listOf(PlaceResult(it, distanceFromCar(it))) }
        // With no map on screen (the phone's search page) there is no fix: without a position the results would
        // come from anywhere in the world
        val near = MapLocation.fix.value?.let { it.latitude to it.longitude } ?: lastKnown(context)
        val (google, osm) = coroutineScope {
            val g = async {
                if (BuildConfig.PLACES_KEY.isEmpty()) geocoder(context, q, limit, near)
                else runCatching { google(context, q, near, limit) }
                    .onFailure { EventLog.log("Navigation: Google Places search failed (${it.message})") }
                    .getOrNull().orEmpty()
            }
            val p = async {
                runCatching { photon(q, near?.first, near?.second, limit) }
                    .onFailure { EventLog.log("Navigation: Photon search failed (${it.message})") }
                    .getOrNull().orEmpty()
            }
            // A slow geocoder must not hold Photon's answer back (its blocking call just finishes unheard)
            (withTimeoutOrNull(GEOCODER_WAIT_MS) { g.await() } ?: emptyList()) to p.await()
        }
        val first = if (BuildConfig.PLACES_KEY.isEmpty()) "phone geocoder" else "Google Places"
        EventLog.log("Navigation: search found ${google.size} ($first) + ${osm.size} (Photon)")
        merge(google, osm, limit).map { PlaceResult(it, distanceFromCar(it)) }
    }

    /**
     * [first] (Google's or the geocoder's, best for addresses), then the places of [second] that are not already there (within
     * [SAME_PLACE_M]), at most [limit].
     */
    internal fun merge(first: List<Destination>, second: List<Destination>, limit: Int): List<Destination> {
        val out = mutableListOf<Destination>()
        for (d in first + second) {
            if (out.size >= limit) break
            if (out.none { distanceM(it.latitude, it.longitude, d.latitude, d.longitude) < SAME_PLACE_M }) out += d
        }
        return out
    }

    /** Where the phone last was, by any provider, when the map isn't following it. */
    private fun lastKnown(context: Context): Pair<Double, Double>? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let { it.latitude to it.longitude }
    }.getOrNull()

    /** "Casa" / "home": the home place of the linked smart home, when there is one. */
    private fun home(query: String): Destination? {
        if (query.lowercase() !in HomeWords) return null
        val place = SmartHome.linked.value.firstNotNullOfOrNull { it.catalog.value?.home } ?: return null
        return Destination(place.name, null, place.latitude, place.longitude)
    }

    /** Google Places (Text Search, New), near [near] when known. */
    private fun google(context: Context, query: String, near: Pair<Double, Double>?, limit: Int): List<Destination> {
        val body = JSONObject().put("textQuery", query).put("pageSize", limit)
        near?.let { (lat, lon) ->
            val center = JSONObject().put("latitude", lat).put("longitude", lon)
            body.put("locationBias", JSONObject().put("circle", JSONObject().put("center", center).put("radius", GOOGLE_BIAS_M)))
        }
        val request = Request.Builder().url(GOOGLE_URL)
            .header("X-Goog-Api-Key", BuildConfig.PLACES_KEY)
            .header("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location")
            // What Google checks when the key is restricted to this app in the Cloud console
            .header("X-Android-Package", context.packageName)
            .apply { signingSha1(context)?.let { header("X-Android-Cert", it) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            // Only the code: never the request, which carries the key
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return parseGoogle(response.body?.string().orEmpty())
        }
    }

    /** Google's Text Search answer into destinations: the name and the address under it. */
    internal fun parseGoogle(json: String): List<Destination> {
        val places = JSONObject(json).optJSONArray("places") ?: return emptyList()
        return (0 until places.length()).mapNotNull { i ->
            val p = places.getJSONObject(i)
            val at = p.optJSONObject("location") ?: return@mapNotNull null
            val address = p.optString("formattedAddress").ifBlank { null }
            val name = p.optJSONObject("displayName")?.optString("text")?.ifBlank { null } ?: address ?: return@mapNotNull null
            Destination(name, address?.takeIf { it != name }, at.getDouble("latitude"), at.getDouble("longitude"))
        }
    }

    /** The SHA-1 of this APK's signing certificate, as Google expects it (hex, no colons). */
    internal fun signingSha1(context: Context): String? = sha1 ?: runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull().also { sha1 = it }

    @Volatile private var sha1: String? = null

    private fun photon(query: String, lat: Double?, lon: Double?, limit: Int): List<Destination> {
        val url = PHOTON_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .apply {
                // Nearer places first: the driver's "Roadhouse" is the nearest one
                if (lat != null && lon != null) addQueryParameter("lat", "%.5f".format(java.util.Locale.ROOT, lat)).addQueryParameter("lon", "%.5f".format(java.util.Locale.ROOT, lon))
            }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return parsePhoton(response.body?.string().orEmpty())
        }
    }

    /** Photon's GeoJSON answer into destinations: the name (or the street) and a short address under it. */
    internal fun parsePhoton(json: String): List<Destination> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val p = f.optJSONObject("properties") ?: return@mapNotNull null
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val town = p.optString("city").ifBlank { p.optString("town") }.ifBlank { p.optString("village") }.ifBlank { p.optString("county") }
            val name = p.optString("name").ifBlank { street }.ifBlank { town }.ifBlank { return@mapNotNull null }
            val address = listOf(street.takeIf { it != name }, town.takeIf { it != name }).filter { !it.isNullOrBlank() }.joinToString(", ").ifBlank { null }
            Destination(name, address, c.getDouble(1), c.getDouble(0))
        }
    }

    @Suppress("DEPRECATION") // the blocking call, on an IO thread: the listener version needs API 33
    private fun geocoder(context: Context, query: String, limit: Int, near: Pair<Double, Double>?): List<Destination> = runCatching {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context)
        // Within about 100 km (a box, as the geocoder takes no "near"); some geocoders ignore the box (Xiaomi's
        // answers from China), so what is outside it is dropped. Anywhere only when the position is not known
        val found = if (near == null) geocoder.getFromLocationName(query, limit).orEmpty() else {
            val (lat, lon) = near
            geocoder.getFromLocationName(query, limit, lat - NEAR_DEG, lon - NEAR_DEG, lat + NEAR_DEG, lon + NEAR_DEG).orEmpty()
                .filter { kotlin.math.abs(it.latitude - lat) <= NEAR_DEG && kotlin.math.abs(it.longitude - lon) <= NEAR_DEG }
        }
        found.map { a ->
            val line = a.getAddressLine(0)
            Destination(a.featureName?.takeIf { it.any(Char::isLetter) } ?: line ?: query, line, a.latitude, a.longitude)
        }
    }.getOrDefault(emptyList())

    private fun distanceFromCar(d: Destination): Double? =
        MapLocation.fix.value?.let { distanceM(it.latitude, it.longitude, d.latitude, d.longitude) }

    /**
     * Where something shared points: a `geo:` or `google.navigation:` link, a map link (short ones followed to the
     * full address, which holds the coordinates), coordinates in the text, or else the text searched as an address.
     */
    suspend fun resolve(context: Context, shared: String, title: String? = null): Destination? = withContext(Dispatchers.IO) {
        val text = shared.trim()
        if (text.isEmpty()) return@withContext null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val link = Regex("""(https?://\S+|geo:\S+|google\.navigation:\S+)""").find(text)?.value
        // Google Maps shares "Place name\nhttps://maps.app.goo.gl/..."
        val label = title?.takeIf { it.isNotBlank() } ?: lines.firstOrNull { !it.contains("://") && !it.startsWith("geo:") }
        if (link != null) {
            val target = if (link.startsWith("http")) runCatching { followLink(link) }.getOrNull() ?: link else link
            coordinatesIn(target)?.let { (lat, lon) ->
                return@withContext Destination(label ?: placeNameIn(target) ?: "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon), null, lat, lon)
            }
            // A link with a place name but no coordinates: search the name
            (queryIn(target) ?: placeNameIn(target))?.let { name -> return@withContext search(context, name, 1).firstOrNull()?.destination }
        }
        coordinatesIn(text)?.let { (lat, lon) -> return@withContext Destination(label ?: "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon), null, lat, lon) }
        // Plain text: an address or a place name
        search(context, lines.joinToString(", "), 1).firstOrNull()?.destination
    }

    /** The address a short map link leads to. */
    private fun followLink(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val final = response.request.url.toString()
            // The European consent page carries the real address in "continue"
            return runCatching { response.request.url.queryParameter("continue") }.getOrNull() ?: final
        }
    }

    /** Coordinates in a map link or a text: a place's own pin first, then the view's centre, then any "lat, lon". */
    internal fun coordinatesIn(text: String): Pair<Double, Double>? {
        val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
        val patterns = listOf(
            Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)"""),
            Regex("""geo:(-?\d+\.?\d*),(-?\d+\.?\d*)"""),
            Regex("""[?&](?:q|query|ll|daddr|destination)=(-?\d+\.\d+),\s*(-?\d+\.\d+)"""),
            Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)"""),
            Regex("""google\.navigation:q=(-?\d+\.\d+),(-?\d+\.\d+)"""),
            Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})(?![\d.])"""),
        )
        for (p in patterns) {
            val m = p.find(decoded) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            // geo:0,0?q=address means "no coordinates"
            if (lat == 0.0 && lon == 0.0) continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }

    /** "Piazza del Campo" from ".../maps/place/Piazza+del+Campo/@...". */
    internal fun placeNameIn(url: String): String? =
        Regex("""/place/([^/@?]+)""").find(url)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()?.ifBlank { null }

    /** The searched text of a `geo:0,0?q=...` or `?q=...` link. */
    internal fun queryIn(url: String): String? =
        Regex("""[?&](?:q|query|daddr|destination)=([^&]+)""").find(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()?.takeIf { it.isNotBlank() && coordinatesIn("geo:$it") == null }

    /** An address in a contact card (vCard ADR: post box; extended; street; town; region; postcode; country). */
    internal fun vCardAddress(vcard: String): Pair<String?, String>? {
        val unfolded = vcard.replace(Regex("\r?\n[ \t]"), "")
        val name = Regex("""(?m)^FN[^:]*:(.+)$""").find(unfolded)?.groupValues?.get(1)?.trim()
        val adr = Regex("""(?m)^(?:item\d+\.)?ADR[^:]*:(.+)$""").find(unfolded)?.groupValues?.get(1) ?: return null
        val parts = adr.split(';').map { it.replace("\\,", ",").trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return name to parts.joinToString(", ")
    }

    private const val PHOTON_URL = "https://photon.komoot.io/api/"
    private const val GOOGLE_URL = "https://places.googleapis.com/v1/places:searchText"
    private const val GOOGLE_BIAS_M = 50_000.0
    private const val SAME_PLACE_M = 50.0
    private const val NEAR_DEG = 1.0
    private const val GEOCODER_WAIT_MS = 4_000L
    private const val USER_AGENT = "BetterAuto (https://github.com/simoneluconi/BetterAuto)"

    /** What "home" is called in a voice command or a search, in the languages of the app. */
    private val HomeWords = setOf("casa", "a casa", "home", "البيت", "بيتي", "المنزل")
}
