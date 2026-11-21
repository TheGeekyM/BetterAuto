package com.simoneluconi.betterauto.assist

import android.content.Context
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarMotion
import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.map.MapFix
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.MapSource
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.map.roads.RoadInfo
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The driving assistant: a few spoken words at the right moment, each one a setting of its own. For the length of a
 * car session (the car connected, as [VehicleData] says): the brief at the start of a drive ([ArrivalBrief]), a rest
 * reminder after long driving ([RestTimer]), a soft chime over the road's limit ([SpeedAlert], the sign pulses
 * through [speeding]), the nearest station when the fuel runs low ([FuelStop]); at the end, where the car was left
 * ([ParkedCar], on the phone). Holds the position and the road under the car for the whole session, so the limit is
 * known with no map on screen. Main thread.
 */
object DrivingAssist {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var session: Job? = null
    private var sessionStart = 0L

    /** Lives across sessions: a reconnection with the last known (low) level offers nothing again; a refill re-arms it. */
    private val fuel = FuelAlert()

    /** The last position worth parking at: from a GPS (not an estimate), precise enough. */
    private var lastGood: ParkedCar? = null

    private val _speeding = MutableStateFlow(false)

    /** Over the road's limit right now (the speed limit sign pulses). */
    val speeding: StateFlow<Boolean> = _speeding

    fun init(context: Context) {
        if (app != null) return
        val context = context.applicationContext
        app = context
        AssistStore.init(context)
        Trip.init(context)
        scope.launch {
            VehicleData.snapshot.map { it.connected }.distinctUntilChanged().collect { connected ->
                if (connected) started(context) else if (session != null) ended(context)
            }
        }
        // Every new destination and when it was set: the habit the brief guesses the next one from
        scope.launch {
            Trip.places.map { it.lastOrNull() }.distinctUntilChanged().drop(1).collect { place ->
                if (place != null) AssistStore.addTripStart(context, TripStart(place.name, place.latitude, place.longitude, System.currentTimeMillis()))
            }
        }
    }

    private fun started(context: Context) {
        val now = System.currentTimeMillis()
        val settings = LauncherSettings.get(context)
        session?.cancel()
        sessionStart = now
        lastGood = null
        ParkedCarNotice.cancel(context)
        val briefDue = BriefRule.due(AssistStore.lastEndAt(context), now, AssistStore.briefOwed(context))
        EventLog.log("Assist: session started${if (briefDue) "" else ", a quick reconnection"}")
        session = scope.launch {
            launch { hold { MapLocation.request(context) } }
            launch { settings.assistSpeed.collectLatest { on -> if (on) hold { RoadInfo.request(context) } else _speeding.value = false } }
            launch { followParking(context) }
            if (briefDue && settings.assistBrief.value) {
                AssistStore.setBriefOwed(context, true)
                launch { brief(context) }
            }
            launch { watch(context) }
        }
    }

    private fun ended(context: Context) {
        session?.cancel()
        session = null
        _speeding.value = false
        val now = System.currentTimeMillis()
        AssistStore.setLastEnd(context, now)
        val settings = LauncherSettings.get(context)
        val parked = lastGood
        EventLog.log("Assist: session ended${if (parked != null) ", parking place kept" else ""}")
        if (!settings.assistParked.value || parked == null) return
        AssistStore.setParked(context, parked)
        // At once: the service stops with the session and the process may go. A link redone within moments
        // (wireless) takes it back at the next start
        if (settings.assistParkedNotify.value) ParkedCarNotice.post(context, parked)
    }

    /** Keeps what [take] holds until cancelled. */
    private suspend fun hold(take: () -> () -> Unit) {
        val release = take()
        try {
            awaitCancellation()
        } finally {
            release()
        }
    }

    private suspend fun followParking(context: Context) {
        MapLocation.fix.collect { fix ->
            if (fix == null || fix.source == MapSource.ESTIMATED || (fix.accuracyM ?: 0f) > PARK_ACCURACY_M) return@collect
            // Just arrived, not a position kept from before
            if (SystemClock.elapsedRealtime() - fix.at > FIX_FRESH_MS) return@collect
            lastGood = ParkedCar(fix.latitude, fix.longitude, System.currentTimeMillis(), RoadInfo.state.value?.name ?: lastGood?.road?.takeIf { near(fix) })
        }
    }

