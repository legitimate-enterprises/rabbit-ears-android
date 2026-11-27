package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.Playlist
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import enterprises.legitimate.rabbitears.api.models.PlaylistEntryKind
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.PlaylistShareAccess
import enterprises.legitimate.rabbitears.api.models.Station
import enterprises.legitimate.rabbitears.kit.CustomImageModel
import enterprises.legitimate.rabbitears.kit.Editions
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.ImageRequest
import enterprises.legitimate.rabbitears.kit.ImageTarget
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.PlaylistRun
import enterprises.legitimate.rabbitears.kit.PlaylistSharesModel
import enterprises.legitimate.rabbitears.kit.ProfileColors
import enterprises.legitimate.rabbitears.kit.artId
import enterprises.legitimate.rabbitears.kit.caption
import enterprises.legitimate.rabbitears.kit.isEditable
import enterprises.legitimate.rabbitears.kit.isOwned
import enterprises.legitimate.rabbitears.kit.isShared
import enterprises.legitimate.rabbitears.kit.label
import enterprises.legitimate.rabbitears.kit.sharedBy
import enterprises.legitimate.rabbitears.kit.title
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** `12 songs · 48m`, `3 movies · 6h 10m` or `40 episodes · 28h`: what playing it plays. */
fun playlistCaption(p: Playlist): String =
    listOf(
            Format.plural(
                p.itemCount,
                when (p.kind) {
                    PlaylistKind.movies -> "movie"
                    PlaylistKind.tv -> "episode"
                    PlaylistKind.music -> "song"
                },
            ),
            Format.minutes(p.durationMs),
        )
        .joinToString(" · ")

@Composable
fun PlaylistTile(p: Playlist, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    Column(modifier.clickable { nav.open(Route.Playlist(p.id)) }) {
        Art(
            p.imageUrl ?: p.artItemId?.let { ArtworkStore.item(it) },
            artColor(p.id),
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small),
            PlaceholderKind.Playlist,
        ) {
            StatusBadges(isDownloaded(DownloadTarget(playlistId = p.id)), shared = p.isShared)
        }
        TileText(p.name, listOfNotNull(playlistCaption(p), p.sharedBy).joinToString(" · "))
    }
}

/** Where an item goes: a playlist, or a station's own contents, which are a playlist too. */
enum class AddTarget(val noun: String) {
    Playlist("playlist"),
    Station("station"),
}

private class AddOption(val name: String, val caption: String, val playlistId: String)

/**
 * A container, such as a show or an album, stays one entry; with [collection] the id is a
 * collection's. A title with several editions added without [editionId] asks which. [itemIds], a
 * library selection, goes in one request in place of [itemId], each title as its default edition.
 */
@Composable
fun AddTo(
    target: AddTarget,
    kind: PlaylistKind,
    itemId: String,
    collection: Boolean = false,
    editionId: String? = null,
    itemIds: List<String>? = null,
    onDone: () -> Unit,
) {
    val nav = LocalNav.current
    val model = nav.playlists
    val stations = nav.stations
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<String?>(null) }
    var rescheduling by remember { mutableStateOf<Station?>(null) }
    LaunchedEffect(model) {
        if (target == AddTarget.Playlist) model.load() else stations.load(kind)
    }
    val choices =
        if (target == AddTarget.Playlist)
            model.editable(kind).map { AddOption(it.name, playlistCaption(it), it.id) }
        else
            stations.mine(kind, nav.catalog.profileId).map {
                AddOption(it.name, it.rating ?: "", it.playlistId)
            }
    val add = { playlistId: String, edition: String? ->
        busy = true
        chosen = playlistId
        scope.launch {
            val added =
                when {
                    itemIds != null -> model.add(itemIds, playlistId)
                    collection -> model.addCollection(itemId, playlistId)
                    else -> model.add(itemId, playlistId, edition)
                }
            busy = false
            // A station just made has nothing scheduled to keep.
            val station =
                stations.stations
                    .firstOrNull { it.playlistId == playlistId }
                    ?.takeIf { target == AddTarget.Station && playlistId != made }
            if (added && station != null) rescheduling = station else if (added) onDone()
        }
    }
    val create = {
        scope.launch {
            val playlistId =
                if (target == AddTarget.Playlist) model.create(name, kind)?.id
                else stations.create(name, kind)?.playlistId
            made = playlistId
            playlistId?.let { add(it, editionId) }
        }
    }
    rescheduling?.let {
        RescheduleQuestion(it, onDone)
        return
    }
    val asking = chosen?.takeIf { model.editions.isNotEmpty() }
    val error = if (target == AddTarget.Playlist) model.error else stations.error ?: model.error
    AlertDialog(
        onDone,
        confirmButton = {
            if (asking == null)
                TextButton({ create() }, enabled = name.isNotBlank() && !busy) {
                    Text("Create and add")
                }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
        title = { Text(if (asking == null) "Add to ${target.noun}" else "Which edition?") },
        containerColor = Palette.raised,
        text = {
            if (asking != null)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    model.editions.forEach { e ->
                        Text(
                            Editions.label(e),
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !busy) { add(asking, e.id) }
                                .padding(vertical = 10.dp),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    model.error?.let { Text(it, color = Palette.accent) }
                }
            else
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !busy) { add(c.playlistId, editionId) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(c.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text(c.caption, color = Palette.dim, fontSize = 13.sp)
                        }
                    }
                    OutlinedTextField(
                        name,
                        { name = it },
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("New ${target.noun}") },
                        singleLine = true,
                    )
                    error?.let { Text(it, color = Palette.accent) }
                }
        },
    )
}

