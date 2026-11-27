package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.Resumable
import enterprises.legitimate.rabbitears.kit.placeholderKind
import enterprises.legitimate.rabbitears.kit.resumeProgress
import enterprises.legitimate.rabbitears.ui.theme.Palette
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home: the title in progress, else nothing, with Also in progress, then Next up, On now, a shelf
 * for each occasion in season, Recently added and New from your channels. Several in progress put
 * the latest beside the others, one fills the width, none leaves the row off.
 */
@Composable
fun Home() {
    val nav = LocalNav.current
    val model = nav.catalog
    val c = model.catalog
    val wide = LocalWidth.current != WidthClass.Compact
    val tileWidth = if (wide) 264.dp else 168.dp
    // Videos are not in the catalog, so a watch change elsewhere reads them again.
    val watched = nav.app.live.watched
    // A failed read is null, so the strip keeps what it showed.
    val inProgress =
        rememberLoaded(c, watched) { nav.youtube.inProgress().takeIf { nav.youtube.error == null } }
    val fresh =
        rememberLoaded(c, watched) {
            nav.youtube.newFromChannels().takeIf { nav.youtube.error == null }
        }
    val videos = inProgress.value.orEmpty()
    val newVideos = fresh.value.orEmpty()
    val lead = c.homeLead(videos)
    val nextUp = c.nextUp
    val open = { item: Item -> nav.open(Route.Details(item.id)) }
    LaunchedEffect(nav.stations) { nav.stations.load() }
    // A library change reloads the catalog, which reads the occasions again with it.
    OccasionsLoad(nav, c)
    val reload: suspend () -> Unit = {
        coroutineScope {
            launch { model.reload() }
            launch { inProgress.reload() }
            launch { fresh.reload() }
            launch { nav.stations.load() }
            launch { nav.occasions.load() }
        }
    }

    Refreshable(reload) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Header("Home") }
            model.error?.let { item { Text(it, color = Palette.accent) } }
            if (model.loaded && model.error == null && c.recentlyAdded.isEmpty())
                item {
                    Text(
                        if (nav.app.me?.account?.isAdmin == true)
                            "Nothing here yet. Add a library in the admin console."
                        else "Nothing here yet.",
                        color = Palette.dim,
                    )
                }
            lead.hero?.let { hero ->
                item {
                    if (lead.also.isNotEmpty() && wide)
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Box(Modifier.weight(2f)) { ResumableHero(c, hero, wide) }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SectionTitle("Also in progress")
                                lead.also.forEach {
                                    val pick = it.pick(c, nav)
                                    MediaRow(
                                        pick.title,
                                        pick.caption,
                                        pick.open,
                                        {
                                            Art(
                                                pick.art,
                                                artColor(pick.held.id),
                                                Modifier.size(96.dp, 54.dp)
                                                    .clip(MaterialTheme.shapes.small),
                                            )
                                        },
                                        downloaded = isDownloaded(pick.held.id),
                                        held = pick.held,
                                    )
                                }
                            }
                        }
                    else ResumableHero(c, hero, wide)
                }
            }
            if (!wide)
                item {
                    Strip("Also in progress", lead.also) {
                        val pick = it.pick(c, nav)
                        WideTile(
                            pick.held,
                            pick.art,
                            pick.title,
                            pick.caption,
                            pick.open,
                            Modifier.width(tileWidth),
                            kind = pick.kind,
                        )
                    }
                }
            item {
                Strip("Next up", nextUp) {
                    WideTile(
                        Held.Title(it),
                        art(it),
                        c.heading(it),
                        c.episodeCaption(it),
                        {
                            // Plays as the title's menu does: resuming a position kept.
                            val start = c.start(it)
                            if (start == null) open(it)
                            else
                                nav.play(
                                    start.itemId,
                                    start.positionMs,
                                    editionId = start.editionId,
                                )
                        },
                        Modifier.width(tileWidth),
                        kind = placeholderKind(it.kind.value),
                        plays = c.playLabel(it),
                        details = { open(it) },
                    )
                }
            }
            item {
                Strip("On now", nav.stations.onNow) {
                    StationTile(it, Modifier.width(tileWidth), onNow = true)
                }
            }
            val poster: @Composable (Item) -> Unit = {
                val caption =
                    if (it.kind == ItemKind.show) Format.plural(c.seasons(it.id).size, "season")
                    else it.metadata?.runtimeMinutes?.let(Format::runtime)
                PosterTile(
                    it,
                    caption,
                    { open(it) },
                    Modifier.width(if (wide) 160.dp else 112.dp),
                    kind = placeholderKind(it.kind.value),
                )
            }
            nav.occasions.shelves.forEach { shelf ->
                item(key = "occasion-${shelf.occasion.id}") {
                    Strip(
                        shelf.title,
                        shelf.items,
                        SeeAll(shelf.occasion.name) { nav.open(Route.Occasion(shelf.occasion)) },
                        poster,
                    )
                }
            }
            item { Strip("Recently added", c.recentlyAdded, tile = poster) }
            item {
                Strip("New from your channels", newVideos) {
                    WideTile(
                        Held.Video(it),
                        ArtworkStore.item(it.id),
                        it.title,
                        listOfNotNull(it.channelTitle, it.durationMs?.let(Format::minutes))
                            .joinToString(" · "),
                        { nav.open(Route.Video(it.id)) },
                        Modifier.width(tileWidth),
                        kind = PlaceholderKind.Video,
                    )
                }
            }
            item { Box(Modifier.height(8.dp)) }
        }
    }
}

