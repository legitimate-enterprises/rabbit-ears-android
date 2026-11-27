package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Genre
import enterprises.legitimate.rabbitears.api.models.MusicAlbum
import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.kit.AlbumSort
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.MusicModel
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.SelectKind
import enterprises.legitimate.rabbitears.kit.Selection
import enterprises.legitimate.rabbitears.kit.SongSort
import enterprises.legitimate.rabbitears.kit.length
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

private enum class MusicView(val label: String) {
    Albums("Albums"),
    Artists("Artists"),
    Songs("Songs"),
    Playlists("Playlists"),
    Stations("Stations"),
}

/**
 * Music: Albums, Artists, Playlists, Songs and Stations behind tabs, with the now-playing pane
 * beside.
 */
@Composable
fun MusicTab() {
    val nav = LocalNav.current
    val library = nav.musicLibrary
    val player = nav.music
    var view by rememberSaveable { mutableStateOf(MusicView.Albums) }
    var sort by rememberStored("music.albumSort", AlbumSort.Title)
    var songSort by rememberStored("music.songSort", SongSort.Title)
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    val genres by produceState(emptyList<Genre>(), library) { value = library.genres() }
    val selection = remember { Selection() }
    val selectKind =
        when (view) {
            MusicView.Albums -> SelectKind.Album
            MusicView.Artists -> SelectKind.Artist
            MusicView.Songs -> SelectKind.Song
            MusicView.Playlists,
            MusicView.Stations -> null
        }
    // A watch change elsewhere reads the list shown again, keeping what is selected.
    val watched = nav.app.live.watched
    LaunchedEffect(view, genre, songSort) { selection.end() }
    val load: suspend () -> Unit = {
        when (view) {
            MusicView.Albums -> library.loadAlbums(genre, watched)
            MusicView.Artists -> library.loadArtists(genre, watched)
            MusicView.Songs -> library.loadSongs(songSort, genre, watched)
            MusicView.Playlists -> nav.playlists.load()
            MusicView.Stations -> nav.stations.load(PlaylistKind.music)
        }
    }
    LaunchedEffect(view, genre, songSort, watched) { load() }
    LaunchedEffect(nav.playlists) { if (!nav.playlists.loaded) nav.playlists.load() }
    var creating by remember { mutableStateOf(false) }
    if (creating) NewPlaylist(PlaylistKind.music) { creating = false }
    val wide = LocalWidth.current != WidthClass.Compact
    val pane =
        LocalWidth.current == WidthClass.Expanded &&
            player?.current != null &&
            player.station == null
    val albums = library.albums?.let { MusicModel.sorted(it, sort) }
    val playlists = nav.playlists.of(PlaylistKind.music)
    val count =
        when (view) {
            MusicView.Albums -> albums?.let { Format.plural(it.size, "album") }
            MusicView.Artists -> library.artists?.let { Format.plural(it.size, "artist") }
            MusicView.Playlists -> Format.plural(playlists.size, "playlist")
            MusicView.Songs -> library.songs?.let { Format.plural(it.size, "song") }
            MusicView.Stations -> Format.plural(nav.stations.of(PlaylistKind.music).size, "station")
        }
    val filtered = view == MusicView.Albums || view == MusicView.Artists || view == MusicView.Songs
    val sortMenu =
        @Composable {
            when (view) {
                MusicView.Albums ->
                    SortMenu(AlbumSort.entries, sort, { it.label }, { sort = it }, genres, genre) {
                        genre = it
                    }
                MusicView.Songs ->
                    SortMenu(
                        SongSort.entries,
                        songSort,
                        { it.label },
                        { songSort = it },
                        genres,
                        genre,
                    ) {
                        genre = it
                    }
                MusicView.Artists ->
                    SortMenu(emptyList<SongSort>(), null, { it.label }, {}, genres, genre) {
                        genre = it
                    }
                else -> Unit
            }
        }
    val shown =
        when (view) {
            MusicView.Albums -> albums.orEmpty().map { it.id }
            MusicView.Artists -> library.artists.orEmpty().map { it.id }
            MusicView.Songs -> library.songs.orEmpty().map { it.id }
            MusicView.Playlists,
            MusicView.Stations -> emptyList()
        }
    Row(Modifier.fillMaxSize()) {
        Refreshable(
            {
                library.forget()
                load()
            },
            Modifier.weight(1f),
        ) {
            LazyVerticalGrid(
                GridCells.Adaptive(if (wide) 136.dp else 104.dp),
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Header("Music", count, trailing = { SelectToggle(selection, selectKind) }) {
                            sortMenu()
                        }
                        ViewTabs(
                            MusicView.entries.filter {
                                it != MusicView.Playlists ||
                                    nav.playlists.offersView(PlaylistKind.music)
                            },
                            view,
                            { it.label },
                        ) {
                            view = it
                        }
                    }
                }
                if (wide && filtered)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), Arrangement.End) { sortMenu() }
                    }
                library.error?.let {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(it, color = Palette.accent) }
                }
                when (view) {
                    MusicView.Albums -> {
                        if (albums?.isEmpty() == true) empty("No music yet.")
                        items(albums.orEmpty(), key = { it.id }) {
                            AlbumTile(it, selection = selection)
                        }
                    }
                    MusicView.Artists ->
                        items(library.artists.orEmpty(), key = { it.id }) { artist ->
                            Column(
                                Modifier.pick(selection, artist.id) {
                                    nav.open(Route.Artist(artist.id))
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // The badge sits outside the circle's clip, on its square's corner.
                                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                    Art(
                                        ArtworkStore.item(artist.id),
                                        artColor(artist.id),
                                        Modifier.matchParentSize().clip(CircleShape),
                                        PlaceholderKind.Artist,
                                    )
                                    StatusBadges(isDownloaded(artist.id))
                                }
                                Text(
                                    artist.name,
                                    Modifier.padding(top = 6.dp),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    MusicView.Playlists ->
                        playlistCells(PlaylistKind.music, nav) { creating = true }
                    MusicView.Stations -> stationCells(PlaylistKind.music, nav, wide)
                    MusicView.Songs ->
                        itemsIndexed(
                            library.songs.orEmpty(),
                            key = { _, s -> s.id },
                            span = { _, _ -> GridItemSpan(maxLineSpan) },
                        ) { index, song ->
                            SongRow(
                                song,
                                null,
                                player?.current?.id == song.id,
                                album = true,
                                selection = selection,
                            ) {
                                player?.scope?.launch {
                                    player.play(library.songs.orEmpty(), index, "Songs")
                                }
                            }
                        }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(80.dp)) }
            }
            SelectionBar(selection, selectKind, shown, Modifier.align(Alignment.BottomCenter)) {}
        }
        if (pane && player != null)
            NowPlayingPane(
                player,
                Modifier.width(360.dp)
                    .fillMaxHeight()
                    .padding(start = 24.dp, top = 24.dp, bottom = 24.dp),
            )
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.empty(text: String) =
    item(span = { GridItemSpan(maxLineSpan) }) { Text(text, color = Palette.dim) }

