package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.SystemCapabilities
import enterprises.legitimate.rabbitears.api.models.Edition
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemCredits
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.ItemRelated
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.Verdict
import enterprises.legitimate.rabbitears.kit.CapabilityGroup
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.Editions
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.MediaSummary
import enterprises.legitimate.rabbitears.kit.OccasionShelf
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.crewLines
import enterprises.legitimate.rabbitears.kit.placeholderKind
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** Movie, Show, Season or Episode details, by the item's kind. */
@Composable
fun Details(id: Long) {
    val nav = LocalNav.current
    // A search can find a title the catalog has not loaded yet; ask the server for it.
    val fetched by
        produceState<Item?>(null, id) {
            value = runCatching { nav.catalog.api.call { items.getItem(id) } }.getOrNull()
        }
    val item = nav.catalog.catalog.item(id) ?: fetched ?: return BackBar("Back")
    Refreshable(nav.catalog::reload) {
        when (item.kind) {
            ItemKind.movie -> Movie(item, fetched?.editions.orEmpty())
            ItemKind.show -> Show(item)
            ItemKind.season -> Season(item)
            ItemKind.episode -> Episode(item)
        }
    }
}

@Composable
private fun credits(id: Long): ItemCredits? {
    val nav = LocalNav.current
    return produceState<ItemCredits?>(null, id) { value = nav.catalog.credits(id) }.value
}

@Composable
private fun occasionRows(id: Long): List<OccasionShelf> {
    val nav = LocalNav.current
    return produceState(emptyList<OccasionShelf>(), id) { value = nav.occasions.containing(id) }
        .value
}

/** One row per occasion in season the title is in, each of its other titles. */
private fun LazyListScope.occasionStrips(rows: List<OccasionShelf>, width: @Composable () -> Dp) {
    rows.forEach { row ->
        item(key = "occasion-${row.occasion.id}") {
            val open = LocalNav.current
            Strip(row.occasion.name, row.items) {
                PosterTile(
                    it,
                    open.catalog.catalog.caption(it),
                    { open.open(Route.Details(it.id)) },
                    Modifier.width(width()),
                    kind = placeholderKind(it.kind.value),
                )
            }
        }
    }
}

@Composable
private fun related(id: Long): ItemRelated? {
    val nav = LocalNav.current
    return produceState<ItemRelated?>(null, id) { value = nav.catalog.related(id) }.value
}

@Composable
private fun media(id: Long, fileId: Long? = null): MediaSummary? {
    val nav = LocalNav.current
    return produceState<MediaSummary?>(null, id, fileId) { value = nav.catalog.media(id, fileId) }
        .value
}

/** How play would go on this device, asked again when the network changes. */
@Composable
private fun verdict(id: Long): Verdict? {
    val nav = LocalNav.current
    val context = LocalContext.current
    val capabilities = remember { SystemCapabilities(context) }
    var path by remember { mutableIntStateOf(0) }
    DisposableEffect(capabilities) {
        capabilities.onChange = { if (it == CapabilityGroup.Network) path++ }
        onDispose { capabilities.close() }
    }
    return produceState<Verdict?>(null, id, path) {
            value = nav.catalog.verdict(id, capabilities.facts())
        }
        .value
}

/** A details page: Back and the page's menu, then the sections, scrolling as one. */
@Composable
private fun Page(
    back: String,
    menu: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { BackBar(back, menu) }
        content()
        item { Box(Modifier.height(16.dp)) }
    }
}

private val Wide: Boolean
    @Composable get() = LocalWidth.current != WidthClass.Compact

@Composable
private fun Poster(item: Item, width: Dp, radius: Dp = 12.dp) {
    Art(
        ArtworkStore.item(item.id),
        artColor(item.id),
        Modifier.width(width).height(width * 1.5f).clip(RoundedCornerShape(radius)),
        placeholderKind(item.kind.value),
    )
}

