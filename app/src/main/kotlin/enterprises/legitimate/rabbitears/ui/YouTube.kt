package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.MediaSummary
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.SelectKind
import enterprises.legitimate.rabbitears.kit.Selection
import enterprises.legitimate.rabbitears.kit.VideoSort
import enterprises.legitimate.rabbitears.kit.channelParts
import enterprises.legitimate.rabbitears.kit.published
import enterprises.legitimate.rabbitears.ui.theme.Palette
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private enum class YouTubeView(val label: String) {
    Videos("Videos"),
    Channels("Channels"),
    Unwatched("Unwatched"),
}

private val Wide: Boolean
    @Composable get() = LocalWidth.current != WidthClass.Compact

private fun LazyGridScope.row(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
fun YouTubeTab() {
    val nav = LocalNav.current
    val model = nav.youtube
    var view by rememberSaveable { mutableStateOf(YouTubeView.Videos) }
    var sort by rememberStored("youtube.videoSort", VideoSort.Newest)
    var revision by remember { mutableIntStateOf(0) }
    val selection = remember { Selection() }
    val selectKind = if (view == YouTubeView.Channels) SelectKind.Channel else SelectKind.Video
    LaunchedEffect(view) { selection.end() }
    val watched = nav.app.live.watched
    LaunchedEffect(model, revision, watched) { model.loadChannels() }
    // A failed read is null, so the list keeps what it showed.
    val loaded =
        rememberLoaded(view, sort, revision, watched) {
            when (view) {
                YouTubeView.Videos -> model.videos(sort)
                YouTubeView.Unwatched -> model.videos(VideoSort.Newest, unwatched = true)
                else -> null
            }?.takeIf { model.error == null }
        }
    val videos = loaded.value
    val reload: suspend () -> Unit = {
        coroutineScope {
            launch { model.loadChannels() }
            launch { loaded.reload() }
        }
    }
    val count = model.channels?.let { Format.plural(it.size, "channel") }
    val shown =
        (if (view == YouTubeView.Channels) model.channels?.map { it.id } else videos?.map { it.id })
            .orEmpty()
    val sortMenu = @Composable { if (view == YouTubeView.Videos) VideoSortMenu(sort) { sort = it } }
    Refreshable(reload) {
        LazyVerticalGrid(
            GridCells.Adaptive(if (Wide) 240.dp else 168.dp),
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (Wide) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            row {
                Column {
                    Header("YouTube", count, trailing = { SelectToggle(selection, selectKind) }) {
                        sortMenu()
                    }
                    // Unwatched is the Videos tab filtered, so it is a chip there.
                    val tab = if (view == YouTubeView.Channels) view else YouTubeView.Videos
                    ViewTabs(listOf(YouTubeView.Videos, YouTubeView.Channels), tab, { it.label }) {
                        if (it != tab) view = it
                    }
                }
            }
            if (view != YouTubeView.Channels)
                row {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f)) {
                            Chip("Unwatched", view == YouTubeView.Unwatched) {
                                view =
                                    if (view == YouTubeView.Unwatched) YouTubeView.Videos
                                    else YouTubeView.Unwatched
                            }
                        }
                        if (Wide) sortMenu()
                    }
                }
            model.error?.let { row { Text(it, color = Palette.accent) } }
            when (view) {
                YouTubeView.Videos,
                YouTubeView.Unwatched -> {
                    if (videos?.isEmpty() == true)
                        row {
                            Text(
                                if (view == YouTubeView.Unwatched) "You're all caught up."
                                else "No videos yet.",
                                color = Palette.dim,
                            )
                        }
                    items(videos.orEmpty(), key = { it.id }) {
                        VideoTile(it, channel = true, selection = selection) { revision++ }
                    }
                }
                YouTubeView.Channels ->
                    items(model.channels.orEmpty(), key = { it.id }) { channel ->
                        Column(
                            Modifier.pick(selection, channel.id) {
                                nav.open(Route.Channel(channel.id))
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // The badge sits outside the circle's clip, on its square's corner.
                            Box(Modifier.size(if (Wide) 120.dp else 96.dp)) {
                                Art(
                                    ArtworkStore.item(channel.id),
                                    artColor(channel.id),
                                    Modifier.matchParentSize().clip(CircleShape),
                                    PlaceholderKind.Channel,
                                ) {
                                    if (channel.unwatchedCount > 0)
                                        Badge("${channel.unwatchedCount}")
                                }
                                StatusBadges(isDownloaded(channel.id))
                            }
                            Text(
                                channel.title,
                                Modifier.padding(top = 6.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                Format.plural(channel.videoCount, "video"),
                                fontSize = 12.sp,
                                color = Palette.dim,
                            )
                        }
                    }
            }
            row { Box(Modifier.height(80.dp)) }
        }
        SelectionBar(selection, selectKind, shown, Modifier.align(Alignment.BottomCenter)) {
            revision++
        }
    }
}