@Composable
fun NewPlaylist(kind: PlaylistKind, onDone: () -> Unit) {
    val model = LocalNav.current.playlists
    NameDialog(
        "New playlist",
        "Create",
        "",
        model.error,
        { model.create(it, kind) != null },
        onDone,
    )
}

private fun addHint(kind: PlaylistKind) =
    when (kind) {
        PlaylistKind.movies -> "Add movies from their details."
        PlaylistKind.tv -> "Add shows, seasons and episodes from their details."
        PlaylistKind.music -> "Add artists, albums and songs from their pages."
    }

fun LazyGridScope.playlistCells(kind: PlaylistKind, nav: Navigator, onNew: () -> Unit) {
    val mine = nav.playlists.of(kind)
    if (!nav.app.isKid)
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row { Pill("New playlist", onNew, icon = R.drawable.ic_playlist_add) }
        }
    if (nav.playlists.loaded && mine.isEmpty())
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "No playlists yet. " + addHint(kind),
                color = Palette.dim,
            )
        }
    items(mine, key = { it.id }) { PlaylistTile(it) }
}

@Composable
fun PlaylistScreen(id: String) {
    val nav = LocalNav.current
    val model = nav.playlists
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { if (!model.loaded) model.load() }
    val playlist = model.playlists.firstOrNull { it.id == id }
    var sharing by remember { mutableStateOf(false) }
    if (sharing && playlist != null) ShareDialog(playlist) { sharing = false }
    var renaming by remember { mutableStateOf(false) }
    if (renaming && playlist != null)
        NameDialog(
            "Rename playlist",
            "Save",
            playlist.name,
            model.error,
            { model.rename(playlist, it) },
        ) {
            renaming = false
        }
    var imaging by remember { mutableStateOf<ImageRequest?>(null) }
    imaging?.let {
        ImageDialog(it) { changed ->
            imaging = null
            if (changed) scope.launch { model.load() }
        }
    }
    var version by remember { mutableStateOf(0) }
    val loaded = rememberLoaded(id, version) { model.entries(id) }
    val entries = loaded.value
    val play = { entryId: String?, shuffled: Boolean ->
        playlist?.let { scope.launch { playFrom(nav, it, entryId, shuffled) } }
        Unit
    }
    val reload: suspend () -> Unit = {
        model.load()
        loaded.reload()
    }
    Refreshable(reload) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                BackBar("Back") {
                    if (playlist != null && (playlist.isOwned || nav.downloads != null))
                        Box {
                            var open by remember { mutableStateOf(false) }
                            var downloading by remember { mutableStateOf<DownloadAsk?>(null) }
                            IconAction(R.drawable.ic_more, "More", { open = true })
                            val ask =
                                DownloadAsk(
                                    DownloadTarget(playlistId = playlist.id),
                                    playlist.name,
                                    playlist.kind == PlaylistKind.music,
                                )
                            downloading?.let { DownloadDialog(it) { downloading = null } }
                            DropdownMenu(open, { open = false }) {
                                DownloadMenuItem(ask, { open = false }) { downloading = it }
                                if (playlist.isOwned) {
                                    DropdownMenuItem(
                                        { Text("Share") },
                                        {
                                            open = false
                                            sharing = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        { Text("Rename playlist") },
                                        {
                                            open = false
                                            renaming = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        { Text("Set image") },
                                        {
                                            open = false
                                            imaging = ImageRequest.of(playlist, model.profileId)
                                        },
                                    )
                                    if (playlist.imageUrl != null)
                                        DropdownMenuItem(
                                            { Text("Remove image") },
                                            {
                                                open = false
                                                val target =
                                                    ImageTarget.Playlist(model.profileId, id)
                                                scope.launch {
                                                    if (CustomImageModel(model.api).remove(target))
                                                        model.load()
                                                }
                                            },
                                        )
                                    DropdownMenuItem(
                                        { Text("Delete playlist") },
                                        {
                                            open = false
                                            scope.launch { if (model.delete(playlist)) nav.back() }
                                        },
                                    )
                                }
                            }
                        }
                }
            }
            playlist ?: return@LazyColumn
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.headlineMedium)
                    Text(playlistCaption(playlist), color = Palette.dim)
                    playlist.sharedBy?.let { Text(it, color = Palette.dim) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playlist.itemCount > 0) {
                        Pill(
                            "Play",
                            { play(null, false) },
                            primary = true,
                            icon = R.drawable.ic_play,
                        )
                        if (playlist.kind == PlaylistKind.music)
                            Pill("Shuffle", { play(null, true) }, icon = R.drawable.ic_shuffle)
                    }
                }
            }
            entryRows(
                nav,
                playlist.kind,
                entries,
                { play(it.id, false) },
                editable = model.adult && playlist.isEditable,
            ) {
                version++
            }
        }
    }
}

