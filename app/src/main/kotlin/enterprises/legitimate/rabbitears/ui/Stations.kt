package enterprises.legitimate.rabbitears.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.RabbitEarsApp
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import enterprises.legitimate.rabbitears.api.models.PlaylistEntryKind
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.Station
import enterprises.legitimate.rabbitears.api.models.StationOnAir
import enterprises.legitimate.rabbitears.api.models.StationOrder
import enterprises.legitimate.rabbitears.api.models.StationProgram
import enterprises.legitimate.rabbitears.api.models.StationTimeRange
import enterprises.legitimate.rabbitears.api.models.StationTiming
import enterprises.legitimate.rabbitears.api.models.StationVisibility
import enterprises.legitimate.rabbitears.api.models.Weekday
import enterprises.legitimate.rabbitears.kit.ArtworkPath
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.SmartDraft
import enterprises.legitimate.rabbitears.kit.StationForm
import enterprises.legitimate.rabbitears.kit.Stations
import enterprises.legitimate.rabbitears.kit.isEditable
import enterprises.legitimate.rabbitears.kit.title
import enterprises.legitimate.rabbitears.ui.theme.Palette
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

fun programArt(p: StationProgram): String =
    p.song?.let { ArtworkPath.item(it.albumId, it.posterUrl) }
        ?: if (p.kind == PlaylistEntryKind.movie)
            ArtworkPath.item(p.itemId, p.item?.backdropUrl, "backdrop")
        else ArtworkPath.item(p.itemId, p.item?.posterUrl)