    private fun near(fix: MapFix): Boolean = lastGood?.let { LikelyDestination.metres(it.latitude, it.longitude, fix.latitude, fix.longitude) < 100 } == true

    private suspend fun brief(context: Context) {
        // The car's audio and sensors come up in the first seconds
        delay(BRIEF_DELAY_MS)
        if (!awaitFree(context)) return
        val text = ArrivalBrief.compose(context, sessionStart)
        // Putting it together may have taken a while: something else may be talking now
        if (!awaitFree(context) || !LauncherSettings.get(context).assistBrief.value) return
        if (AssistVoice.say(context, text)) AssistStore.setBriefOwed(context, false)
    }

    /** The timers: speed every second, rest and fuel every [SLOW_TICK_MS]. */
    private suspend fun watch(context: Context) = coroutineScope {
        val settings = LauncherSettings.get(context)
        val speed = SpeedAlert()
        val rest = AssistStore.restTimer(context)
        var slowAt = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (settings.assistSpeed.value) {
                val limit = RoadInfo.state.value?.limit?.kmh
                if (speed.update(now, speedKmh(now), limit)) {
                    EventLog.log("Assist: over the speed limit, chime")
                    if (AssistVoice.free(context)) AssistVoice.chime()
                }
                _speeding.value = speed.over
            }
            if (now - slowAt >= SLOW_TICK_MS) {
                slowAt = now
                if (settings.assistRest.value) {
                    val before = Triple(rest.stretchStart, rest.lastMovingAt, rest.lastReminderAt)
                    if (rest.update(now, moving(now))) {
                        EventLog.log("Assist: rest reminder after ${(now - rest.stretchStart) / 60_000} min of driving")
                        val res = AppLocales.voiceContext(context).resources
                        AssistVoice.say(context, res.getString(if (rest.firstReminder) R.string.assist_rest_first else R.string.assist_rest_again))
                    }
                    if (before != Triple(rest.stretchStart, rest.lastMovingAt, rest.lastReminderAt)) AssistStore.saveRest(context, rest)
                }
                if (settings.assistFuel.value) {
                    val f = VehicleData.snapshot.value.fuel?.value
                    if (f != null && fuel.update(f.levelPercent, f.rangeKm, settings.fuelReserve.value)) {
                        // Within the session: a drive that ends meanwhile offers nothing
                        launch { if (awaitFree(context)) FuelStop.offer(context) }
                    }
                }
            }
            delay(TICK_MS)
        }
    }

    /** The car's speed while fresh, else the GPS's. */
    private fun speedKmh(now: Long): Double? {
        VehicleData.snapshot.value.speed?.takeIf { now - it.at < SPEED_FRESH_MS }?.let { return it.value.kmh }
        val fix = MapLocation.fix.value ?: return null
        if (fix.source == MapSource.ESTIMATED || SystemClock.elapsedRealtime() - fix.at > SPEED_FRESH_MS) return null
        return fix.speedMs?.let { it * 3.6 }
    }

    /** Driving, as the car says; with a car that says nothing, as the GPS moves. */
    private fun moving(now: Long): Boolean = when (CarMotionState.state.value) {
        CarMotion.MOVING -> true
        CarMotion.STANDING -> false
        CarMotion.UNKNOWN -> (speedKmh(now) ?: 0.0) > MOVING_KMH
    }

    /** Waits until nothing else talks (a voice turn, a call); false when that took too long. */
    private suspend fun awaitFree(context: Context): Boolean = withTimeoutOrNull(FREE_WAIT_MS) {
        while (!AssistVoice.free(context)) delay(1_000)
        true
    } == true

    private const val TICK_MS = 1_000L
    private const val SLOW_TICK_MS = 15_000L
    private const val SPEED_FRESH_MS = 3_000L
    private const val FIX_FRESH_MS = 5_000L
    private const val MOVING_KMH = 8.0
    private const val PARK_ACCURACY_M = 50f
    private const val BRIEF_DELAY_MS = 6_000L
    private const val FREE_WAIT_MS = 120_000L
}
