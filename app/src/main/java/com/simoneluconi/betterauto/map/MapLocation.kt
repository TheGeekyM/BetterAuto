package com.simoneluconi.betterauto.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.CarGpsGate
import com.simoneluconi.betterauto.car.GearPosition
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the car is for the map, and which way it points. */
data class MapFix(
    val latitude: Double,
    val longitude: Double,
    /** Degrees from north; kept from the last move while standing, null until the car has moved. */
    val bearing: Float?,
    val speedMs: Float?,
    val accuracyM: Float?,
    val source: MapSource,
    /** [SystemClock.elapsedRealtime] when it arrived (or was estimated). */
    val at: Long,
) {
    fun toLocation(): Location = Location("betterauto").also { l ->
        l.latitude = latitude
        l.longitude = longitude
        bearing?.let { l.bearing = it }
        speedMs?.let { l.speed = it }
        accuracyM?.let { l.accuracy = it }
        l.time = System.currentTimeMillis()
        l.elapsedRealtimeNanos = at * 1_000_000
    }
}

/** Where the position on the map comes from. */
enum class MapSource {
    /** The car's own GPS. */
    CAR,
    /** The phone's location. */
    PHONE,
    /** No GPS at all (a tunnel): moved on from the last fix with the car's speed and heading. */
    ESTIMATED,
}

/** Why there is no position to show, when there is none. */
enum class MapLocationProblem { NONE, NO_PERMISSION, SEARCHING }

/**
 * The position the map follows, while someone asks for it ([request]): the car screen for the whole session,
 * a map on screen, the guidance. The same rule as the smart-home arrival buttons ([CarGpsGate]): the car's own
 * GPS while it is live and sees satellites, then the phone's GPS stays off; otherwise the phone's real GPS once
 * a second, its network location only as a fallback while the GPS is quiet. The heading is the way the car
 * moves (the GPS course), never the phone's compass.
 *
 * Only a live, precise phone fix moves the car: one delivered late (a remembered or batched position) or a
 * vague one (a network guess hundreds of metres off, in the sea by a coast road) is left out, and a phone GPS
 * gone quiet is asked again. A position kept from before (the map closed, the last session) is shown as the
 * last known one, not as live.
 *
 * With no GPS at all (a tunnel, a covered car park) the position goes on from the last fix as the car
 * says it moves: its speed (backwards in reverse), and for the turns its own compass or, failing that,
 * its gyroscope, once the gyroscope's sense of turning has been checked against the GPS.
 */