@Composable
fun StationIdentity(
    name: String,
    modifier: Modifier,
    big: Boolean = false,
    imageUrl: String? = null,
) {
    if (imageUrl != null) return Art(imageUrl, Palette.raised, modifier)
    Column(
        modifier.background(Palette.raised).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_logo),
            null,
            Modifier.size(if (big) 72.dp else 28.dp),
            tint = Color.Unspecified,
        )
        Text(
            name,
            fontSize = if (big) 32.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StationArt(station: Station, modifier: Modifier, onAir: StationOnAir = station.onAir) {
    val program = onAir.now ?: onAir.next
    if (program == null || station.imageUrl != null)
        StationIdentity(station.name, modifier, imageUrl = station.imageUrl)
    else Art(programArt(program), artColor(station.id), modifier, PlaceholderKind.Station)
}

private fun shape(station: Station) = if (station.kind == PlaylistKind.music) 1f else 16f / 9

@Composable
private fun CaptionLines(lines: List<String>) {
    lines.forEachIndexed { i, line ->
        Text(
            line,
            fontSize = 12.sp,
            color = if (i == 0 && lines.size > 1) Palette.secondary else Palette.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A station's tile. Home's On now tile, [onNow], names the station with its kind, shows how far
 * through the program it is under a play arrow, and tunes in; its long press goes to the station.
 */
@Composable
fun StationTile(station: Station, modifier: Modifier = Modifier, onNow: Boolean = false) {
    val nav = LocalNav.current
    val process = LocalContext.current.applicationContext as RabbitEarsApp
    var menu by remember { mutableStateOf(false) }
    val tap =
        if (onNow)
            Modifier.combinedClickable(
                onClickLabel = Stations.onNowLabel(station),
                onLongClickLabel = "More actions",
                onLongClick = { menu = true },
                onClick = { tuneIn(nav, process, station) },
            )
        else Modifier.clickable { nav.open(Route.Station(station.id)) }
    Column(modifier.then(tap)) {
        Box(Modifier.fillMaxWidth().aspectRatio(shape(station)).clip(MaterialTheme.shapes.small)) {
            StationArt(station, Modifier.matchParentSize())
            if (onNow) {
                PlayMark()
                Stations.onNowFraction(station.onAir)?.let { TileBar(it, label = null) }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            MenuAction("Go to details") {
                menu = false
                nav.open(Route.Station(station.id))
            }
        }
        Text(
            if (onNow) Stations.onNowName(station) else station.name,
            Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CaptionLines(Stations.caption(station.onAir, station.kind))
    }
}

@Composable
private fun StationRow(station: Station) {
    val nav = LocalNav.current
    Row(
        Modifier.fillMaxWidth().clickable { nav.open(Route.Station(station.id)) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StationArt(
            station,
            Modifier.width(if (station.kind == PlaylistKind.music) 76.dp else 136.dp)
                .aspectRatio(shape(station))
                .clip(MaterialTheme.shapes.small),
        )
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CaptionLines(Stations.caption(station.onAir, station.kind))
        }
    }
}

fun LazyGridScope.stationCells(kind: PlaylistKind, nav: Navigator, wide: Boolean) {
    val all = nav.stations.of(kind)
    if (!nav.app.isKid)
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row {
                Pill(
                    "New station",
                    { nav.open(Route.StationEditor()) },
                    icon = R.drawable.ic_plus,
                )
            }
        }
    if (nav.stations.loaded && all.isEmpty())
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "No stations yet.",
                color = Palette.dim,
            )
        }
    if (wide) items(all, key = { it.id }) { StationTile(it) }
    else items(all, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { StationRow(it) }
}

/** Plays a station: a music one in the music player, which outlives this screen. */
fun tuneIn(nav: Navigator, app: RabbitEarsApp, station: Station) {
    if (station.kind != PlaylistKind.music) return nav.open(Route.Tune(station))
    val music = nav.music ?: return
    app.scope.launch { music.tune(station) }
    nav.open(Route.NowPlaying())
}

private fun kindName(kind: PlaylistKind) =
    when (kind) {
        PlaylistKind.movies -> "Movies station"
        PlaylistKind.tv -> "TV station"
        PlaylistKind.music -> "Music station"
    }

fun kindLabel(kind: PlaylistKind) =
    when (kind) {
        PlaylistKind.movies -> "Movies"
        PlaylistKind.tv -> "TV"
        PlaylistKind.music -> "Music"
    }

private fun visibilityName(v: StationVisibility) =
    when (v) {
        StationVisibility.private -> "Private"
        StationVisibility.household -> "Household"
        StationVisibility.server -> "Server"
    }

private fun orderName(o: StationOrder) =
    when (o) {
        StationOrder.playlist -> "In order"
        StationOrder.random -> "Shuffled"
    }

private fun time(at: OffsetDateTime) =
    Stations.time(at, ZoneId.systemDefault(), Locale.getDefault())

@Composable
fun StationScreen(id: String) {
    val nav = LocalNav.current
    val process = LocalContext.current.applicationContext as RabbitEarsApp
    val model = nav.stations
    LaunchedEffect(id, nav.app.live.stations) { model.get(id) }
    val station = model.stations.firstOrNull { it.id == id }
    val owned = station?.ownerId == nav.app.activeProfile?.id
    var version by remember { mutableStateOf(0) }
    val entries by
        produceState<List<PlaylistEntry>?>(null, station?.playlistId, version, owned) {
            value = station?.takeIf { owned }?.let { nav.playlists.entries(it.playlistId) }
        }
    val programs by
        produceState<List<StationProgram>?>(null, id, station?.modifiedAt) {
            value = model.schedule(id, LocalDate.now())
        }
    val wide = LocalWidth.current != WidthClass.Compact
    // Use rules asks first, then edits new rules; Edit rules opens the saved ones.
    var asking by remember { mutableStateOf(false) }
    var ruling by remember { mutableStateOf<SmartDraft?>(null) }
    var editing by remember { mutableStateOf(false) }
    var rescheduling by remember { mutableStateOf<Station?>(null) }
    rescheduling?.let { RescheduleQuestion(it) { rescheduling = null } }
    val saveRules: suspend (SmartDraft) -> Boolean = { draft ->
        val playlistId = station?.playlistId
        (playlistId != null && nav.playlists.saveRules(playlistId, draft)).also {
            if (it) {
                version++
                rescheduling = model.get(id)
            }
        }
    }
    if (asking && station != null)
        AlertDialog(
            { asking = false },
            confirmButton = {
                TextButton(
                    {
                        asking = false
                        ruling = SmartDraft(station.kind, station.name)
                    },
                ) {
                    Text("Use rules")
                }
            },
            dismissButton = { TextButton({ asking = false }) { Text("Cancel") } },
            title = { Text("Use rules?") },
            text = { Text("Replaces this station's titles with those the rules match.") },
            containerColor = Palette.raised,
        )
    ruling?.let {
        SmartRulesDialog("Use rules", it, { saveRules(it) }, named = false) { ruling = null }
    }
    if (editing && station != null)
        EditRules(
            { nav.playlists.draft(station.playlistId, station.kind, station.name) },
            saveRules,
            named = false,
        ) {
            editing = false
        }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BackBar("Back") }
        model.error?.let { item { Text(it, color = Palette.accent) } }
        station ?: return@LazyColumn
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StationArt(
                    station,
                    Modifier.width(if (wide) 280.dp else 136.dp)
                        .aspectRatio(shape(station))
                        .clip(MaterialTheme.shapes.medium),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(station.name, style = MaterialTheme.typography.headlineMedium)
                    if (station.smart == true) SmartBadge()
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(kindName(station.kind), color = Palette.dim)
                        Tag(station.rating ?: "None")
                        Text(visibilityName(station.visibility), color = Palette.dim)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    "Tune in",
                    { tuneIn(nav, process, station) },
                    primary = true,
                    icon = R.drawable.ic_play,
                )
                if (owned) {
                    Pill("Edit station", { nav.open(Route.StationEditor(station.id)) })
                    if (station.smart == true)
                        Pill("Edit rules…", { editing = true }, enabled = !nav.app.offline)
                    else Pill("Use rules…", { asking = true }, enabled = !nav.app.offline)
                    StationImageActions(station) { model.get(id) }
                }
            }
        }
        item {
            val order =
                if (station.order == StationOrder.random) "Plays in random order"
                else "Plays in order"
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(order, color = Palette.secondary)
                Text(Stations.caption(station.onAir, station.kind).joinToString(". "))
            }
        }
        if (owned) {
            item { SectionTitle("Contents") }
            entryRows(
                nav,
                station.kind,
                entries,
                // A station's songs are not a queue; the song's album is the way in.
                { entry -> entry.song?.let { nav.open(Route.Album(it.albumId)) } },
                // A station's own playlist is listed nowhere and is the owner's to edit.
                editable =
                    station.smart != true &&
                        nav.playlists.playlists
                            .firstOrNull { it.id == station.playlistId }
                            ?.isEditable ?: true,
            ) {
                version++
            }
        }
        item { SectionTitle("Today") }
        if (programs?.isEmpty() == true)
            item { Text("Nothing scheduled today.", color = Palette.dim) }
        items(programs.orEmpty(), key = { "${it.startsAt}" }) { p ->
            ScheduleRow(p, on = station.onAir.now?.startsAt == p.startsAt)
        }
        item { Box(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ScheduleRow(p: StationProgram, on: Boolean) {
    val past = !on && p.endsAt.toInstant().isBefore(java.time.Instant.now())
    val color = if (on) Palette.accent else if (past) Palette.dim else Palette.text
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            time(p.startsAt),
            Modifier.width(88.dp),
            color = if (on) Palette.accent else Palette.dim,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        )
        Column(Modifier.weight(1f)) {
            Text(
                p.title,
                color = color,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (on) {
                val left = p.endsAt.toInstant().toEpochMilli() - System.currentTimeMillis()
                Text(
                    "On now, ${Format.remaining(maxOf(0, left))}",
                    color = Palette.accent,
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            Format.minutes(p.durationMs),
            color = Palette.dim,
            fontSize = 13.sp,
        )
    }
}

@Composable
fun <T> MenuField(
    label: String,
    value: String,
    options: List<T>,
    name: (T) -> String,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value,
            {},
            Modifier.fillMaxWidth(),
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_down), null) },
        )
        if (enabled) Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach {
                MenuAction(name(it)) {
                    onPick(it)
                    open = false
                }
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** A From or To field holding `HH:mm` in the server's time, shown in the device's format. */
@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val time = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.MIDNIGHT)
    Box(modifier) {
        OutlinedTextField(
            time.format(timeFormat),
            {},
            Modifier.fillMaxWidth(),
            readOnly = true,
            label = { Text(label) },
        )
        Box(
            Modifier.matchParentSize().clickable {
                TimePickerDialog(
                        context,
                        { _, h, m -> onPick("%02d:%02d".format(Locale.US, h, m)) },
                        time.hour,
                        time.minute,
                        DateFormat.is24HourFormat(context),
                    )
                    .show()
            },
        )
    }
}

