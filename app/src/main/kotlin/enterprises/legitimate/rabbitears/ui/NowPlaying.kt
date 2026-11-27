package enterprises.legitimate.rabbitears.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.AudioDetails
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.kit.AudioQueue
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.MusicPlayer
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.RepeatMode
import enterprises.legitimate.rabbitears.kit.StationTuner
import enterprises.legitimate.rabbitears.kit.Stations
import enterprises.legitimate.rabbitears.kit.audioDetails
import enterprises.legitimate.rabbitears.kit.length
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The music position, polled for the screens that show it; the song's length comes with it. */
@Composable
private fun rememberClock(player: MusicPlayer): Pair<Long, Long> {
    var now by remember { mutableLongStateOf(player.positionMs) }
    LaunchedEffect(player) {
        while (true) {
            now = player.positionMs
            delay(500)
        }
    }
    return now to (player.current?.durationMs ?: 0)
}

@Composable
fun AlbumArt(albumId: String, size: Dp, radius: Dp = 8.dp) {
    Art(
        ArtworkStore.item(albumId),
        artColor(albumId),
        Modifier.size(size).clip(RoundedCornerShape(radius)),
        PlaceholderKind.Album,
    )
}

@Composable
fun CollectionArt(id: String, size: Dp, radius: Dp = 8.dp) {
    Art(
        ArtworkStore.collection(id),
        artColor(id),
        Modifier.size(size).clip(RoundedCornerShape(radius)),
    )
}

/** The song's title, always two lines tall so the controls below hold still between songs. */
@Composable
private fun Title(title: String) {
    Text(
        title,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 30.sp,
        minLines = 2,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** An accent line, one line tall even when empty, that closes the player and opens [route]. */
@Composable
private fun GoTo(text: String, size: TextUnit, weight: FontWeight, route: Route) {
    val nav = LocalNav.current
    Text(
        text,
        Modifier.fillMaxWidth().clickable(role = Role.Button) {
            nav.back()
            nav.open(route)
        },
        color = Palette.accent,
        fontSize = size,
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The player follows a downward drag and closes, as Back does, once dragged past [CLOSE_DRAG] or
 * flung down; otherwise it springs back.
 */
@Composable
private fun ClosesOnDragDown(close: () -> Unit, content: @Composable () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val (distance, speed) = with(LocalDensity.current) { CLOSE_DRAG.toPx() to CLOSE_FLING.toPx() }
    Box(
        Modifier.offset { IntOffset(0, drag.roundToInt()) }
            .draggable(
                rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) },
                Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (drag > distance || velocity > speed) close()
                    else animate(drag, 0f) { value, _ -> drag = value }
                },
            ),
    ) {
        content()
    }
}

private val CLOSE_DRAG = 120.dp
private val CLOSE_FLING = 1000.dp

/**
 * The album art over its file details: a tap or an upward swipe turns it to the details, a tap or a
 * downward swipe turns it back. With no [audio] it stays the art and its handle is invisible, still
 * taking its space.
 */
@Composable
private fun FlipArt(albumId: String, audio: AudioDetails?) {
    var flipped by remember(audio == null) { mutableStateOf(false) }
    // The system's animation scale applies, so a phone set to remove animations turns it at once.
    val turn by animateFloatAsState(if (flipped) 180f else 0f, tween(FLIP_MS))
    val shape = RoundedCornerShape(16.dp)
    val turnable =
        if (audio == null) Modifier
        else
            Modifier.semantics {
                    contentDescription =
                        if (flipped) "File details. Swipe down or tap for album art"
                        else "Album art. Swipe up or tap for file details"
                }
                .clickable { flipped = !flipped }
                .pointerInput(flipped) { swipe(up = !flipped) { flipped = !flipped } }
    Column(
        Modifier.padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    rotationY = turn
                    cameraDistance = 12 * density
                }
                .then(turnable),
        ) {
            if (turn <= 90f || audio == null)
                Art(
                    ArtworkStore.item(albumId),
                    artColor(albumId),
                    Modifier.fillMaxSize().clip(shape),
                    PlaceholderKind.Album,
                )
            else
                AudioPanel(
                    audio,
                    Modifier.fillMaxSize()
                        .graphicsLayer { rotationY = 180f }
                        .clip(shape)
                        .background(Palette.raised),
                )
        }
        Box(
            Modifier.padding(top = 8.dp)
                .size(36.dp, 4.dp)
                .clip(CircleShape)
                .background(if (audio == null) Color.Transparent else Palette.outline),
        )
    }
}