object MapLocation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _fix = MutableStateFlow<MapFix?>(null)

    /** The last position; kept after the map closes, so it opens where the car was. */
    val fix: StateFlow<MapFix?> = _fix

    private val _problem = MutableStateFlow(MapLocationProblem.NONE)
    val problem: StateFlow<MapLocationProblem> = _problem

    private val _lost = MutableStateFlow(false)

    /** No GPS for a while and nothing to estimate with: the position shown is the last one seen. */
    val lost: StateFlow<Boolean> = _lost

    private var users = 0
    private var job: Job? = null
    private var context: Context? = null
    private var phoneListener: LocationListener? = null
    /** The network location, heard only while the phone's GPS is quiet. */
    private var fallbackListener: LocationListener? = null
    private var phoneProvider: String? = null
    /** When the phone's location was last asked for, and when its [phoneProvider] last delivered (used or not). */
    private var phoneAskedAt = 0L
    private var lastPhoneAt = 0L
    /** The last fix from the phone's [phoneProvider] that was used. */
    private var lastGpsAt = 0L
    /** Times the quiet phone GPS was asked again, since its last fix. */
    private var reasked = 0
    /** Why phone fixes are being left out, logged once per stretch. */
    private var dropping: PhoneVerdict? = null
    /** The phone's provider whose fixes move the car. */
    private var phoneSource: String? = null
    private var lastCarAt = 0L
    /** The last fix from a GPS, the car's or the phone's (not an estimate). */
    private var lastRealAt = 0L
    private var source: MapSource? = null
    private val gyro = GyroSense()
    /** The estimate's last step, standing or moving. */
    private var stepAt = 0L

    /** Follows the position while the returned function is not called; maps on screen call it as they go. */
    fun request(context: Context): () -> Unit {
        this.context = context.applicationContext
        if (users++ == 0) start()
        var released = false
        return {
            if (!released) {
                released = true
                if (--users == 0) stop()
            }
        }
    }

    @Volatile private var phoneIgnored = false

    /** Debug: no phone location, as deep in a tunnel, to try the estimate on a bench with WiFi positioning. */
    fun ignorePhone(ignored: Boolean) {
        phoneIgnored = ignored
        if (ignored) scope.launch { stopPhone() }
    }

    /** The car says it has a GPS of its own: then a position from elsewhere is worth a word on the map. */
    fun carHasGps(snapshot: VehicleSnapshot): Boolean = "LOCATION" in snapshot.offered

    private fun start() {
        lastCarAt = 0L
        // A position kept from before (the map closed, the last session) is where the car was, not where it is: never
        // moved on from, and labelled once it is old ([reckon])
        lastRealAt = 0L
        lastPhoneAt = 0L
        lastGpsAt = 0L
        reasked = 0
        dropping = null
        job = scope.launch {
            launch { followCar() }
            launch { watchPhone() }
            launch { reckon() }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        stopPhone()
        source = null
        phoneSource = null
        _problem.value = MapLocationProblem.NONE
        _lost.value = false
    }

    private suspend fun followCar() {
        val gate = CarGpsGate()
        VehicleData.snapshot.mapNotNull { s -> s.position?.let { it to s.satellites } }.distinctUntilChanged().collect { (timed, satellites) ->
            if (gate.judge(timed, satellites) != CarGpsGate.Verdict.USE) return@collect
            lastCarAt = SystemClock.elapsedRealtime()
            stopPhone()
            val p = timed.value
            onFix(p.latitude, p.longitude, p.bearing?.toFloat(), p.speedKmh?.div(3.6)?.toFloat(), p.accuracyM?.toFloat(), MapSource.CAR)
        }
    }

    private fun carLive() = lastCarAt != 0L && SystemClock.elapsedRealtime() - lastCarAt < CarGpsGate.FRESH_MS

    /**
     * The phone's GPS on whenever the car's is quiet, off as soon as the car talks again. A request gone quiet is
     * made again: a phone may stop delivering to an app in the background without a word.
     */
    private suspend fun watchPhone() {
        while (true) {
            if (carLive()) {
                stopPhone()
            } else {
                startPhone()
                val quietMs = SystemClock.elapsedRealtime() - maxOf(phoneAskedAt, lastPhoneAt)
                if (phoneListener != null && quietMs > phoneSilentMs(lastPhoneAt != 0L, reasked)) {
                    if (reasked == 0) EventLog.log("Map: no fix from the phone's ${phoneProvider ?: "location"} for ${quietMs / 1000} s, asking again (${phoneState()})")
                    reasked++
                    stopPhone()
                    startPhone(again = true)
                }
            }
            delay(PHONE_CHECK_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPhone(again: Boolean = false) {
        if (phoneListener != null || phoneIgnored) return
        val context = context ?: return
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            if (_problem.value != MapLocationProblem.NO_PERMISSION) EventLog.log("Map: no location permission, waiting for the car's GPS")
            _problem.value = MapLocationProblem.NO_PERMISSION
            return
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val provider = phoneProvider(lm) ?: return
        phoneProvider = provider
        if (_fix.value == null) {
            _problem.value = MapLocationProblem.SEARCHING
            // Something to show at once while the first fix comes: the newest any provider remembers, only if it is as
            // fresh as a live one (an older one put the car where it had been, maybe far away, as if it were there)
            lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { onPhoneLocation(it, primary = it.provider == provider, remembered = true) }
        }
        // The real GPS once a second: the fused location hands over the mobile network's guess, hundreds of metres
        // off, whenever the GPS pauses. The network is heard too, slower, but only counts while the GPS is quiet
        val listener = LocationListener { onPhoneLocation(it, primary = true) }
        if (!request(context, lm, provider, PHONE_INTERVAL_MS, listener)) return
        phoneListener = listener
        if (provider == LocationManager.GPS_PROVIDER && enabled(lm, LocationManager.NETWORK_PROVIDER)) {
            val fallback = LocationListener { onPhoneLocation(it, primary = false) }
            if (request(context, lm, LocationManager.NETWORK_PROVIDER, FALLBACK_INTERVAL_MS, fallback)) fallbackListener = fallback
        }
        phoneAskedAt = SystemClock.elapsedRealtime()
        if (!again) EventLog.log("Map: asking the phone's $provider every ${PHONE_INTERVAL_MS / 1000} s${if (fallbackListener != null) ", the network as fallback" else ""}")
    }

    @SuppressLint("MissingPermission")
    private fun request(context: Context, lm: LocationManager, provider: String, intervalMs: Long, listener: LocationListener): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            val quality = if (provider == LocationManager.NETWORK_PROVIDER) LocationRequest.QUALITY_BALANCED_POWER_ACCURACY else LocationRequest.QUALITY_HIGH_ACCURACY
            val request = LocationRequest.Builder(intervalMs).setQuality(quality).setMinUpdateDistanceMeters(0f).build()
            lm.requestLocationUpdates(provider, request, context.mainExecutor, listener)
        } else {
            lm.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())
        }
    }.isSuccess

    private fun stopPhone() {
        val lm = context?.getSystemService(LocationManager::class.java)
        phoneListener?.let { lm?.removeUpdates(it) }
        fallbackListener?.let { lm?.removeUpdates(it) }
        phoneListener = null
        fallbackListener = null
    }

    /**
     * A fix from the phone: [primary] from the provider asked once a second (its GPS, or the only one there is), else
     * the network fallback. [remembered]: a last known position, not a delivery.
     */
    private fun onPhoneLocation(loc: Location, primary: Boolean, remembered: Boolean = false) {
        if (carLive()) return
        val now = SystemClock.elapsedRealtime()
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        // A provider that leaves the time out is taken as live
        val ageMs = if (loc.elapsedRealtimeNanos == 0L) 0L else ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0L)
        val verdict = phoneFixVerdict(
            primary, ageMs, accuracy,
            sinceGpsMs = if (lastGpsAt == 0L) null else now - lastGpsAt,
            estimating = source == MapSource.ESTIMATED,
        )
        val last = _fix.value
        val details = "${loc.provider}, accuracy ${accuracy?.roundToInt() ?: "?"} m, age ${String.format(Locale.ROOT, "%.1f", ageMs / 1000.0)} s" +
            (last?.let { ", ${distanceM(it.latitude, it.longitude, loc.latitude, loc.longitude).roundToInt()} m from the last" } ?: "")
        if (primary && !remembered) {
            // Every hole in the GPS's deliveries tells something on the next drive: throttled, batched, no sky
            val gap = if (lastPhoneAt == 0L) null else now - lastPhoneAt
            if (gap != null && gap > GAP_LOG_MS) {
                EventLog.log("Map: phone fix after ${gap / 1000} s without (${if (reasked > 0) "asked again $reasked time(s), " else ""}$details; ${phoneState()})")
            }
            lastPhoneAt = now
            reasked = 0
        }
        if (verdict != PhoneVerdict.USE) {
            if (verdict == PhoneVerdict.GPS_BETTER || remembered) return
            if (dropping != verdict) EventLog.log("Map: phone fix left out, ${if (verdict == PhoneVerdict.OLD) "delivered late" else "too vague"} ($details)")
            dropping = verdict
            return
        }
        dropping = null
        if (!remembered) {
            if (primary) lastGpsAt = now
            // Which of the phone's providers moves the car, when it changes (the GPS, the network while it is quiet)
            if (source == MapSource.PHONE && phoneSource != loc.provider) EventLog.log("Map: following the phone's ${loc.provider} ($details)")
            phoneSource = loc.provider
        }
        onFix(
            loc.latitude, loc.longitude,
            if (loc.hasBearing()) loc.bearing else null,
            if (loc.hasSpeed()) loc.speed else null,
            accuracy,
            MapSource.PHONE,
            remembered = remembered,
            at = now - ageMs,
            details = details,
        )
    }

    private fun onFix(
        latitude: Double, longitude: Double, bearing: Float?, speedMs: Float?, accuracyM: Float?, from: MapSource,
        remembered: Boolean = false, at: Long? = null, details: String? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = _fix.value
        if (!remembered) {
            lastRealAt = now
            setSource(from, details)
            _problem.value = MapLocationProblem.NONE
            _lost.value = false
        }
        val heading = heading(last, latitude, longitude, bearing, speedMs)
        // Both from a GPS and a turn between them: does the gyroscope agree on which way is left?
        if (last != null && last.source != MapSource.ESTIMATED && !remembered) {
            gyro.learn(last.bearing, heading, (now - last.at) / 1000.0, speedMs, carMotion(VehicleData.snapshot.value)?.rotation?.third)
        }
        // A remembered position keeps its own time: the guidance, waiting for a fix from now, must not take it
        _fix.value = MapFix(latitude, longitude, heading, speedMs, accuracyM, from, if (remembered) at ?: now else now)
    }

    /** Once a second: with no GPS for a few seconds, moves the car on as the car says it moves. */
    private suspend fun reckon() {
        while (true) {
            delay(RECKON_STEP_MS)
            val last = _fix.value ?: continue
            val now = SystemClock.elapsedRealtime()
            // No fix since the start: what is shown was kept from before, and once that is old it says so
            if (lastRealAt == 0L) {
                val lost = now - last.at > LOST_AFTER_MS
                if (lost && !_lost.value) EventLog.log("Map: no fix yet, the last known position (${(now - last.at) / 1000} s old) is shown")
                _lost.value = lost
                continue
            }
            val quiet = now - lastRealAt
            if (quiet < RECKON_AFTER_MS) {
                _lost.value = false
                continue
            }
            val snapshot = VehicleData.snapshot.value
            val wall = System.currentTimeMillis()
            // Every piece of car data is optional: without the speed nothing can be estimated, the car stays
            // where it was last seen and, once that is clearly not a pause between fixes, the map says so
            val kmh = snapshot.speed?.takeIf { wall - it.at < CAR_DATA_FRESH_MS }?.value?.kmh
            if (kmh == null || quiet > RECKON_MAX_MS) {
                val lost = kmh != null || quiet > LOST_AFTER_MS
                if (lost && !_lost.value) EventLog.log("Map: no GPS${if (kmh == null) " and no speed from the car" else " for too long"}: the position stands")
                _lost.value = lost
                continue
            }
            _lost.value = false
            // Moved since the last fix or the last step, whichever is later: a stop in between doesn't count
            val dt = (now - maxOf(last.at, stepAt)) / 1000.0
            stepAt = now
            // Standing: a phone sends little while nothing changes, and there is nothing to move
            if (kmh < STANDING_KMH) continue
            val reverse = snapshot.gear?.takeIf { wall - it.at < CAR_DATA_FRESH_MS }?.value?.position == GearPosition.REVERSE
            val motion = carMotion(snapshot)
            val heading = carHeading(last.bearing, motion?.compassBearing, motion?.rotation?.third, gyro.sense, dt)
            val metres = kmh / 3.6 * dt * (if (reverse) -1 else 1)
            val (lat, lon) = if (heading != null) advance(last.latitude, last.longitude, heading.toDouble(), metres) else last.latitude to last.longitude
            setSource(MapSource.ESTIMATED)
            _fix.value = MapFix(lat, lon, heading, (kmh / 3.6).toFloat(), null, MapSource.ESTIMATED, now)
        }
    }

    private fun carMotion(snapshot: VehicleSnapshot) =
        snapshot.motion?.takeIf { System.currentTimeMillis() - it.at < CAR_DATA_FRESH_MS }?.value

    private fun setSource(value: MapSource, details: String? = null) {
        if (source == value) return
        EventLog.log(
            when (value) {
                MapSource.CAR -> "Map: following the car's GPS"
                MapSource.PHONE -> "Map: following the phone's location"
                MapSource.ESTIMATED -> "Map: no GPS, estimating from the car's speed" +
                    if (gyro.sense != 0) " and gyroscope" else ""
            } + (details?.let { " ($it)" } ?: ""),
        )
        source = value
    }

    /**
     * Which way the car points: its own bearing while it moves; standing still a GPS bearing wanders, so the
     * last one stays. Without any bearing, from the way it moved since the last fix.
     */
    internal fun heading(last: MapFix?, latitude: Double, longitude: Double, bearing: Float?, speedMs: Float?): Float? {
        val moving = speedMs == null || speedMs >= MOVING_MS
        if (bearing != null && moving) return bearing
        // Standing from the start: no direction yet (north up), not whatever the GPS guesses at rest
        if (last == null) return null
        if (bearing == null && speedMs == null) {
            val d = distanceM(last.latitude, last.longitude, latitude, longitude)
            if (d >= MOVED_M) return bearingDeg(last.latitude, last.longitude, latitude, longitude)
        }
        return last.bearing
    }

    /**
     * For the log: whether the phone's screen is on and how much the system thinks of this app (100 foreground,
     * 125 foreground service, 200+ less): a phone that stops the GPS for apps in the background shows here.
     */
    private fun phoneState(): String {
        val context = context ?: return "?"
        val screen = context.getSystemService(PowerManager::class.java)?.isInteractive
        val app = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
        return "screen ${if (screen == true) "on" else if (screen == false) "off" else "?"}, app importance $app"
    }

    /** The phone's own GPS; without one (switched off, a tablet) the fused location, else the network's. */
    private fun phoneProvider(lm: LocationManager): String? {
        if (enabled(lm, LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= 31 && enabled(lm, LocationManager.FUSED_PROVIDER)) return LocationManager.FUSED_PROVIDER
        return LocationManager.NETWORK_PROVIDER.takeIf { enabled(lm, it) }
    }

    private fun enabled(lm: LocationManager, provider: String) =
        runCatching { lm.allProviders.contains(provider) && lm.isProviderEnabled(provider) }.getOrDefault(false)

    /**
     * How long the phone's quiet location waits before it is asked again: longer for a first fix (a GPS starting cold
     * may take half a minute, asking again would only start it over), then longer each time in a row (a long tunnel).
     */
    internal fun phoneSilentMs(hadFix: Boolean, reasked: Int): Long =
        if (!hadFix) PHONE_FIRST_FIX_MS else minOf(PHONE_SILENT_MS * (reasked + 1), PHONE_SILENT_MAX_MS)

    private const val PHONE_INTERVAL_MS = 1_000L
    private const val FALLBACK_INTERVAL_MS = 5_000L
    private const val PHONE_CHECK_MS = 2_000L
    /** The phone's GPS this long without a word: asked again. */
    private const val PHONE_SILENT_MS = 10_000L
    private const val PHONE_SILENT_MAX_MS = 60_000L
    private const val PHONE_FIRST_FIX_MS = 45_000L
    /** A hole this long in the phone's deliveries goes in the log. */
    private const val GAP_LOG_MS = 5_000L

    /** Below this (about 5 km/h) the bearing is noise. */
    private const val MOVING_MS = 1.5f

    /** Moved enough to tell a direction from two fixes. */
    private const val MOVED_M = 8.0

    private const val RECKON_STEP_MS = 1_000L
    /** No GPS for this long: the estimate starts. */
    private const val RECKON_AFTER_MS = 3_000L
    /** The estimate drifts: past this the car just stays where it was last seen. */
    private const val RECKON_MAX_MS = 5 * 60_000L
    /** Without the car's speed, this long without a fix is no pause: the signal is gone. */
    private const val LOST_AFTER_MS = 15_000L
    private const val STANDING_KMH = 1.0
    private const val CAR_DATA_FRESH_MS = 3_000L
    /** A phone fix vaguer than this doesn't end the estimate. */
    internal const val ESTIMATE_BEATS_M = 60f
}