fun LazyListScope.entryRows(
    nav: Navigator,
    kind: PlaylistKind,
    entries: List<PlaylistEntry>?,
    onSong: (PlaylistEntry) -> Unit,
    editable: Boolean = true,
    onRemoved: () -> Unit,
) {
    val model = nav.playlists
    model.error?.let { item { Text(it, color = Palette.accent) } }
    if (entries?.isEmpty() == true) item { Text(addHint(kind), color = Palette.dim) }
    itemsIndexed(entries.orEmpty(), key = { _, e -> e.id }) { index, entry ->
        val scope = rememberCoroutineScope()
        EntryRow(
            entry,
            editable,
            onOpen = {
                val itemId = entry.itemId
                val collectionId = entry.collectionId
                when {
                    entry.kind == PlaylistEntryKind.song -> onSong(entry)
                    collectionId != null -> nav.open(Route.Collection(collectionId))
                    itemId == null -> Unit
                    entry.kind == PlaylistEntryKind.artist -> nav.open(Route.Artist(itemId))
                    entry.kind == PlaylistEntryKind.album -> nav.open(Route.Album(itemId))
                    else -> nav.open(Route.Details(itemId))
                }
            },
            onRemove = {
                scope.launch {
                    model.remove(entry)
                    onRemoved()
                }
            },
        )
        if (index == entries.orEmpty().lastIndex) Box(Modifier.height(16.dp))
    }
}

/** Plays the playlist's play order from the first row of [entryId], or the top. */
private suspend fun playFrom(
    nav: Navigator,
    playlist: Playlist,
    entryId: String?,
    shuffled: Boolean,
) {
    val rows = nav.playlists.playOrder(playlist.id)?.takeIf { it.isNotEmpty() } ?: return
    if (playlist.kind == PlaylistKind.music) {
        val songs = rows.filter { it.song != null }
        val start = songs.indexOfFirst { it.entryId == entryId }.coerceAtLeast(0)
        // On the music player's scope: a start outlives this screen.
        nav.music?.let {
            it.scope.launch {
                it.play(
                    songs.mapNotNull { row -> row.song },
                    start,
                    playlist.name,
                    playlistId = playlist.id,
                    shuffled = shuffled,
                )
            }
        }
    } else {
        val start = rows.indexOfFirst { it.entryId == entryId }.coerceAtLeast(0)
        nav.play(rows[start].itemId, run = PlaylistRun(playlist.id, rows, start))
    }
}

private val TITLE_ENTRIES =
    setOf(
        PlaylistEntryKind.movie,
        PlaylistEntryKind.show,
        PlaylistEntryKind.season,
        PlaylistEntryKind.episode,
    )

@Composable
private fun EntryRow(
    entry: PlaylistEntry,
    editable: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val catalog = LocalNav.current.catalog.catalog
    val item = entry.itemId?.takeIf { entry.kind in TITLE_ENTRIES }?.let(catalog::item)
    MediaRow(
        entry.title,
        entry.caption,
        onOpen,
        {
            entry.collectionId?.let { CollectionArt(it, 48.dp, 6.dp) }
                ?: AlbumArt(entry.artId!!, 48.dp, 6.dp)
        },
        "Remove from playlist".takeIf { editable },
        downloaded =
            isDownloaded(
                entry.collectionId?.let { DownloadTarget(collectionId = it) }
                    ?: DownloadTarget(itemId = entry.itemId),
            ),
        onRemove = onRemove,
        held =
            item?.let {
                Held.Title(
                    it,
                    if (editable) {
                        { close ->
                            MenuAction("Remove from playlist") {
                                close()
                                onRemove()
                            }
                        }
                    } else null,
                )
            },
    )
}

