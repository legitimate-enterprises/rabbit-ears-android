package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.FavoriteKind
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.kit.ArtworkPath
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.FavoriteShelves
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.placeholderKind
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** A page's star: filled in the accent while [id] is a favorite, and a tap toggles it. */
@Composable
fun FavoriteStar(id: String, kind: FavoriteKind) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val on = id in nav.favorites
    IconButton(
        { scope.launch { nav.favorites.set(id, kind, !on) } },
        Modifier.semantics { selected = on },
    ) {
        Icon(
            painterResource(if (on) R.drawable.ic_star_filled else R.drawable.ic_star),
            "Favorite",
            tint = if (on) Palette.accent else Palette.text,
        )
    }
}

/** The star of a tile's or row's menu, where none is on screen; the menu stays open. */
@Composable
fun FavoriteMenuItem(id: String, kind: FavoriteKind) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val on = id in nav.favorites
    MenuAction(if (on) "Remove from Favorites" else "Add to Favorites") {
        scope.launch { nav.favorites.set(id, kind, !on) }
    }
}

/** Every favorite of the profile, one section per kind that has any, as Search draws them. */
@Composable
fun Favorites() {
    val nav = LocalNav.current
    val c = nav.catalog.catalog
    LaunchedEffect(Unit) { nav.favorites.load() }
    val shelves by
        produceState<FavoriteShelves?>(null, nav.favorites.favorites, c) {
            value = nav.favorites.shelves(c)
        }
    val wide = LocalWidth.current != WidthClass.Compact
    val poster = if (wide) 136.dp else 104.dp
    val title =
        @Composable { it: Item ->
            val caption =
                if (it.kind == ItemKind.episode)
                    listOfNotNull(c.show(it)?.let(Catalog::title), Format.episodeCode(it))
                        .joinToString(" · ")
                else c.caption(it)
            PosterTile(
                it,
                caption,
                { nav.open(Route.Details(it.id)) },
                Modifier.width(poster),
                kind = placeholderKind(it.kind.value),
            )
        }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(R.drawable.ic_back, "Back", { nav.back() })
                Text("Favorites", style = MaterialTheme.typography.headlineMedium)
            }
        }
        nav.favorites.error?.let { item { Text(it, color = Palette.accent) } }
        val found = shelves ?: return@LazyColumn
        if (found.isEmpty && nav.favorites.error == null)
            return@LazyColumn item {
                Text(
                    "Nothing starred yet. Favorite is beside a title’s Play or in its menu.",
                    color = Palette.dim,
                )
            }
        item { Strip("Movies", found.movies, tile = title) }
        item { Strip("Shows", found.shows, tile = title) }
        item { Strip("Seasons", found.seasons, tile = title) }
        item { Strip("Episodes", found.episodes, tile = title) }
        item {
            Strip("Artists", found.artists) {
                Round(ArtworkPath.item(it.id, it.posterUrl), it.id, it.name, isDownloaded(it.id)) {
                    nav.open(Route.Artist(it.id))
                }
            }
        }
        item { Strip("Albums", found.albums) { AlbumTile(it, Modifier.width(poster)) } }
        if (found.songs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            items(found.songs, key = { it.id }) { song ->
                val music = nav.music
                MediaRow(
                    song.title,
                    "${song.artistName} · ${song.albumTitle}",
                    {
                        music?.scope?.launch {
                            music.play(found.songs, found.songs.indexOf(song), "Favorites")
                        }
                    },
                    { AlbumArt(song.albumId, song.posterUrl, 48.dp, 6.dp) },
                    downloaded = isDownloaded(song.id),
                    held = Held.Song(song),
                )
            }
        }
        item {
            Strip("Videos", found.videos) {
                WideTile(
                    Held.Video(it),
                    ArtworkPath.item(it.id, it.posterUrl),
                    it.title,
                    it.channelTitle,
                    { nav.open(Route.Video(it.id)) },
                    Modifier.width(220.dp),
                    kind = PlaceholderKind.Video,
                )
            }
        }
    }
}