@Composable
private fun Card(selected: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) Palette.indicator.copy(alpha = 0.18f) else Palette.raised)
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected,
            onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Palette.accent),
        )
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Now ends the schedule after what is on; Later keeps it. [onDone] runs after either. */
@Composable
fun RescheduleQuestion(station: Station, onDone: () -> Unit) {
    val model = LocalNav.current.stations
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDone,
        confirmButton = {
            TextButton(
                {
                    busy = true
                    scope.launch {
                        val done = model.reschedule(station) != null
                        busy = false
                        if (done) onDone()
                    }
                },
                enabled = !busy,
            ) {
                Text("Now")
            }
        },
        dismissButton = { TextButton(onDone, enabled = !busy) { Text("Later") } },
        title = { Text("Reschedule now?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Stations.RESCHEDULE_ANSWERS)
                model.error?.let { Text(it, color = Palette.accent) }
            }
        },
        containerColor = Palette.raised,
    )
}

/** The type is chosen once, when the station is made. A 400's field errors show on their fields. */
@Composable
fun StationEditor(stationId: String?) {
    val nav = LocalNav.current
    val model = nav.stations
    val scope = rememberCoroutineScope()
    val station = stationId?.let { id -> model.stations.firstOrNull { it.id == id } }
    val form = remember(station?.id) { StationForm(station) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var rescheduling by remember { mutableStateOf<Station?>(null) }
    val errors = model.fieldErrors
    val run = { block: suspend () -> Unit ->
        busy = true
        scope.launch {
            block()
            busy = false
        }
        Unit
    }
    val save = {
        run {
            val saved = model.save(station?.id, form.input) ?: return@run
            if (station != null) rescheduling = saved
            else {
                nav.back()
                nav.open(Route.Station(saved.id))
            }
        }
    }
    rescheduling?.let { RescheduleQuestion(it) { nav.back() } }
    if (deleting)
        AlertDialog(
            { deleting = false },
            confirmButton = {
                TextButton(
                    {
                        deleting = false
                        station?.let {
                            run {
                                if (model.delete(it)) {
                                    nav.back()
                                    if (nav.stack.lastOrNull() == Route.Station(it.id)) nav.back()
                                }
                            }
                        }
                    },
                ) {
                    Text("Delete", color = Color(0xFFFF6B6B))
                }
            },
            dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
            title = { Text("Delete ${station?.name}?") },
            text = { Text("Its schedule and contents go with it.") },
            containerColor = Palette.raised,
        )

    val details =
        @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    form.name,
                    { form.name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    isError = errors["name"] != null,
                    supportingText = errors["name"]?.let { { Text(it) } },
                )
                MenuField(
                    "Type",
                    kindLabel(form.kind),
                    PlaylistKind.entries,
                    ::kindLabel,
                    error = errors["kind"],
                    enabled = station == null,
                ) {
                    form.choose(it)
                }
                MenuField(
                    "Timing",
                    Stations.timingName(form.timing),
                    StationTiming.entries,
                    Stations::timingName,
                    error = errors["timing"],
                ) {
                    form.timing = it
                }
                Text(
                    "When each program starts after the one before ends.",
                    color = Palette.dim,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MenuField(
                        "Order",
                        orderName(form.order),
                        StationOrder.entries,
                        ::orderName,
                        Modifier.weight(1f),
                        errors["order"],
                    ) {
                        form.order = it
                    }
                    val server = form.visibility == StationVisibility.server
                    MenuField(
                        "Visibility",
                        visibilityName(form.visibility),
                        listOf(StationVisibility.private, StationVisibility.household),
                        ::visibilityName,
                        Modifier.weight(1f),
                        errors["visibility"],
                        enabled = !server,
                    ) {
                        form.visibility = it
                    }
                }
                Text(
                    "Only a server admin can make a station server wide.",
                    color = Palette.dim,
                    fontSize = 12.sp,
                )
                station?.let { s ->
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Rating", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "The highest rating of its contents. Recalculated hourly.",
                                    color = Palette.secondary,
                                    fontSize = 12.sp,
                                )
                            }
                            Tag(s.rating ?: "None")
                        }
                    }
                    TextButton({ deleting = true }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Delete station", color = Color(0xFFFF6B6B))
                    }
                }
            }
        }
    val schedule =
        @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Schedule")
                Card(form.continuous) {
                    Choice("Continuous", form.continuous) { form.continuous = true }
                }
                Card(!form.continuous) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Chosen days and times", !form.continuous) {
                            form.continuous = false
                        }
                        Text(
                            "Server time. A program still playing when its time range ends plays to its end.",
                            color = Palette.secondary,
                            fontSize = 12.sp,
                        )
                        if (!form.continuous) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Weekday.entries.forEach { day ->
                                    Chip(
                                        java.time.DayOfWeek.valueOf(day.name.uppercase())
                                            .getDisplayName(
                                                TextStyle.SHORT,
                                                LocalLocale.current.platformLocale,
                                            ),
                                        day in form.days,
                                    ) {
                                        form.days =
                                            if (day in form.days) form.days - day
                                            else form.days + day
                                    }
                                }
                            }
                            form.times.forEachIndexed { i, range ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val set = { r: StationTimeRange ->
                                        form.times =
                                            form.times.mapIndexed { j, old ->
                                                if (j == i) r else old
                                            }
                                    }
                                    TimeField("From", range.from, Modifier.weight(1f)) {
                                        set(range.copy(from = it))
                                    }
                                    TimeField("To", range.to, Modifier.weight(1f)) {
                                        set(range.copy(to = it))
                                    }
                                    IconAction(
                                        R.drawable.ic_close,
                                        "Remove time range",
                                        { form.removeRange(i) },
                                    )
                                }
                            }
                            TextButton(form::addRange) {
                                Icon(
                                    painterResource(R.drawable.ic_plus),
                                    null,
                                    tint = Palette.accent,
                                )
                                Text(
                                    "Add a time range",
                                    Modifier.padding(start = 8.dp),
                                    color = Palette.accent,
                                )
                            }
                            errors
                                .filterKeys { it.startsWith("days") || it.startsWith("times") }
                                .values
                                .forEach { Text(it, color = Palette.accent, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconAction(R.drawable.ic_close, "Close", { nav.back() })
            Text(
                if (station == null) "New station" else "Edit station",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp),
            )
            Pill("Save", save, primary = true, enabled = form.ready && !busy)
        }
        model.error?.let { Text(it, Modifier.padding(bottom = 8.dp), color = Palette.accent) }
        if (LocalWidth.current != WidthClass.Compact)
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Box(Modifier.weight(1f)) { details() }
                Box(Modifier.weight(1f)) { schedule() }
            }
        else
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                details()
                schedule()
            }
        Box(Modifier.height(24.dp))
    }
}