private const val FLIP_MS = 400

/**
 * Calls [turn] and claims the gesture once a press travels past the touch slop upward, if [up], or
 * else downward. A press going the other way is left to the player's drag to close.
 */
private suspend fun PointerInputScope.swipe(up: Boolean, turn: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var dy = 0f
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed || change.isConsumed) break
        dy += change.positionChange().y
        if (abs(dy) > viewConfiguration.touchSlop) {
            if (dy < 0 == up) {
                change.consume()
                turn()
            }
            break
        }
    }
}

/**
 * The file details in the art's place, "Source" then "Delivered". shortcut: rows do not scroll, so
 * a narrow phone at a large font scale clips the last; scroll them if that is seen.
 */
@Composable
private fun AudioPanel(audio: AudioDetails, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Format.audioDetails(audio).forEach { (group, rows) ->
            Column {
                Text(group, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                rows.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(row.label, Modifier.weight(1f), color = Palette.dim, fontSize = 14.sp)
                        if (row.lossless) Tag("Lossless")
                        Text(
                            row.value,
                            color =
                                when (row.success) {
                                    null -> Palette.text
                                    true -> Palette.ok
                                    false -> Palette.accent
                                },
                            fontSize = 14.sp,
                            fontWeight =
                                if (row.success == null) FontWeight.Medium else FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(player: MusicPlayer, now: Long, length: Long) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: if (length > 0) (now.toFloat() / length).coerceIn(0f, 1f) else 0f
    Column {
        Slider(
            fraction,
            { scrub = it },
            onValueChangeFinished = {
                scrub?.let { player.seek((it * length).toLong()) }
                scrub = null
            },
            colors =
                SliderDefaults.colors(
                    thumbColor = Palette.accent,
                    activeTrackColor = Palette.accent,
                    inactiveTrackColor = Palette.tag,
                ),
        )
        val shown = scrub?.let { (it * length).toLong() } ?: now
        Row {
            Text(Format.length(shown), fontSize = 12.sp, color = Palette.secondary)
            Spacer(Modifier.weight(1f))
            Text("-" + Format.length(length - shown), fontSize = 12.sp, color = Palette.secondary)
        }
    }
}

@Composable
private fun Transport(player: MusicPlayer, big: Dp) {
    val q = player.queue
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Toggle(R.drawable.ic_shuffle, "Shuffle", q.shuffled) { player.setShuffle(!q.shuffled) }
        IconAction(
            R.drawable.ic_previous,
            "Previous",
            { player.scope.launch { player.previous() } },
        )
        IconButton(
            { player.scope.launch { player.toggle() } },
            Modifier.size(big),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Palette.accent,
                    contentColor = Palette.onAccent,
                ),
        ) {
            Icon(
                painterResource(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                if (player.playing) "Pause" else "Play",
                Modifier.size(big / 2),
            )
        }
        IconAction(R.drawable.ic_next, "Next", { player.scope.launch { player.next() } })
        Toggle(
            if (q.repeatMode == RepeatMode.One) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
            "Repeat: ${q.repeatMode.name.lowercase()}",
            q.repeatMode != RepeatMode.Off,
        ) {
            player.setRepeat(RepeatMode.entries[(q.repeatMode.ordinal + 1) % 3])
        }
    }
}

@Composable
private fun Toggle(icon: Int, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick,
        colors =
            IconButtonDefaults.iconButtonColors(
                contentColor = if (on) Palette.accent else Palette.text,
            ),
    ) {
        Icon(painterResource(icon), label)
    }
}

/**
 * Up next: tap plays an entry, the handle drags it to a new place, a sideways swipe removes it.
 * shortcut: rows are a fixed height so a drag moves one place per row's height.
 */
@Composable
private fun QueueList(player: MusicPlayer, modifier: Modifier = Modifier) {
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val swipePx = with(LocalDensity.current) { 120.dp.toPx() }
    var dragged by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    val upNext = player.queue.upNext
    if (upNext.isEmpty()) return Text("Nothing up next.", modifier, color = Palette.dim)
    LazyColumn(modifier) {
        itemsIndexed(upNext, key = { _, e -> e.id }) { index, entry ->
            var swipe by remember { mutableFloatStateOf(0f) }
            QueueRow(
                entry,
                Modifier.graphicsLayer {
                        translationY = if (entry.id == dragged) offset else 0f
                        translationX = swipe
                    }
                    .pointerInput(entry.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (kotlin.math.abs(swipe) > swipePx) player.remove(entry.id)
                                swipe = 0f
                            },
                            onDragCancel = { swipe = 0f },
                        ) { _, delta ->
                            swipe += delta
                        }
                    }
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction("Remove from Up next") {
                                    player.remove(entry.id)
                                    true
                                },
                            )
                    }
                    .clickable { player.scope.launch { player.jump(entry.id) } },
            ) {
                Icon(
                    painterResource(R.drawable.ic_drag),
                    "Reorder",
                    Modifier.size(40.dp).padding(8.dp).pointerInput(entry.id) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragged = entry.id
                                offset = 0f
                            },
                            onDragEnd = { dragged = -1 },
                            onDragCancel = { dragged = -1 },
                        ) { _, delta ->
                            offset += delta
                            val at = player.queue.upNext.indexOfFirst { it.id == entry.id }
                            val to = at + (offset / rowPx).roundToInt().coerceIn(-1, 1)
                            if (to != at && to in player.queue.upNext.indices) {
                                player.move(at, to)
                                offset -= (to - at) * rowPx
                            }
                        }
                    },
                    tint = Palette.secondary,
                )
            }
            if (index == upNext.lastIndex) Spacer(Modifier.height(16.dp))
        }
    }
}