@Composable
private fun VideoSortMenu(sort: VideoSort, onPick: (VideoSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_filter, "Sort: ${sort.label}", { open = true })
        DropdownMenu(open, { open = false }) {
            VideoSort.entries.forEach {
                DropdownMenuItem(
                    { Text(it.label) },
                    {
                        onPick(it)
                        open = false
                    },
                    trailingIcon =
                        if (it == sort)
                            ({
                                Icon(
                                    painterResource(R.drawable.ic_check),
                                    null,
                                    tint = Palette.accent,
                                )
                            })
                        else null,
                )
            }
        }
    }
}

/** The caption names the channel on the YouTube tab and the date on a channel. */
@Composable
private fun VideoTile(
    video: YouTubeVideo,
    channel: Boolean,
    selection: Selection? = null,
    changed: () -> Unit,
) {
    val nav = LocalNav.current
    val caption =
        if (channel) listOfNotNull(video.channelTitle, video.durationMs?.let(Format::minutes))
        else
            listOfNotNull(
                video.durationMs?.let(Format::minutes),
                video.publishedOn?.let { Format.published(it) },
            )
    val progress =
        video.durationMs
            ?.takeIf { it > 0 && video.positionMs > 0 }
            ?.let { video.positionMs.toDouble() / it }
    Hold(Held.Video(video, changed), selection, { nav.open(Route.Video(video.id)) }) { gesture ->
        Column(gesture) {
            Art(
                ArtworkStore.item(video.id),
                artColor(video.id),
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small),
                PlaceholderKind.Video,
            ) {
                if (!video.watched && progress == null) UnwatchedDot()
                progress?.let { TileBar(it) }
                StatusBadges(isDownloaded(video.id), overBar = progress != null)
            }
            TileText(video.title, caption.joinToString(" · "))
        }
    }
}

@Composable
fun ChannelScreen(id: Long) {
    val nav = LocalNav.current
    val model = nav.youtube
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val loaded = rememberLoaded(id, version, nav.app.live.watched) { model.channel(id) }
    val detail = loaded.value
    Refreshable(loaded::reload) {
        LazyVerticalGrid(
            GridCells.Adaptive(if (Wide) 240.dp else 168.dp),
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (Wide) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            row {
                BackBar("Back") {
                    detail?.let {
                        DownloadMore(
                            DownloadAsk(DownloadTarget(itemId = id), it.channel.title, false),
                        )
                    }
                }
            }
            val d = detail ?: return@LazyVerticalGrid row { model.error?.let { Text(it) } }
            val channel = d.channel
            row {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Art(
                        ArtworkStore.item(channel.id),
                        artColor(channel.id),
                        Modifier.size(if (Wide) 112.dp else 80.dp).clip(CircleShape),
                        PlaceholderKind.Channel,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(channel.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Format.channelParts(channel).forEach { Text(it, color = Palette.dim) }
                        }
                    }
                }
            }
            row {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.videos.firstOrNull()?.let { newest ->
                        Pill(
                            "Play newest",
                            { nav.play(newest.id, newest.positionMs, title = newest.title) },
                            primary = true,
                            icon = R.drawable.ic_play,
                        )
                    }
                    val all = channel.unwatchedCount > 0
                    Pill(
                        if (all) "Mark all watched" else "Mark all unwatched",
                        { scope.launch { if (model.setWatched(channel.id, all)) version++ } },
                        icon = R.drawable.ic_check,
                    )
                }
            }
            if (!nav.app.isKid)
                row {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("For kids", Modifier.weight(1f))
                            Switch(
                                channel.forKids == true,
                                {
                                    scope.launch { if (model.setForKids(channel.id, it)) version++ }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent),
                            )
                        }
                        AllowedForKids(channel.id, nav.app.listKids)
                    }
                }
            channel.overview?.let {
                row { Text(it, color = Palette.secondary, lineHeight = 21.sp) }
            }
            row { SectionTitle("Videos") }
            items(d.videos, key = { it.id }) { VideoTile(it, channel = false) { version++ } }
            row { Box(Modifier.height(8.dp)) }
        }
    }
}

