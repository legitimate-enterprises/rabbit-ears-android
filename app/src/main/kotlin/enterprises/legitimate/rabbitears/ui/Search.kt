package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.SearchResult
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.isEmpty
import enterprises.legitimate.rabbitears.kit.placeholderKind
import enterprises.legitimate.rabbitears.kit.tv
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun Search() {
    val nav = LocalNav.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResult?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { nav.catalog.searchTyped(query) { results = it } }
    val wide = LocalWidth.current != WidthClass.Compact
    val poster = if (wide) 136.dp else 104.dp

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(R.drawable.ic_back, "Back", { nav.back() })
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
        }
        val found = results ?: return@LazyColumn
        if (found.isEmpty)
            return@LazyColumn item {
                Text("Nothing matches “${query.trim()}”.", color = Palette.dim)
            }
        val c = nav.catalog.catalog
        // Movies, TV, music, YouTube, then people; within each, the most specific kind first.
        item {
            Strip("Movies", found.movies) {
                PosterTile(
                    it,
                    c.caption(it),
                    { nav.open(Route.Details(it.id)) },
                    Modifier.width(poster),
                    kind = PlaceholderKind.Movie,
                )
            }
        }
        item {
            Strip("TV", found.tv) {
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
        }
        if (found.songs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            items(found.songs) { song ->
                val music = nav.music
                MediaRow(
                    song.title,
                    "${song.artistName} · ${song.albumTitle}",
                    { music?.scope?.launch { music.play(listOf(song), 0, "Search") } },
                    { AlbumArt(song.albumId, 48.dp, 6.dp) },
                    downloaded = isDownloaded(song.id),
                )
            }
        }
        item { Strip("Albums", found.albums) { AlbumTile(it, Modifier.width(poster)) } }
        item {
            Strip("Artists", found.artists) {
                Round(ArtworkStore.item(it.id), it.id, it.name, isDownloaded(it.id)) {
                    nav.open(Route.Artist(it.id))
                }
            }
        }
        item {
            Strip("Videos", found.videos) {
                WideTile(
                    Held.Video(it),
                    ArtworkStore.item(it.id),
                    it.title,
                    it.channelTitle,
                    { nav.open(Route.Video(it.id)) },
                    Modifier.width(220.dp),
                    kind = PlaceholderKind.Video,
                )
            }
        }
        item {
            Strip("Channels", found.channels) {
                Round(ArtworkStore.item(it.id), it.id, it.title, isDownloaded(it.id)) {
                    nav.open(Route.Channel(it.id))
                }
            }
        }
        item {
            Strip("People", found.people) {
                Round(ArtworkStore.person(it.id), it.id, it.name, false) {
                    nav.open(Route.Person(it.id))
                }
            }
        }
    }
}

@Composable
private fun Round(art: String, id: String, name: String, downloaded: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The badge sits outside the circle's clip, on its square's corner.
        Box(Modifier.size(80.dp)) {
            Art(art, avatarColor(id), Modifier.matchParentSize().clip(CircleShape))
            StatusBadges(downloaded)
        }
        Text(
            name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