@Composable
private fun ShareDialog(playlist: Playlist, onDone: () -> Unit) {
    val nav = LocalNav.current
    val model =
        remember(playlist.id) {
            PlaylistSharesModel(nav.catalog.api, nav.catalog.profileId, playlist.id)
        }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.load() }
    AlertDialog(
        onDone,
        confirmButton = { TextButton(onDone) { Text("Done") } },
        title = {
            Column {
                Text("Share")
                Text(playlist.name, color = Palette.dim, fontSize = 13.sp)
            }
        },
        containerColor = Palette.raised,
        text = {
            LazyColumn {
                item { SectionTitle("Households") }
                items(model.households, key = { "h${it.id}" }) { h ->
                    ShareRow(
                        h.name,
                        Format.plural(h.profiles, "profile"),
                        model.access(h),
                        listOf(null) + PlaylistShareAccess.entries,
                        { scope.launch { model.set(h, it) } },
                    ) {
                        Icon(painterResource(R.drawable.ic_home), null, Modifier.size(18.dp))
                    }
                }
                item {
                    Text(
                        "Everyone in the household can see it.",
                        Modifier.padding(top = 4.dp),
                        color = Palette.dim,
                        fontSize = 13.sp,
                    )
                }
                item { SectionTitle("Profiles", Modifier.padding(top = 18.dp)) }
                if (model.profiles.isEmpty())
                    item { Text("No one else to share with yet.", color = Palette.dim) }
                items(model.profiles, key = { "p${it.profileId}" }) { p ->
                    ShareRow(
                        p.name,
                        p.subtitle,
                        p.access,
                        p.choices,
                        { scope.launch { model.set(p, it) } },
                        p.color?.let { Color(ProfileColors.argb(it)) } ?: Palette.tonal,
                    ) {
                        Text(
                            p.name.take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                model.error?.let { item { Text(it, color = Palette.accent) } }
            }
        },
    )
}

/** A household or profile with the menu that sets its access; null is Not shared. */
@Composable
private fun ShareRow(
    name: String,
    subtitle: String,
    access: PlaylistShareAccess?,
    choices: List<PlaylistShareAccess?>,
    onChange: (PlaylistShareAccess?) -> Unit,
    circle: Color = Palette.tonal,
    badge: @Composable () -> Unit,
) {
    val label = { a: PlaylistShareAccess? -> a?.label ?: "Not shared" }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).background(circle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            badge()
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty())
                Text(subtitle, color = Palette.dim, fontSize = 13.sp, maxLines = 1)
        }
        Box {
            var open by remember { mutableStateOf(false) }
            TextButton(
                { open = true },
                Modifier.semantics { contentDescription = "$name: ${label(access)}" },
            ) {
                Text(label(access), color = if (access == null) Palette.dim else Palette.text)
                Icon(
                    painterResource(R.drawable.ic_chevron_down),
                    null,
                    Modifier.size(14.dp),
                    tint = Palette.dim,
                )
            }
            DropdownMenu(open, { open = false }) {
                choices.forEach { c ->
                    DropdownMenuItem(
                        { Text(label(c)) },
                        {
                            open = false
                            if (c != access) onChange(c)
                        },
                        trailingIcon =
                            if (c == access)
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
}

@Composable
fun MediaRow(
    title: String,
    caption: String,
    onOpen: () -> Unit,
    art: @Composable () -> Unit,
    removeLabel: String? = null,
    downloaded: Boolean = false,
    onRemove: () -> Unit = {},
    /**
     * A movie, show, season, episode or video the row stands for, whose menu a long press opens.
     */
    held: Held? = null,
) {
    val row =
        @Composable { gesture: Modifier ->
            Row(
                Modifier.fillMaxWidth().then(gesture),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                art()
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(caption, fontSize = 12.sp, color = Palette.dim, maxLines = 1)
                }
                if (downloaded) DownloadedMark()
                removeLabel?.let { IconAction(R.drawable.ic_close, it, onRemove) }
            }
        }
    if (held == null) row(Modifier.clickable(onClick = onOpen))
    else Hold(held, null, onOpen, content = row)
}

@Composable
fun MenuAction(label: String, onClick: () -> Unit) = DropdownMenuItem({ Text(label) }, onClick)