/**
 * Reads the occasions in season when Home appears, [catalog] is read again, the app returns to the
 * foreground and the device's calendar date turns.
 */
@Composable
private fun OccasionsLoad(nav: Navigator, catalog: Catalog) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    var starts by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        starts++
        today = LocalDate.now()
    }
    LaunchedEffect(nav, catalog, starts, today) { nav.occasions.load() }
    LaunchedEffect(today) {
        val midnight = today.plusDays(1).atStartOfDay(ZonedDateTime.now().zone)
        delay(maxOf(1_000L, Duration.between(ZonedDateTime.now(), midnight).toMillis()))
        today = LocalDate.now()
    }
}

/** What a tile or row of Also in progress shows. */
private class Pick(
    val held: Held,
    val art: String,
    val title: String,
    val caption: String,
    val open: () -> Unit,
    val kind: PlaceholderKind,
)

private fun Resumable.pick(c: Catalog, nav: Navigator): Pick =
    when (this) {
        is Resumable.Title ->
            Pick(
                Held.Title(item),
                art(item),
                c.heading(item),
                c.leftCaption(item),
                { nav.open(Route.Details(item.id)) },
                placeholderKind(item.kind.value),
            )
        is Resumable.Video ->
            Pick(
                Held.Video(video),
                ArtworkStore.item(video.id),
                video.title,
                caption(video),
                { nav.open(Route.Video(video.id)) },
                PlaceholderKind.Video,
            )
    }

@Composable
private fun ResumableHero(c: Catalog, hero: Resumable, wide: Boolean) =
    when (hero) {
        is Resumable.Title -> TitleHero(c, hero.item, wide)
        is Resumable.Video -> VideoHero(hero.video, wide)
    }

/** A wide tile's image: an episode's still, else the title's backdrop. */
private fun art(item: Item) =
    ArtworkStore.item(item.id, if (item.kind == ItemKind.episode) "poster" else "backdrop")

/** A video's caption: `Workbench Weekly · 18m left`. */
private fun caption(video: YouTubeVideo) =
    listOfNotNull(
            video.channelTitle,
            video.durationMs?.let { Format.remaining(maxOf(0, it - video.positionMs)) },
        )
        .joinToString(" · ")

@Composable
private fun TitleHero(c: Catalog, item: Item, wide: Boolean) {
    val nav = LocalNav.current
    Hero(
        Held.Title(item),
        art(item),
        c.heading(item),
        c.leftCaption(item, named = wide),
        c.progress(item.id),
        wide,
        details = { nav.open(Route.Details(item.id)) },
        play = {
            // The position belongs to one edition, which a resume plays.
            val watch = c.watchState(item.id)
            nav.play(item.id, watch?.positionMs ?: 0, editionId = watch?.editionId)
        },
    )
}

@Composable
private fun VideoHero(video: YouTubeVideo, wide: Boolean) {
    val nav = LocalNav.current
    Hero(
        Held.Video(video),
        ArtworkStore.item(video.id),
        video.title,
        caption(video),
        video.resumeProgress(),
        wide,
        details = { nav.open(Route.Video(video.id)) },
        play = { nav.play(video.id, video.positionMs, title = video.title) },
    )
}

@Composable
private fun Hero(
    held: Held,
    art: String,
    heading: String,
    caption: String,
    progress: Double?,
    wide: Boolean,
    details: () -> Unit,
    play: () -> Unit,
) =
    Hold(held, null, details) { gesture ->
        Art(
            art,
            Color(0xFF17304A),
            Modifier.fillMaxWidth()
                .height(if (wide) 240.dp else 200.dp)
                .clip(RoundedCornerShape(16.dp))
                .then(gesture),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.2f to Color(0x000A101A),
                            0.8f to Color(0xCC0A101A),
                        ),
                    )
                    .padding(if (wide) 32.dp else 20.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Continue watching",
                        color = Palette.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        heading,
                        fontSize = if (wide) 36.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(caption, color = Palette.secondary)
                    Row(
                        Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Pill(
                            "Resume",
                            play,
                            primary = true,
                            icon = R.drawable.ic_play,
                        )
                        if (wide) Pill("Details", details)
                        progress?.let {
                            Box(
                                Modifier.padding(start = 4.dp)
                                    .then(if (wide) Modifier.width(280.dp) else Modifier.weight(1f))
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.3f)),
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(it.toFloat())
                                        .height(4.dp)
                                        .background(Palette.accent),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