private val ROW = 56.dp

@Composable
private fun QueueRow(
    entry: AudioQueue.Entry,
    modifier: Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AlbumArt(entry.song.albumId, 40.dp, 4.dp)
        Column(Modifier.weight(1f)) {
            Text(
                entry.song.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(entry.song.artistName, Format.length(entry.song.durationMs))
                    .filter { it.isNotEmpty() }
                    .joinToString(" · "),
                fontSize = 12.sp,
                color = Palette.dim,
                maxLines = 1,
            )
        }
        trailing()
    }
}

@Composable
private fun UpNextHeader(player: MusicPlayer, big: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Up next",
                style =
                    if (big) MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp)
                    else MaterialTheme.typography.titleMedium,
            )
            if (big && player.queue.source.isNotEmpty())
                Text("Playing from ${player.queue.source}", color = Palette.secondary)
        }
        if (player.queue.upNext.isNotEmpty())
            TextButton({ player.clearUpNext() }) { Text("Clear", color = Palette.accent) }
    }
}

/** The phone's floating card above the bottom bar, as MusicAndroid draws it. */
@Composable
fun MiniPlayer(player: MusicPlayer) {
    player.station?.let {
        return StationMini(player, it)
    }
    val song = player.current ?: return
    val nav = LocalNav.current
    val (now, length) = rememberClock(player)
    Box(
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.raised)
            .clickable { nav.open(Route.NowPlaying()) },
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(song.albumId, 48.dp, 6.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    song.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(song.artistName, fontSize = 12.sp, color = Palette.secondary, maxLines = 1)
            }
            PlayPause(player)
            IconAction(R.drawable.ic_next, "Next", { player.scope.launch { player.next() } })
            Stop(player)
        }
        if (length > 0)
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth((now.toFloat() / length).coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(Palette.accent),
            )
    }
}

@Composable
private fun PlayPause(player: MusicPlayer) {
    IconAction(
        if (player.playing) R.drawable.ic_pause else R.drawable.ic_play,
        if (player.playing) "Pause" else "Play",
        { player.scope.launch { player.toggle() } },
    )
}

