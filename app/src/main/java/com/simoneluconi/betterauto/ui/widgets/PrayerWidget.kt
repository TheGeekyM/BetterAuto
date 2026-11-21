package com.simoneluconi.betterauto.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.prayer.DayTimes
import com.simoneluconi.betterauto.prayer.Prayer
import com.simoneluconi.betterauto.prayer.PrayerPlace
import com.simoneluconi.betterauto.prayer.PrayerTimes
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LocalFormatLocale
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.glass
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The next prayer (its name, time and how long until it) and today's five times, computed on the phone
 * ([PrayerTimes]) for where the car is, else where the phone last was, else the city of the settings (then named).
 * Look-only, like the clock: nothing to press, no page of its own.
 */
@Composable
fun PrayerWidget(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val now = rememberMinute()
    val method by settings.prayerMethod.collectAsStateWithLifecycle()
    val asr by settings.asrJuristic.collectAsStateWithLifecycle()
    val city by settings.prayerCity.collectAsStateWithLifecycle()
    // Rounded to about a kilometre: the fix changes every second, the times by seconds over kilometres
    val position by remember { MapLocation.fix.map { f -> f?.let { PrayerPlace.round(it.latitude) to PrayerPlace.round(it.longitude) } }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(MapLocation.fix.value?.let { PrayerPlace.round(it.latitude) to PrayerPlace.round(it.longitude) })
    val lastKnown = remember { PrayerPlace.lastKnown(context) }
    val place = remember(position, lastKnown, city) { PrayerPlace.of(position ?: lastKnown, city) }
    val zone = now.zone
    val days = remember(place, method, asr, zone) {
        val cache = HashMap<LocalDate, DayTimes>()
        val get: (LocalDate) -> DayTimes = { date -> cache.getOrPut(date) { PrayerTimes.day(date, place.latitude, place.longitude, zone, method, asr) } }
        get
    }
    val today = days(now.toLocalDate())
    // At the prayer's own minute it is "now", then the next one
    val shown = PrayerTimes.last(now, days)?.takeIf { it.second == now } ?: PrayerTimes.next(now, days)
    val h24 = is24Hours(settings.clockStyle.collectAsStateWithLifecycle().value, context, LocalFormatLocale.current)
    fun time(t: ZonedDateTime) = t.withZoneSameInstant(zone).format(if (h24) Clock24 else Clock12)
    val countdown = shown?.let { (_, at) ->
        val minutes = Duration.between(now, at).toMinutes()
        when {
            minutes <= 0 -> stringResource(R.string.prayer_now)
            minutes < 60 -> stringResource(R.string.prayer_in_m, minutes.toInt())
            else -> stringResource(R.string.prayer_in_hm, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    BoxWithConstraints(
        modifier
            .glass(RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .padding(24.dp),
    ) {
        val small = maxHeight < 200.dp
        val rowsFit = maxHeight >= 380.dp
        // Five chips fit in about 330 dp; under the next prayer even in a small place
        val chipsFit = maxWidth >= 330.dp && maxHeight >= 170.dp
        val list = when {
            rowsFit && maxHeight > maxWidth * 0.9f -> ListStyle.ROWS
            chipsFit -> ListStyle.CHIPS
            rowsFit -> ListStyle.ROWS
            else -> ListStyle.NONE
        }
        val big = minOf(maxHeight.value * if (small && list == ListStyle.NONE) 0.32f else if (small) 0.22f else 0.2f, maxWidth.value * 0.11f).coerceIn(26f, 56f)
        val chipName = if (small) 15.sp else 17.sp
        val chipTime = if (small) 18.sp else 20.sp
        val title = stringResource(R.string.widget_prayer)
        val cityName = place.city?.let { stringResource(R.string.prayer_for_city, stringResource(it.label)) }

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (small) 4.dp else 10.dp)) {
            if (!small) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CarIcon(CarIcons.Mosque, colors.text2, Modifier.size(26.dp))
                    CarLabel(title, 24.sp, colors.text, Modifier.weight(1f, fill = false), weight = FontWeight.ExtraBold)
                    cityName?.let { CarLabel(it, 17.sp, colors.text2, weight = FontWeight.Bold, shrinkToFit = true) }
                }
            }
            if (shown != null) {
                val (prayer, at) = shown
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (small) CarIcon(CarIcons.Mosque, colors.accent, Modifier.size((big * 0.8f).dp))
                    CarLabel(stringResource(prayer.label), big.sp, colors.text, Modifier.weight(1f, fill = false), weight = FontWeight.ExtraBold, shrinkToFit = true)
                    CarLabel(time(at), big.sp, colors.accent, weight = FontWeight.ExtraBold, tabularNumbers = true)
                }
                countdown?.let { CarLabel(it, (big * 0.42f).coerceIn(18f, 24f).sp, colors.text2, weight = FontWeight.Bold, tabularNumbers = true) }
            }
            if (list != ListStyle.NONE) {
                Spacer(Modifier.weight(1f))
                val highlight = shown?.takeIf { it.second.toLocalDate() == today.date }?.first
                val five = Prayer.entries.filter { it.isPrayer }
                if (list == ListStyle.CHIPS) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in five) {
                            val next = p == highlight
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .then(if (next) Modifier.background(colors.surface2) else Modifier)
                                    .padding(vertical = if (small) 6.dp else 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CarLabel(stringResource(p.label), chipName, if (next) colors.accent else colors.text2, weight = FontWeight.Bold, shrinkToFit = true)
                                CarLabel(today[p]?.let(::time) ?: "–", chipTime, if (next) colors.accent else colors.text, weight = FontWeight.Bold, tabularNumbers = true, shrinkToFit = true)
                            }
                        }
                    }
                } else {
                    Column {
                        five.forEachIndexed { i, p ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                            val next = p == highlight
                            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                                CarLabel(stringResource(p.label), 18.sp, if (next) colors.accent else colors.text2, Modifier.weight(1f), weight = FontWeight.Bold)
                                CarLabel(today[p]?.let(::time) ?: "–", 19.sp, if (next) colors.accent else colors.text, weight = FontWeight.Bold, tabularNumbers = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ListStyle { ROWS, CHIPS, NONE }