/** Title block beside the poster: an optional goldenrod kicker, the title, the meta line. */
@Composable
private fun Titles(
    kicker: String?,
    onKicker: () -> Unit,
    title: String,
    meta: @Composable () -> Unit,
    big: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        kicker?.let {
            Text(
                it,
                Modifier.clickable(onClick = onKicker),
                color = Palette.accent,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            title,
            fontSize = if (big) 36.sp else 24.sp,
            lineHeight = if (big) 44.sp else 30.sp,
            fontWeight = FontWeight.Medium,
        )
        meta()
    }
}

/** `2h 46m 2024 R [4K] [HDR]` in dim text, with an optional goldenrod time left. */
@Composable
private fun Meta(parts: List<String>, tags: List<String> = emptyList(), left: String? = null) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        parts.filter { it.isNotEmpty() }.forEach { Text(it, color = Palette.dim) }
        tags.forEach { Tag(it) }
        left?.let { Text(it, color = Palette.accent, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun Overview(item: Item) {
    item.metadata
        ?.overview
        ?.takeIf { it.isNotBlank() }
        ?.let {
            Text(
                it,
                color = Palette.secondary,
                style =
                    MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
            )
        }
}

@Composable
private fun WatchedPill(item: Item) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val watched = nav.catalog.catalog.isWatched(item.id)
    Pill(
        if (watched) "Unwatched" else "Watched",
        { scope.launch { nav.catalog.setWatched(!watched, item) } },
        icon = R.drawable.ic_check,
    )
}

/** Previous and Next, each only when that neighbor exists, opening it in place of this page. */
@Composable
private fun Neighbors(related: ItemRelated?, episode: Boolean) {
    val nav = LocalNav.current
    listOfNotNull(related?.previous?.let { "Previous" to it }, related?.next?.let { "Next" to it })
        .forEach { (word, neighbor) ->
            val name = if (episode) Format.episodeCode(neighbor) else Catalog.title(neighbor)
            Pill(
                "$word: $name",
                { nav.replace(Route.Details(neighbor.id)) },
                Modifier.semantics {
                    contentDescription = "$word ${if (episode) "episode" else "film"}, $name"
                },
            )
        }
}

/** Play for both members of a Watch together; the answer opens each one's player. */
@Composable
private fun PlayTogether(onClick: () -> Unit) {
    Pill("Play together", onClick, primary = true, icon = R.drawable.ic_play)
}

@Composable
private fun Cast(credits: ItemCredits?) {
    val nav = LocalNav.current
    val cast = credits?.cast.orEmpty()
    Strip("Cast", cast) { credit ->
        Column(
            Modifier.width(if (Wide) 112.dp else 84.dp).clickable {
                nav.open(Route.Person(credit.personId))
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Art(
                ArtworkStore.person(credit.personId),
                avatarColor(credit.personId),
                Modifier.size(64.dp).clip(CircleShape),
            )
            Text(
                credit.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            credit.character?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Palette.dim,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** With more than one audio track the movie offers a pick, which the player starts with. */
@Composable
private fun DetailLines(
    lines: List<Pair<String, String>>,
    media: MediaSummary?,
    episode: Boolean,
    audio: Long?,
    onAudio: (Long) -> Unit,
    title: Boolean = true,
    verdict: Verdict? = null,
) {
    if (lines.isEmpty() && media == null) return
    val key = if (Wide) 74.dp else 92.dp
    Column {
        if (title) SectionTitle("Details", Modifier.padding(bottom = 6.dp))
        lines.forEach { (k, v) -> KeyValue(k, key) { Text(v) } }
        media ?: return
        val tracks = media.audio
        if (!episode && tracks.size > 1)
            KeyValue("Audio", key) {
                val current = tracks.firstOrNull { it.id == audio } ?: media.defaultAudio
                Choice(current?.let(media::label).orEmpty(), tracks, media::label) {
                    onAudio(it.id)
                }
            }
        else media.defaultAudio?.let { KeyValue("Audio", key) { Text(media.label(it)) } }
        if (media.subtitles.isNotEmpty()) KeyValue("Subtitles", key) { Text(media.subtitleLine) }
        media.videoLine?.let { video ->
            KeyValue("Video", key) {
                Text(
                    buildAnnotatedString {
                        append(video)
                        verdict ?: return@buildAnnotatedString
                        append(" · ")
                        val free = verdict == Verdict.direct_play
                        withStyle(SpanStyle(if (free) Palette.ok else Palette.accent)) {
                            append(Format.verdict(verdict))
                        }
                        append(" on this device")
                    },
                )
            }
        }
        if (!episode) KeyValue("File", key) { Text(media.fileLine, color = Palette.secondary) }
    }
}

/** An outlined button naming [current] that opens a menu of [options]. */
@Composable
private fun <T> Choice(
    current: String,
    options: List<T>,
    label: (T) -> String,
    description: String? = null,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            { open = true },
            Modifier.height(36.dp).semantics(mergeDescendants = description != null) {
                description?.let { contentDescription = it }
            },
            shape = MaterialTheme.shapes.extraSmall,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.text),
        ) {
            Text(current)
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                null,
                Modifier.padding(start = 6.dp).size(18.dp),
            )
        }
        DropdownMenu(open, { open = false }) {
            options.forEach {
                DropdownMenuItem(
                    { Text(label(it)) },
                    {
                        onPick(it)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * With more than one edition, the edition picked starts as the one the saved position belongs to,
 * and Resume is offered only for it.
 */
@Composable
private fun Movie(item: Item, editions: List<Edition>) {
    // Poster beside the text needs the room of an expanded window, as the tablet board draws it.
    val wide = LocalWidth.current == WidthClass.Expanded
    val nav = LocalNav.current
    val c = nav.catalog.catalog
    val credits = credits(item.id)
    val related = related(item.id)
    val occasions = occasionRows(item.id)
    val together = rememberTogether(item.id)
    val watch = c.watchState(item.id)
    var picked by remember(item.id) { mutableStateOf<Long?>(null) }
    // Watch together always plays the default edition, so neither member picks one.
    val choosing = editions.size > 1 && together?.current == null
    val edition =
        if (choosing)
            editions.firstOrNull { it.id == picked } ?: Editions.preselected(editions, watch)
        else Editions.default(editions)
    val editionId = Editions.requested(editions, edition).takeIf { choosing }
    val fileId = Editions.described(editions, edition)
    val media = media(item.id, fileId)
    val verdict = verdict(item.id)
    // An audio pick names a track of the described file, so it goes when the file does.
    var audio by remember(item.id, fileId) { mutableStateOf<Long?>(null) }
    val resuming = Editions.resumes(editions, edition, watch)
    val position = watch?.positionMs ?: 0
    val scope = rememberCoroutineScope()
    var collected by remember { mutableStateOf(0) }
    val inCollections by
        produceState(emptyList(), item.id, collected) { value = nav.collections.ofMovie(item.id) }
    val meta =
        @Composable {
            Meta(
                listOfNotNull(
                    item.metadata?.runtimeMinutes?.let(Format::runtime),
                    Catalog.year(item)?.toString(),
                    item.metadata?.contentRating,
                ),
                media?.tags.orEmpty(),
                c.remainingMs(item.id)?.let(Format::remaining),
            )
        }
    val actions =
        @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Actions {
                    if (together?.inSync == true) PlayTogether { scope.launch { together.play() } }
                    else {
                        Pill(
                            if (resuming) "Resume" else "Play",
                            {
                                nav.play(
                                    item.id,
                                    if (resuming) position else 0,
                                    audio,
                                    editionId = editionId,
                                )
                            },
                            primary = true,
                            icon = R.drawable.ic_play,
                        )
                        if (resuming)
                            Pill(
                                "Start over",
                                { nav.play(item.id, 0, audio, editionId = editionId) },
                            )
                        if (choosing && edition != null)
                            Choice(
                                edition.name,
                                editions,
                                Editions::label,
                                "Edition, ${edition.name}",
                            ) {
                                picked = it.id
                            }
                    }
                    // An adult marks watched in More; a kid has no More.
                    if (nav.app.isKid) WatchedPill(item)
                    together?.let { TogetherAction(it) }
                    Neighbors(related, episode = false)
                }
                together?.let { TogetherNote(it, Catalog.title(item)) }
            }
        }
    val lines =
        credits?.let { crewLines(it, episode = false) }.orEmpty() +
            listOfNotNull(
                item.genres
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { g -> "Genres" to g.joinToString(", ") { it.name } },
            )
    Page(
        "Back",
        {
            MoreMenu(
                PlaylistKind.movies,
                item.id,
                item,
                editionId = editionId,
                onCollected = { collected++ },
            )
        },
    ) {
        if (wide)
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Poster(item, 240.dp, 16.dp)
                    Column(
                        Modifier.widthIn(max = 680.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Titles(null, {}, Catalog.title(item), meta, big = true)
                        actions()
                        Overview(item)
                        DetailLines(
                            lines,
                            media,
                            false,
                            audio,
                            { audio = it },
                            title = false,
                            verdict = verdict,
                        )
                    }
                }
            }
        else {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Poster(item, 112.dp)
                    Titles(null, {}, Catalog.title(item), meta, big = false)
                }
            }
            item { actions() }
            item { Overview(item) }
        }
        Catalog.related(related).forEach { row ->
            item {
                Strip(row.collection.name, row.items) {
                    PosterTile(
                        it,
                        c.caption(it),
                        { nav.open(Route.Details(it.id)) },
                        Modifier.width(if (Wide) 112.dp else 84.dp),
                        kind = PlaceholderKind.Movie,
                    )
                }
            }
        }
        occasionStrips(occasions) { if (Wide) 112.dp else 84.dp }
        item { Cast(credits) }
        item { MovieCollections(inCollections, if (Wide) 112.dp else 84.dp) }
        if (!wide)
            item { DetailLines(lines, media, false, audio, { audio = it }, verdict = verdict) }
    }
}

@Composable
private fun Show(item: Item) {
    val wide = LocalWidth.current == WidthClass.Expanded
    val nav = LocalNav.current
    val c = nav.catalog.catalog
    val credits = credits(item.id)
    val occasions = occasionRows(item.id)
    val target = c.resumeTarget(item.id)
    val seasons = c.children(item.id).filter { it.kind == ItemKind.season }
    val left = target?.let { t ->
        c.remainingMs(t.id)?.let { "${Format.remaining(it)} in ${Format.episodeCode(t)}" }
    }
    val meta =
        @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Meta(
                    listOfNotNull(
                        c.caption(item),
                        Catalog.year(item)?.let { "$it–" },
                        item.metadata?.contentRating,
                    ),
                )
                left?.let { Text(it, color = Palette.accent, fontWeight = FontWeight.Medium) }
            }
        }
    Page("Back", { MoreMenu(PlaylistKind.tv, item.id, item) }) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (wide) 32.dp else 16.dp),
                verticalAlignment = if (wide) Alignment.Top else Alignment.Bottom,
            ) {
                Poster(item, if (wide) 200.dp else 112.dp)
                Column(
                    Modifier.widthIn(max = 680.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Titles(null, {}, Catalog.title(item), meta, big = wide)
                    if (wide) ShowActions(target, seasons)
                    if (wide) Overview(item)
                }
            }
        }
        if (!wide) {
            item { ShowActions(target, seasons) }
            item { Overview(item) }
        }
        item {
            Strip("Seasons", seasons) { season ->
                val unwatched = c.unwatchedCount(season.id)
                PosterTile(
                    season,
                    Format.plural(c.children(season.id).size, "episode"),
                    { nav.open(Route.Details(season.id)) },
                    Modifier.width(if (wide) 140.dp else 104.dp),
                    kind = PlaceholderKind.Show,
                ) {
                    if (unwatched > 0) Badge("$unwatched new")
                }
            }
        }
        occasionStrips(occasions) { if (wide) 140.dp else 104.dp }
        item { Cast(credits) }
        credits
            ?.let { crewLines(it, episode = false) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { lines -> item { DetailLines(lines, null, false, null, {}) } }
    }
}