/** The tablet's bar under the content, as MovieSyncAndroidTablet draws it. */
@Composable
fun NowPlayingBar(player: MusicPlayer) {
    player.station?.let {
        return Box(
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
            ),
        ) {
            StationMini(player, it)
        }
    }
    val song = player.current ?: return
    val nav = LocalNav.current
    val (now, length) = rememberClock(player)
    Column(
        Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
        ),
    ) {
        HorizontalDivider(color = Palette.divider)
        Row(
            Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.width(260.dp).clickable { nav.open(Route.NowPlaying()) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlbumArt(song.albumId, 48.dp, 4.dp)
                Column {
                    Text(
                        song.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${song.artistName} · ${song.albumTitle}",
                        fontSize = 12.sp,
                        color = Palette.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconAction(
                R.drawable.ic_previous,
                "Previous",
                { player.scope.launch { player.previous() } },
            )
            PlayPause(player)
            IconAction(R.drawable.ic_next, "Next", { player.scope.launch { player.next() } })
            Stop(player)
            Text(Format.length(now), fontSize = 12.sp, color = Palette.secondary)
            Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.tag))
                if (length > 0)
                    Box(
                        Modifier.fillMaxWidth((now.toFloat() / length).coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Palette.accent),
                    )
            }
            Text(Format.length(length), fontSize = 12.sp, color = Palette.secondary)
            IconAction(R.drawable.ic_queue, "Up next", { nav.open(Route.NowPlaying(queue = true)) })
        }
    }
}

/** The expanded Music tab's right-hand pane: now playing and Up next, as MusicAndroidTablet. */
@Composable
fun NowPlayingPane(player: MusicPlayer, modifier: Modifier) {
    val song = player.current ?: return
    val (now, length) = rememberClock(player)
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(Palette.raised).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AlbumArt(song.albumId, 120.dp, 12.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (player.queue.source.isNotEmpty())
                    Text(
                        "Playing from ${player.queue.source}",
                        fontSize = 12.sp,
                        color = Palette.secondary,
                    )
                Text(
                    song.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(song.artistName, color = Palette.secondary)
            }
        }
        Progress(player, now, length)
        Transport(player, 64.dp)
        Pill("Stop", { player.scope.launch { player.stop() } }, icon = R.drawable.ic_stop)
        UpNextHeader(player, big = false)
        QueueList(player, Modifier.weight(1f))
    }
}

/** Now playing over everything, opened from the mini player; Up next is a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlaying(route: Route.NowPlaying) {
    val nav = LocalNav.current
    val showing = nav.music?.showing == true
    // The queue can end or be cleared by a profile switch while this is open.
    LaunchedEffect(showing) { if (!showing) nav.back() }
    val player = nav.music ?: return
    player.station?.let {
        return StationNowPlaying(player, it)
    }
    val song = player.current ?: return
    val (now, length) = rememberClock(player)
    var queue by remember(route) { mutableStateOf(route.queue) }
    ClosesOnDragDown({ nav.back() }) {
        Column(
            Modifier.fillMaxSize()
                .background(Palette.ground)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(R.drawable.ic_chevron_down, "Close", { nav.back() })
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (player.queue.source.isNotEmpty()) {
                        Text("Playing from", fontSize = 12.sp, color = Palette.secondary)
                        Text(
                            player.queue.source,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                MoreMenu(PlaylistKind.music, song.id, name = song.title)
            }
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxHeight().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlipArt(song.albumId, player.stream?.audio)
                Column {
                    Title(song.title)
                    GoTo(song.artistName, 18.sp, FontWeight.SemiBold, Route.Artist(song.artistId))
                    GoTo(song.albumTitle, 16.sp, FontWeight.Medium, Route.Album(song.albumId))
                }
                Box(Modifier.padding(top = 8.dp)) { Progress(player, now, length) }
                Transport(player, 72.dp)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Pill("Up next", { queue = true }, icon = R.drawable.ic_queue)
                    Pill(
                        "Stop",
                        { player.scope.launch { player.stop() } },
                        icon = R.drawable.ic_stop,
                    )
                    player.queue.upNext.firstOrNull()?.let {
                        Text(
                            "${it.song.title} · ${Format.length(it.song.durationMs)}",
                            color = Palette.dim,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    if (queue)
        ModalBottomSheet({ queue = false }, containerColor = Palette.raised) {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxHeight(0.7f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UpNextHeader(player, big = true)
                QueueList(player, Modifier.weight(1f))
            }
        }
}

@Composable
private fun Stop(player: MusicPlayer, size: Dp? = null) {
    val stop = {
        player.scope.launch { player.stop() }
        Unit
    }
    if (size == null) return IconAction(R.drawable.ic_stop, "Stop", stop)
    IconButton(
        stop,
        Modifier.size(size),
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = Palette.accent,
                contentColor = Palette.onAccent,
            ),
    ) {
        Icon(painterResource(R.drawable.ic_stop), "Stop", Modifier.size(size / 2))
    }
}

/** The mini player for a music station: the song, or the station off the air, and Stop. */
@Composable
private fun StationMini(player: MusicPlayer, tuner: StationTuner) {
    val nav = LocalNav.current
    val song = player.current
    val (now, length) = rememberClock(player)
    Box(
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.raised)
            .clickable { nav.open(Route.NowPlaying()) },
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) AlbumArt(song.albumId, 48.dp, 6.dp)
            else
                StationIdentity(
                    "",
                    Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                    imageUrl = tuner.station.imageUrl,
                )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    song?.title ?: tuner.station.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song?.let { "${it.artistName} · ${tuner.station.name}" }
                        ?: Stations.caption(tuner.onAir, PlaylistKind.music).first(),
                    fontSize = 12.sp,
                    color = Palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Stop(player)
        }
        if (length > 0 && player.playing)
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth((now.toFloat() / length).coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(Palette.accent),
            )
    }
}