@Composable
fun VideoScreen(id: Long) {
    val nav = LocalNav.current
    val model = nav.youtube
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val video by
        produceState<YouTubeVideo?>(null, id, version, nav.app.live.watched) {
            value = model.video(id)
        }
    val media by produceState<MediaSummary?>(null, id) { value = nav.catalog.media(id) }
    val together = rememberTogether(id, video?.title)
    val uri = LocalUriHandler.current
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BackBar("Back") { video?.let { VideoMore(it) { version++ } } } }
        val v = video ?: return@LazyColumn item { model.error?.let { Text(it) } }
        item {
            Art(
                ArtworkStore.item(v.id),
                artColor(v.id),
                Modifier.widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp)),
                PlaceholderKind.Video,
            ) {
                v.durationMs
                    ?.takeIf { it > 0 && v.positionMs > 0 }
                    ?.let { TileBar(v.positionMs.toDouble() / it) }
            }
        }
        item {
            Column(
                Modifier.widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.clickable { nav.open(Route.Channel(v.channelId)) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Art(
                        ArtworkStore.item(v.channelId),
                        artColor(v.channelId),
                        Modifier.size(20.dp).clip(CircleShape),
                        PlaceholderKind.Channel,
                    )
                    Text(v.channelTitle, color = Palette.accent, fontWeight = FontWeight.Medium)
                }
                Text(v.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    listOfNotNull(
                            v.durationMs?.let(Format::minutes),
                            v.publishedOn?.let(Format::date),
                        )
                        .forEach { Text(it, color = Palette.dim) }
                    v.height?.let { Tag(Format.resolution(it * 16 / 9, it)) }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    val resuming = v.positionMs > 0
                    if (together?.inSync == true)
                        Pill(
                            "Play together",
                            { scope.launch { together.play() } },
                            primary = true,
                            icon = R.drawable.ic_play,
                        )
                    else
                        Pill(
                            if (resuming) "Resume" else "Play",
                            { nav.play(v.id, if (resuming) v.positionMs else 0, title = v.title) },
                            primary = true,
                            icon = R.drawable.ic_play,
                        )
                    Pill(
                        if (v.watched) "Unwatched" else "Watched",
                        { scope.launch { if (model.setWatched(v, !v.watched)) version++ } },
                        icon = R.drawable.ic_check,
                    )
                    together?.let { TogetherAction(it) }
                }
                together?.let { TogetherNote(it, v.title) }
                v.overview
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Text(it, color = Palette.secondary, lineHeight = 22.sp) }
                Column {
                    SectionTitle("Details", Modifier.padding(bottom = 6.dp))
                    val key = if (Wide) 74.dp else 92.dp
                    media
                        ?.file
                        ?.chapters
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { KeyValue("Chapters", key) { Text("${it.size}") } }
                    media
                        ?.takeIf { it.subtitles.isNotEmpty() }
                        ?.let { KeyValue("Subtitles", key) { Text(it.subtitleLine) } }
                    media?.videoLine?.let { KeyValue("Video", key) { Text(it) } }
                    KeyValue("Added", key) { Text(added(v)) }
                    v.sourceUrl?.let { url ->
                        KeyValue("Source", key) {
                            Text(
                                "Open on YouTube",
                                Modifier.clickable { runCatching { uri.openUri(url) } },
                                color = Palette.accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `Nov 14, 2026, 6:12 AM` in this device's zone. */
private fun added(v: YouTubeVideo): String =
    v.addedAt
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US))