/** What a phone fix is worth to the map. */
internal enum class PhoneVerdict {
    USE,
    /** Delivered late (remembered, batched): the car was there, it is not there now. */
    OLD,
    /** Too vague to put the car on a road (a network guess). */
    VAGUE,
    /** A network fix while the GPS speaks: the GPS is better. */
    GPS_BETTER,
}

/** Older than this at delivery, a phone fix is where the car was. A live one is well under a second. */
internal const val PHONE_FIX_MAX_AGE_MS = 5_000L

/** A phone fix vaguer than this would put the car on another road, or in the sea. */
internal const val PHONE_FIX_MAX_ERROR_M = 100f

/** The network location only after the phone's GPS has been quiet this long. */
internal const val GPS_PREFERRED_MS = 5_000L

/**
 * Whether a phone fix may move the car: [primary] (from the provider asked once a second, the GPS or the only one
 * there is; else the network fallback), its [ageMs] at delivery, [accuracyM], the time since the last primary fix
 * used ([sinceGpsMs], null with none yet), and whether the position is [estimating] from the car's speed (then only
 * a fix as good as the estimate ends it).
 */
internal fun phoneFixVerdict(primary: Boolean, ageMs: Long, accuracyM: Float?, sinceGpsMs: Long?, estimating: Boolean): PhoneVerdict {
    if (ageMs > PHONE_FIX_MAX_AGE_MS) return PhoneVerdict.OLD
    if (!primary && sinceGpsMs != null && sinceGpsMs < GPS_PREFERRED_MS) return PhoneVerdict.GPS_BETTER
    val limit = if (estimating) MapLocation.ESTIMATE_BEATS_M else PHONE_FIX_MAX_ERROR_M
    // A network fix that does not say how good it is: not good enough. The GPS's are
    val accuracy = accuracyM ?: if (primary) 0f else Float.MAX_VALUE
    return if (accuracy > limit) PhoneVerdict.VAGUE else PhoneVerdict.USE
}