@Composable
private fun ShowActions(target: Item?, seasons: List<Item>) {
    val nav = LocalNav.current
    val c = nav.catalog.catalog
    Actions {
        target?.let { t ->
            val resuming = c.isInProgress(t.id)
            Pill(
                "${if (resuming) "Resume" else "Play"} ${Format.episodeCode(t)}",
                { nav.play(t.id, if (resuming) c.watchState(t.id)?.positionMs ?: 0 else 0) },
                primary = true,
                icon = R.drawable.ic_play,
            )
        }
        (seasons.firstOrNull { it.id == target?.parentId } ?: seasons.firstOrNull())?.let { season
            ->
            Pill("Episodes", { nav.open(Route.Details(season.id)) })
        }
    }
}

@Composable
private fun Season(item: Item) {
    val nav = LocalNav.current
    val c = nav.catalog.catalog
    val show = c.show(item)
    val episodes = c.children(item.id)
    val target = c.resumeTarget(item.id)
    val unwatched = c.unwatchedCount(item.id)
    val scope = rememberCoroutineScope()
    val twoPanes = LocalWidth.current == WidthClass.Expanded
    val heading =
        @Composable {
            Titles(
                if (twoPanes) null else show?.let(Catalog::title),
                { show?.let { nav.open(Route.Details(it.id)) } },
                if (twoPanes)
                    listOfNotNull(show?.let(Catalog::title), Catalog.title(item))
                        .joinToString(" · ")
                else Catalog.title(item),
                {
                    Meta(
                        listOfNotNull(
                            Format.plural(episodes.size, "episode"),
                            "$unwatched unwatched",
                            Catalog.year(item)?.toString(),
                        ),
                    )
                },
                big = false,
            )
        }
    val actions =
        @Composable {
            Actions {
                target?.let { t ->
                    val resuming = c.isInProgress(t.id)
                    Pill(
                        "${if (resuming) "Resume" else "Play"} E${t.episodeNumber ?: 0}",
                        {
                            nav.play(t.id, if (resuming) c.watchState(t.id)?.positionMs ?: 0 else 0)
                        },
                        primary = true,
                        icon = R.drawable.ic_play,
                    )
                }
                Pill(
                    if (unwatched == 0) "Mark all unwatched" else "Mark all watched",
                    { scope.launch { nav.catalog.setWatched(unwatched > 0, item) } },
                    icon = R.drawable.ic_check,
                )
            }
        }
    val back = "Back to ${show?.let(Catalog::title) ?: "show"}"
    if (twoPanes) {
        Column {
            BackBar(back) { MoreMenu(PlaylistKind.tv, item.id, item) }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(
                    Modifier.width(340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Poster(item, 180.dp, 16.dp)
                    heading()
                    actions()
                    Overview(item)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(episodes, key = { it.id }) {
                        EpisodeRow(it, current = it.id == target?.id, wide = true)
                    }
                    item { Box(Modifier.height(16.dp)) }
                }
            }
        }
        return
    }
    Page(back, { MoreMenu(PlaylistKind.tv, item.id, item) }) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Poster(item, 80.dp, 8.dp)
                heading()
            }
        }
        item { actions() }
        item { Overview(item) }
        item { SectionTitle("Episodes") }
        items(episodes, key = { it.id }) {
            EpisodeRow(
                it,
                current = it.id == target?.id,
                wide = LocalWidth.current != WidthClass.Compact,
            )
        }
    }
}