/**
 * Now playing on a music station: the song, a progress bar with no knob, one Stop button, and a
 * read-only Up next of what the station plays next. Off the air, the station and when it is back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationNowPlaying(player: MusicPlayer, tuner: StationTuner) {
    val nav = LocalNav.current
    val song = player.current
    val (now, length) = rememberClock(player)
    var showUpNext by remember { mutableStateOf(false) }
    val name = tuner.station.name
    val upNext = player.stationUpNext
    ClosesOnDragDown({ nav.back() }) {
        Column(
            Modifier.fillMaxSize()
                .background(Palette.ground)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(R.drawable.ic_chevron_down, "Close", { nav.back() })
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Station", fontSize = 12.sp, color = Palette.secondary)
                    Text(
                        name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.size(48.dp))
            }
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxHeight().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val art =
                    Modifier.padding(vertical = 24.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                if (song == null) {
                    StationIdentity(name, art, big = true, imageUrl = tuner.station.imageUrl)
                    Text(
                        Stations.caption(tuner.onAir, PlaylistKind.music).first(),
                        Modifier.fillMaxWidth(),
                        color = Palette.secondary,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Art(
                        ArtworkStore.item(song.albumId),
                        artColor(song.albumId),
                        art,
                        PlaceholderKind.Album,
                    )
                    Title(song.title)
                    Text(song.artistName, fontSize = 18.sp, color = Palette.secondary)
                    val fraction = if (length > 0) (now.toFloat() / length).coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier.padding(top = 16.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Palette.tag),
                    ) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Palette.accent))
                    }
                    Row {
                        Text(Format.length(now), fontSize = 12.sp, color = Palette.secondary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "-" + Format.length(length - now),
                            fontSize = 12.sp,
                            color = Palette.secondary,
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Stop(player, 72.dp)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Pill("Up next", { showUpNext = true }, icon = R.drawable.ic_queue)
                }
            }
        }
    }
    if (showUpNext)
        ModalBottomSheet({ showUpNext = false }, containerColor = Palette.raised) {
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Up next", style = MaterialTheme.typography.titleMedium)
                Text("$name chooses what plays next.", color = Palette.secondary)
                if (upNext.isEmpty()) Text("Nothing up next.", color = Palette.dim)
                upNext.forEach { next ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AlbumArt(next.albumId, 40.dp, 4.dp)
                        Column {
                            Text(next.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                listOf(next.artistName, Format.length(next.durationMs))
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" · "),
                                fontSize = 12.sp,
                                color = Palette.dim,
                            )
                        }
                    }
                }
            }
        }
}