/** Sort, when the view has one, and Genre, when the library has any, in one menu. */
@Composable
private fun <S> SortMenu(
    sorts: List<S>,
    sort: S?,
    label: (S) -> String,
    onSort: (S) -> Unit,
    genres: List<Genre>,
    genre: String?,
    onGenre: (String?) -> Unit,
) {
    if (sorts.isEmpty() && genres.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_filter, "Sort and filter", { open = true })
        DropdownMenu(open, { open = false }) {
            if (sorts.isNotEmpty()) {
                MenuHeading("Sort")
                sorts.forEach {
                    Checked(label(it), it == sort) {
                        onSort(it)
                        open = false
                    }
                }
            }
            if (genres.isNotEmpty()) {
                if (sorts.isNotEmpty()) HorizontalDivider()
                MenuHeading("Genre")
                Checked("Any", genre == null) {
                    onGenre(null)
                    open = false
                }
                genres.forEach {
                    Checked(it.name, it.id == genre) {
                        onGenre(it.id)
                        open = false
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Palette.dim)
}

@Composable
fun AlbumTile(album: MusicAlbum, modifier: Modifier = Modifier, selection: Selection? = null) {
    val nav = LocalNav.current
    Column(modifier.pick(selection, album.id) { nav.open(Route.Album(album.id)) }) {
        Art(
            ArtworkStore.item(album.id),
            artColor(album.id),
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small),
            PlaceholderKind.Album,
        ) {
            StatusBadges(isDownloaded(album.id))
        }
        TileText(album.title, album.artistName)
    }
}

/** A song in a list; the one playing is goldenrod. */
@Composable
private fun SongRow(
    song: MusicSong,
    number: Int?,
    current: Boolean,
    album: Boolean = false,
    selection: Selection? = null,
    onClick: () -> Unit,
) {
    val color = if (current) Palette.accent else Palette.text
    Row(
        Modifier.fillMaxWidth().pick(selection, song.id, open = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        number?.let {
            Text(
                "$it",
                Modifier.width(20.dp),
                color = if (current) Palette.accent else Palette.dim,
                textAlign = TextAlign.End,
            )
        }
        if (album) AlbumArt(song.albumId, 40.dp, 4.dp)
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (album)
                Text(
                    "${song.artistName} · ${song.albumTitle}",
                    fontSize = 12.sp,
                    color = Palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
        if (isDownloaded(song.id)) DownloadedMark()
        Text(Format.length(song.durationMs), color = Palette.dim, fontSize = 13.sp)
    }
}

@Composable
private fun PlayShuffle(onPlay: (shuffled: Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Play", { onPlay(false) }, primary = true, icon = R.drawable.ic_play)
        Pill("Shuffle", { onPlay(true) }, icon = R.drawable.ic_shuffle)
    }
}

/** Header art beside its text, as the Artist and Album boards draw it. */
@Composable
private fun MusicHeader(art: @Composable () -> Unit, text: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { text() }
    }
}

@Composable
private fun DetailPage(
    reload: suspend () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Refreshable(reload) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { BackBar("Back", actions) }
            content()
            item { Box(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun ArtistScreen(id: String) {
    val nav = LocalNav.current
    val scope = nav.music?.scope
    val detail = rememberLoaded(id, nav.app.live.watched) { nav.musicLibrary.artist(id) }
    val d =
        detail.value
            ?: return DetailPage(detail::reload) {
                nav.musicLibrary.error?.let { e -> item { Text(e) } }
            }
    val artist = d.artist
    val play = { shuffled: Boolean ->
        scope?.launch {
            val songs = nav.musicLibrary.songsBy(id)
            nav.music?.play(songs, source = artist.name, shuffled = shuffled)
        }
        Unit
    }
    DetailPage(detail::reload, { MoreMenu(PlaylistKind.music, artist.id, name = artist.name) }) {
        item {
            MusicHeader(
                {
                    Art(
                        ArtworkStore.item(artist.id),
                        artColor(artist.id),
                        Modifier.size(112.dp).clip(CircleShape),
                        PlaceholderKind.Artist,
                    )
                },
            ) {
                Text(artist.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOfNotNull(
                            Format.plural(artist.albumCount, "album"),
                            Format.plural(artist.songCount, "song"),
                            artist.genres.firstOrNull()?.name,
                        )
                        .forEach { Text(it, color = Palette.dim) }
                }
            }
        }
        item { PlayShuffle(play) }
        artist.overview?.let { item { Text(it, color = Palette.secondary, lineHeight = 21.sp) } }
        if (d.popular.isNotEmpty()) {
            item { SectionTitle("Popular") }
            itemsIndexed(d.popular, key = { _, s -> s.id }) { index, song ->
                SongRow(song, index + 1, nav.music?.current?.id == song.id) {
                    scope?.launch { nav.music?.play(d.popular, index, artist.name) }
                }
            }
        }
        item {
            Strip("Albums", d.albums) {
                AlbumTile(it, Modifier.width(if (LocalWidth.current.bottomBar) 112.dp else 140.dp))
            }
        }
    }
}

@Composable
fun AlbumScreen(id: String) {
    val nav = LocalNav.current
    val scope = nav.music?.scope
    val detail = rememberLoaded(id, nav.app.live.watched) { nav.musicLibrary.album(id) }
    val d =
        detail.value
            ?: return DetailPage(detail::reload) {
                nav.musicLibrary.error?.let { e -> item { Text(e) } }
            }
    val album = d.album
    val play = { start: Int, shuffled: Boolean ->
        scope?.launch { nav.music?.play(d.songs, start, album.title, shuffled = shuffled) }
        Unit
    }
    val discs = d.songs.map { it.discNumber ?: 1 }.distinct()
    DetailPage(
        detail::reload,
        {
            MoreMenu(PlaylistKind.music, album.id, name = album.title) { close ->
                MenuAction("Go to artist") {
                    close()
                    nav.open(Route.Artist(album.artistId))
                }
            }
        },
    ) {
        item {
            MusicHeader({ AlbumArt(album.id, 136.dp, 12.dp) }) {
                Text(
                    album.artistName,
                    Modifier.clickable { nav.open(Route.Artist(album.artistId)) },
                    color = Palette.accent,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    album.title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 30.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    listOfNotNull(
                            album.year?.toString(),
                            Format.plural(album.songCount, "song"),
                            Format.minutes(album.durationMs),
                        )
                        .forEach { Text(it, color = Palette.dim) }
                    if (album.lossless) Tag("Lossless")
                }
            }
        }
        item { PlayShuffle { play(0, it) } }
        d.songs.forEachIndexed { index, song ->
            val disc = song.discNumber ?: 1
            if (discs.size > 1 && d.songs.indexOfFirst { (it.discNumber ?: 1) == disc } == index)
                item { SectionTitle("Disc $disc") }
            item(key = song.id) {
                SongRow(song, song.trackNumber ?: index + 1, nav.music?.current?.id == song.id) {
                    play(index, false)
                }
            }
        }
    }
}