@Composable
private fun EpisodeRow(item: Item, current: Boolean, wide: Boolean) {
    val nav = LocalNav.current
    val c = nav.catalog.catalog
    Hold(Held.Title(item), null, { nav.open(Route.Details(item.id)) }) { gesture ->
        Row(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(if (current) Palette.raised else Color.Transparent)
                .then(gesture)
                .padding(if (wide) 12.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(
                ArtworkStore.item(item.id),
                artColor(item.id),
                Modifier.width(if (wide) 176.dp else 120.dp)
                    .height(if (wide) 99.dp else 68.dp)
                    .clip(MaterialTheme.shapes.small),
                placeholderKind(item.kind.value),
            ) {
                if (c.isWatched(item.id))
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            "Watched",
                            Modifier.size(14.dp),
                            tint = Palette.ok,
                        )
                    }
                c.progress(item.id)?.let { TileBar(it) }
                StatusBadges(isDownloaded(item.id), overBar = c.progress(item.id) != null)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "${item.episodeNumber ?: 0}. ${Catalog.title(item)}",
                    fontSize = if (wide) 17.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildAnnotatedString {
                        append(
                            listOfNotNull(
                                    item.metadata?.runtimeMinutes?.let(Format::runtime),
                                    item.metadata?.releaseDate?.let(Format::date),
                                )
                                .joinToString(" · "),
                        )
                        c.remainingMs(item.id)?.let {
                            withStyle(SpanStyle(color = Palette.accent)) {
                                append(" · " + Format.remaining(it))
                            }
                        }
                    },
                    fontSize = 12.sp,
                    color = Palette.dim,
                )
                item.metadata?.overview?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = Palette.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Episode(item: Item) {
    val nav = LocalNav.current
    val c = nav.catalog.catalog
    val credits = credits(item.id)
    val media = media(item.id)
    val verdict = verdict(item.id)
    val related = related(item.id)
    val season = c.parent(item)
    val show = c.show(item)
    val resuming = c.isInProgress(item.id)
    val position = c.watchState(item.id)?.positionMs ?: 0
    val together = rememberTogether(item.id)
    val scope = rememberCoroutineScope()
    val lines =
        credits?.let { crewLines(it, episode = true) }.orEmpty() +
            listOfNotNull(
                credits
                    ?.cast
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { cast -> "Guest stars" to cast.take(4).joinToString(", ") { it.name } },
            )
    Page(
        "Back to ${season?.let(Catalog::title) ?: "season"}",
        { MoreMenu(PlaylistKind.tv, item.id, item) },
    ) {
        item {
            Art(
                ArtworkStore.item(item.id),
                artColor(item.id),
                Modifier.widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .height(if (Wide) 360.dp else 214.dp)
                    .clip(RoundedCornerShape(16.dp)),
                placeholderKind(item.kind.value),
            ) {
                c.progress(item.id)?.let { TileBar(it) }
            }
        }
        item {
            Column(
                Modifier.widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Titles(
                    listOfNotNull(show?.let(Catalog::title), season?.let(Catalog::title))
                        .joinToString(" · ")
                        .ifEmpty { null },
                    { season?.let { nav.open(Route.Details(it.id)) } },
                    "${item.episodeNumber ?: 0}. ${Catalog.title(item)}",
                    {
                        Meta(
                            listOfNotNull(
                                item.metadata?.runtimeMinutes?.let(Format::runtime),
                                item.metadata?.releaseDate?.let(Format::date),
                            ),
                            media?.tags.orEmpty(),
                            c.remainingMs(item.id)?.let(Format::remaining),
                        )
                    },
                    big = false,
                )
                Actions {
                    if (together?.inSync == true) PlayTogether { scope.launch { together.play() } }
                    else {
                        Pill(
                            if (resuming) "Resume" else "Play",
                            { nav.play(item.id, if (resuming) position else 0) },
                            primary = true,
                            icon = R.drawable.ic_play,
                        )
                        if (resuming) Pill("Start over", { nav.play(item.id, 0) })
                    }
                    if (!resuming) WatchedPill(item)
                    together?.let { TogetherAction(it) }
                    Neighbors(related, episode = true)
                }
                together?.let { TogetherNote(it, Catalog.title(item)) }
                Overview(item)
                DetailLines(lines, media, true, null, {}, verdict = verdict)
            }
        }
        c.nextEpisodes(item)
            .takeIf { it.isNotEmpty() }
            ?.let { next ->
                item {
                    Column(Modifier.widthIn(max = 720.dp)) {
                        SectionTitle("Up next", Modifier.padding(bottom = 6.dp))
                        next.forEach { EpisodeRow(it, current = false, wide = Wide) }
                    }
                }
            }
    }
}
