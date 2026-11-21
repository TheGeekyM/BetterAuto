package com.simoneluconi.betterauto.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.DestinationActivity
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.CarMotion
import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.distanceM
import com.simoneluconi.betterauto.map.nav.Destination
import com.simoneluconi.betterauto.map.nav.Destinations
import com.simoneluconi.betterauto.map.nav.Navigation
import com.simoneluconi.betterauto.map.nav.PlaceResult
import com.simoneluconi.betterauto.map.nav.PlaceSearch
import com.simoneluconi.betterauto.map.nav.RoutePlanning
import com.simoneluconi.betterauto.map.nav.Trip
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.glass
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import com.simoneluconi.betterauto.ui.vehicle.rememberUnits
import kotlinx.coroutines.delay

/**
 * "Where to?" over the map, on two pages. The main one, harmless choices first and the ones that end something
 * last (each asks for a second press): the voice guidance, search (typed here, see [CarSearchPage]), search on the phone, the route asked again when none came, "Edit trip", skip the next stop, end the navigation; then the recent
 * destinations (not those already in the trip), which during a trip ask whether they are a stop or the new
 * destination. "Edit trip" shows the trip's places instead, moved and removed only with the car standing (on the
 * way that is for the voice or a passenger with the phone; the list still shows). Opened by pressing the knob on
 * the map or the destinations button; takes the knob on its first row and keeps it; Back goes one level back,
 * a tap outside closes it, and so does a new destination set meanwhile on the phone.
 */