/**
 * Which way the car's gyroscope turns. Cars differ in how they mount and report it, so its yaw rate is
 * used only once it has agreed with the GPS on a few turns: [sense] is +1 when a positive rate turns the
 * car right (clockwise), -1 left, 0 while not known.
 */
internal class GyroSense {
    private var votes = 0

    val sense: Int get() = when {
        votes >= VOTES -> 1
        votes <= -VOTES -> -1
        else -> 0
    }

    /** One step between two GPS fixes: [from] and [to] bearings, [yawRate] in rad/s from the car. */
    fun learn(from: Float?, to: Float?, seconds: Double, speedMs: Float?, yawRate: Double?) {
        if (from == null || to == null || yawRate == null || seconds <= 0 || seconds > 5) return
        if (speedMs != null && speedMs < MIN_SPEED_MS) return
        val turned = turnDeg(from, to)
        val gyroDeg = Math.toDegrees(yawRate) * seconds
        if (abs(turned) < MIN_TURN_DEG || abs(gyroDeg) < MIN_TURN_DEG) return
        votes = (votes + (sign(turned) * sign(gyroDeg)).toInt()).coerceIn(-MAX_VOTES, MAX_VOTES)
    }

    private companion object {
        const val VOTES = 3
        const val MAX_VOTES = 10
        const val MIN_TURN_DEG = 4.0
        const val MIN_SPEED_MS = 3f
    }
}