@Composable
fun DestinationPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val units = rememberUnits()
    val places by Trip.places.collectAsStateWithLifecycle()
    val recents by Destinations.recents.collectAsStateWithLifecycle()
    val planning by Navigation.planning.collectAsStateWithLifecycle()
    val settings = remember { LauncherSettings.get(context) }
    val guidance by settings.guidanceVoice.collectAsStateWithLifecycle()
    val motion by CarMotionState.state.collectAsStateWithLifecycle()
    var searchSent by remember { mutableStateOf(false) }
    var tripPage by remember { mutableStateOf(false) }
    var searchPage by remember { mutableStateOf(false) }
    val searchFirst = remember { FocusRequester() }
    val searchRow = remember { FocusRequester() }
    val pick = remember { TripPick() }
    val first = remember { FocusRequester() }
    val editRow = remember { FocusRequester() }
    val tripBack = remember { FocusRequester() }
    // Which page the focus goes to once it is drawn: the first row, the trip page's back, or "Edit trip" again
    var focusTarget by remember { mutableStateOf<FocusRequester?>(first) }
    LaunchedEffect(Unit) { Destinations.init(context) }
    LaunchedEffect(focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        // The page just changed: the control that had the focus went with it, and the focus is dropped a frame
        // later; asked too soon, it would be dropped too. Asked twice (again on a focused control changes nothing)
        repeat(2) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
        focusTarget = null
    }
    // No trip left to edit: back to the main page
    LaunchedEffect(places.isEmpty()) {
        if (places.isEmpty() && tripPage) {
            tripPage = false
            focusTarget = first
        }
    }
    fun back() {
        when {
            pick.active -> pick.cancel()
            searchPage -> {
                searchPage = false
                focusTarget = searchRow
            }
            tripPage -> {
                tripPage = false
                focusTarget = editRow
            }
            else -> onClose()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // A tap outside the card closes it
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .padding(18.dp)
                // The keyboard needs the width: its page takes the screen, the menu stays a card at the side
                .widthIn(max = if (searchPage) 1200.dp else 520.dp)
                .then(if (searchPage) Modifier.fillMaxWidth() else Modifier)
                .fillMaxHeight()
                .shadow(10.dp, RoundedCornerShape(28.dp))
                .glass(RoundedCornerShape(28.dp)) { it.glass }
                .clip(RoundedCornerShape(28.dp))
                // Taps on the card stay on the card
                .pointerInput(Unit) { detectTapGestures { } }
                .navOverlay(if (searchPage) searchFirst else if (tripPage) tripBack else first, ::back)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (searchPage) {
                CarSearchPage(searchFirst, ::back) { place ->
                    // As a recent place: during a trip the driver then says whether it is a stop or the destination
                    Trip.choose(place)
                    onClose()
                }
                return@Column
            }
            if (tripPage) {
                TripPage(places, standing = motion != CarMotion.MOVING, pick, tripBack, ::back)
                return@Column
            }
            // During a trip it is the navigation's own menu, not just a search
            CarLabel(
                stringResource(if (places.isEmpty()) R.string.nav_where_to else R.string.nav_card_title), 26.sp, colors.text,
                weight = FontWeight.ExtraBold,
            )
            // Room around the rows for their focus ring, which the scrolling column would cut
            Column(Modifier.weight(1f).verticalScrollWithBar().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var focusGiven = false
                fun firstFocus(): Modifier = if (focusGiven) Modifier else Modifier.focusRequester(first).also { focusGiven = true }

                if (places.isNotEmpty()) {
                    PanelRow(guidance.icon(), stringResource(R.string.nav_voice), stringResource(guidance.label()), firstFocus()) {
                        settings.setGuidanceVoice(guidance.next())
                    }
                }
                PanelRow(
                    CarIcons.Search, stringResource(R.string.nav_search_car),
                    stringResource(R.string.nav_search_car_desc),
                    firstFocus().focusRequester(searchRow),
                ) {
                    searchPage = true
                    focusTarget = searchFirst
                }
                PanelRow(
                    CarIcons.Phone, stringResource(R.string.nav_search_on_phone),
                    if (searchSent) stringResource(R.string.nav_search_on_phone_desc) else null, firstFocus(),
                ) {
                    DestinationActivity.notifySearch(context)
                    searchSent = true
                }
                if (places.isNotEmpty()) {
                    // No route came: asked again here, with the knob
                    if (planning == RoutePlanning.NO_ROUTE || planning == RoutePlanning.OFFLINE) {
                        PanelRow(CarIcons.Recenter, stringResource(R.string.nav_retry), planningText(planning, places), Modifier) {
                            Navigation.retry()
                        }
                    }
                    val standing = motion != CarMotion.MOVING
                    val stops = places.size - 1
                    val summary = listOfNotNull(
                        if (stops > 0) pluralStringResource(R.plurals.nav_card_stops, stops, stops) else null,
                        stringResource(R.string.nav_card_trip, places.last().name),
                    ).joinToString(" · ")
                    PanelRow(
                        CarIcons.Map, stringResource(R.string.nav_edit_trip_open),
                        if (standing) summary else stringResource(R.string.nav_edit_when_standing), Modifier.focusRequester(editRow),
                    ) {
                        tripPage = true
                        focusTarget = tripBack
                    }
                    if (places.size > 1) {
                        val stop = places.first()
                        ConfirmRow(CarIcons.Next, stringResource(R.string.nav_skip_stop), stop.name, stringResource(R.string.nav_confirm_skip, stop.name)) {
                            Trip.remove(stop)
                        }
                    }
                    ConfirmRow(CarIcons.Close, stringResource(R.string.nav_stop), null, stringResource(R.string.nav_confirm_end)) {
                        Navigation.end()
                        onClose()
                    }
                }
                // The recent places, not the ones already in the trip
                val shownRecents = recents.filter { recent -> places.none { same(it, recent) } }
                CarLabel(stringResource(R.string.nav_recents), 18.sp, colors.text2, weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                if (shownRecents.isEmpty()) {
                    CarLabel(stringResource(R.string.nav_no_recents), 18.sp, colors.text2)
                }
                for (place in shownRecents) {
                    val distance = MapLocation.fix.value?.let { distanceM(it.latitude, it.longitude, place.latitude, place.longitude) }
                    PanelRow(
                        CarIcons.Map, place.name,
                        listOfNotNull(place.address, distance?.let { distanceText(it, units) }).joinToString(" · ").ifBlank { null },
                        Modifier,
                    ) {
                        Trip.choose(place)
                        onClose()
                    }
                }
            }
        }
    }
}

/** The trip's places, to move and remove with the car standing; read only on the way (said on the page). */
@Composable
private fun TripPage(places: List<Destination>, standing: Boolean, pick: TripPick, backFocus: FocusRequester, onBack: () -> Unit) {
    val colors = LocalCarColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Back to the main page, for a finger (the knob's Back does the same)
        Box(
            Modifier
                .focusRequester(backFocus)
                .carClickable(CircleShape, stringResource(R.string.music_back)) { onBack() }
                .background(colors.surface2, CircleShape)
                .size(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
        }
        CarLabel(stringResource(R.string.nav_edit_trip), 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold, shrinkToFit = true)
    }
    Column(Modifier.verticalScrollWithBar().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TripPlaces(places, editable = standing && places.size > 1, pick = pick)
        if (!standing && places.size > 1) CarLabel(stringResource(R.string.nav_edit_when_standing), 16.sp, colors.text2, maxLines = 3)
    }
}

/**
 * Search typed in the car: the field, a keyboard (English or Arabic, switched by its first key in the last row)
 * and what was found, chosen like a recent place. It searches once the typing pauses, or at once with the search
 * key. Typing works on the way too: the owner asked for it (a passenger, a red light). Wide screens show the keyboard and the results side by side; narrow ones one at a time (the
 * search key shows the results, the field brings the keyboard back).
 */
@Composable
private fun CarSearchPage(firstFocus: FocusRequester, onBack: () -> Unit, onChoose: (Destination) -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val units = rememberUnits()
    var query by rememberSaveable { mutableStateOf("") }
    var arabic by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PlaceResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(true) }
    // The search key: the same search without waiting for the pause
    var now by remember { mutableStateOf(false) }
    var searches by remember { mutableStateOf(0) }
    LaunchedEffect(query, searches) {
        if (query.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        if (!now) delay(TYPING_PAUSE_MS)
        now = false
        searching = true
        // A new key cancels it: never left "searching" for a search nobody waits for
        try {
            results = PlaceSearch.search(context, query)
        } finally {
            searching = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_SEARCH
        val keyboard = wide || typing
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        // No keyboard: the knob starts here
                        .then(if (keyboard) Modifier else Modifier.focusRequester(firstFocus))
                        .carClickable(CircleShape, stringResource(R.string.music_back)) { onBack() }
                        .background(colors.surface2, CircleShape)
                        .size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
                }
                // The field: shows what is typed; on a narrow screen it brings the keyboard back
                val shape = RoundedCornerShape(26.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .carClickable(shape, stringResource(R.string.nav_search_hint)) { typing = true }
                        .background(colors.surface2, shape)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (query.isEmpty()) {
                        CarLabel(stringResource(R.string.nav_search_hint), 21.sp, colors.text2, maxLines = 1)
                    } else {
                        CarLabel(query, 21.sp, colors.text, weight = FontWeight.Bold, maxLines = 1, shrinkToFit = true)
                    }
                }
            }
            val found: @Composable (Modifier) -> Unit = { m ->
                Column(m.verticalScrollWithBar().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val shown = results
                    when {
                        searching && shown == null -> CarLabel(stringResource(R.string.nav_searching), 18.sp, colors.text2)
                        shown != null && shown.isEmpty() -> CarLabel(stringResource(R.string.nav_search_none), 18.sp, colors.text2)
                        shown != null -> {
                            for (r in shown) {
                                val d = r.destination
                                PanelRow(
                                    CarIcons.Map, d.name,
                                    listOfNotNull(d.address, r.distanceM?.let { distanceText(it, units) }).joinToString(" · ").ifBlank { null },
                                    Modifier,
                                ) { onChoose(d) }
                            }
                            // The places are the geocoder's and OpenStreetMap's: credited as the licence asks
                            CarLabel(stringResource(R.string.nav_search_attribution), 12.sp, colors.text2, maxLines = 1)
                        }
                    }
                }
            }
            val keys: @Composable (Modifier) -> Unit = { m ->
                SearchKeyboard(
                    arabic, firstFocus, m,
                    onKey = { if (query.length < MAX_QUERY) query += it },
                    onDelete = { query = query.dropLast(1) },
                    onSwitch = { arabic = !arabic },
                    onSearch = {
                        typing = false
                        now = true
                        searches++
                    },
                )
            }
            when {
                wide && keyboard -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    keys(Modifier.weight(1.5f).fillMaxHeight())
                    found(Modifier.weight(1f).fillMaxHeight())
                }
                keyboard -> keys(Modifier.weight(1f).fillMaxWidth())
                else -> found(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * The car's keyboard: digits, then the letters (QWERTY or the Arabic layout), then switch layout, space, delete
 * and search. Shorter rows are centred, every key the same width. [firstFocus] goes on the first letter.
 */
@Composable
private fun SearchKeyboard(
    arabic: Boolean, firstFocus: FocusRequester, modifier: Modifier,
    onKey: (String) -> Unit, onDelete: () -> Unit, onSwitch: () -> Unit, onSearch: () -> Unit,
) {
    val colors = LocalCarColors.current
    val rows = if (arabic) ArabicKeys else LatinKeys
    val columns = rows.maxOf { it.length }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val side = (columns - row.length) / 2f
                if (side > 0) Spacer(Modifier.weight(side))
                row.forEachIndexed { i, ch ->
                    val focus = if (r == 1 && i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    Key(Modifier.weight(1f).then(focus), ch.toString(), { onKey(ch.toString()) }) {
                        CarLabel(ch.toString(), 24.sp, colors.text, weight = FontWeight.Bold)
                    }
                }
                if (side > 0) Spacer(Modifier.weight(side))
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(Modifier.weight(1.6f), stringResource(R.string.nav_key_layout), onSwitch) {
                CarLabel(if (arabic) "ABC" else "عربي", 20.sp, colors.text, weight = FontWeight.Bold)
            }
            // The hamza forms, on a phone a long press away: names like أكتوبر, الإسكندرية need them
            if (arabic) for (ch in ArabicExtra) {
                Key(Modifier.weight(1f), ch.toString(), { onKey(ch.toString()) }) {
                    CarLabel(ch.toString(), 24.sp, colors.text, weight = FontWeight.Bold)
                }
            }
            Key(Modifier.weight(if (arabic) 2.5f else 4f), stringResource(R.string.nav_key_space), { onKey(" ") }) {
                CarLabel(stringResource(R.string.nav_key_space), 18.sp, colors.text2)
            }
            Key(Modifier.weight(1.6f), stringResource(R.string.nav_key_delete), onDelete) {
                CarIcon(CarIcons.Backspace, colors.text, Modifier.size(26.dp))
            }
            Key(Modifier.weight(1.6f), stringResource(R.string.nav_search_car), onSearch, background = colors.accent) {
                CarIcon(CarIcons.Search, colors.onAccent, Modifier.size(26.dp))
            }
        }
    }
}

/** One key: the dialpad's style, a rounded tile that takes touch and the knob. */
@Composable
private fun Key(
    modifier: Modifier, label: String, onClick: () -> Unit,
    background: androidx.compose.ui.graphics.Color = LocalCarColors.current.surface2,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxHeight()
            .carClickable(shape, label, onClick = onClick)
            .background(background, shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private val LatinKeys = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")

// The usual Arabic layout (as on Arabic PC keyboards and phones)
private val ArabicKeys = listOf("1234567890", "ضصثقفغعهخحج", "شسيبلاتنمكط", "ئءؤرىةوزظدذ")
private const val ArabicExtra = "أإآ"

/** Why there is no route, in words: being calculated, none to [places]' destination, or no server. */
@Composable
internal fun planningText(planning: RoutePlanning?, places: List<Destination>): String? = when (planning) {
    RoutePlanning.CALCULATING -> stringResource(R.string.nav_calculating)
    RoutePlanning.NO_ROUTE -> stringResource(R.string.nav_no_route, places.lastOrNull()?.name.orEmpty())
    RoutePlanning.OFFLINE -> stringResource(R.string.nav_offline)
    null -> null
}

/** Within about 50 m: the same place, whatever it was called. */
private fun same(a: Destination, b: Destination) = distanceM(a.latitude, a.longitude, b.latitude, b.longitude) < SAME_PLACE_M

/** One choice in the panel: an icon in a circle, its title and a line under it. */
@Composable
private fun PanelRow(icon: ImageVector, title: String, detail: String?, modifier: Modifier, asking: Boolean = false, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            // Before the clip: the focus ring is drawn just outside the row
            .carClickable(shape, title, onClick = onClick)
            .clip(shape)
            .background(if (asking) colors.accent else colors.surface2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
            CarIcon(icon, colors.text, Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            CarLabel(title, 20.sp, if (asking) colors.onAccent else colors.text, weight = FontWeight.Bold, maxLines = 2, shrinkToFit = true)
            detail?.let { CarLabel(it, 16.sp, if (asking) colors.onAccent else colors.text2, maxLines = 2) }
        }
    }
}

/**
 * A choice that ends something: the first press turns it into the question ([confirm]), a second one within a few
 * seconds acts, so the knob never ends a trip by a slip; left alone, it goes back by itself.
 */
@Composable
private fun ConfirmRow(icon: ImageVector, title: String, detail: String?, confirm: String, onConfirmed: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) {
            delay(CONFIRM_MS)
            asking = false
        }
    }
    PanelRow(icon, if (asking) confirm else title, if (asking) null else detail, Modifier, asking = asking) {
        if (asking) {
            asking = false
            onConfirmed()
        } else {
            asking = true
        }
    }
}

private const val CONFIRM_MS = 4_000L
private const val TYPING_PAUSE_MS = 700L
private const val MAX_QUERY = 80
private val WIDE_SEARCH = 760.dp
private const val SAME_PLACE_M = 50.0