/**
 * The car's heading after [seconds]: its own compass when it has one, else [last] turned by the gyroscope's
 * yaw rate (only with a known [sense]), else unchanged.
 */
internal fun carHeading(last: Float?, compass: Double?, yawRate: Double?, sense: Int, seconds: Double): Float? {
    if (compass != null) return ((compass % 360 + 360) % 360).toFloat()
    if (last == null) return null
    if (yawRate == null || sense == 0) return last
    val turned = Math.toDegrees(yawRate) * seconds * sense
    return (((last + turned) % 360 + 360) % 360).toFloat()
}

/** Clockwise turn from [from] to [to], in -180..180 degrees. */
internal fun turnDeg(from: Float, to: Float): Double = ((to - from + 540.0) % 360.0) - 180.0

/** The point [metres] away along [bearingDeg] (negative goes backwards). */
internal fun advance(latitude: Double, longitude: Double, bearingDeg: Double, metres: Double): Pair<Double, Double> {
    val b = Math.toRadians(bearingDeg)
    val lat = latitude + metres * cos(b) / METRES_PER_DEGREE
    val lon = longitude + metres * sin(b) / (METRES_PER_DEGREE * cos(Math.toRadians(latitude)))
    return lat to lon
}

/** Equirectangular: plenty for the few metres between two fixes. */
internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
    val y = Math.toRadians(lat2 - lat1)
    return sqrt(x * x + y * y) * EARTH_RADIUS_M
}

internal fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
    val y = Math.toRadians(lat2 - lat1)
    return ((Math.toDegrees(atan2(x, y)) + 360.0) % 360.0).toFloat()
}

private const val EARTH_RADIUS_M = 6_371_000.0
private const val METRES_PER_DEGREE = EARTH_RADIUS_M * Math.PI / 180.0
