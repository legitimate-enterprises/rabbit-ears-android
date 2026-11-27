package enterprises.legitimate.rabbitears.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.GridFilter
import enterprises.legitimate.rabbitears.kit.GridSort
import enterprises.legitimate.rabbitears.kit.SelectKind
import enterprises.legitimate.rabbitears.kit.Selection
import enterprises.legitimate.rabbitears.kit.enumNamed
import enterprises.legitimate.rabbitears.kit.placeholderKind
import enterprises.legitimate.rabbitears.ui.theme.Palette

private enum class GridView {
    Titles,
    Playlists,
    Collections,
    Stations,
}

/**
 * TV (shows) and Movies share one grid: filter chips, sort, genre and unwatched marks, and the
 * Playlists, Collections and Stations views.
 */
@Composable
fun Grid(kind: ItemKind) {
    val nav = LocalNav.current
    val model = nav.catalog
    val c = model.catalog
    val tv = kind == ItemKind.show
    var stored by rememberStored("grid.${kind.name}.filter", GridFilter.All)
    var sort by rememberStored("grid.${kind.name}.sort", GridSort.RecentlyAdded)
    var genre by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var view by rememberSaveable(kind) { mutableStateOf(GridView.Titles) }
    var creating by remember { mutableStateOf(false) }
    var creatingCollection by remember { mutableStateOf(false) }
    val selection = remember(kind) { Selection() }
    val selectKind =
        (if (tv) SelectKind.Show else SelectKind.Movie).takeIf { view == GridView.Titles }
    LaunchedEffect(view) { selection.end() }
    val type = if (tv) PlaylistKind.tv else PlaylistKind.movies
    // A kid's Playlists view shows only once something is shared with them.
    LaunchedEffect(nav.playlists) { if (!nav.playlists.loaded) nav.playlists.load() }
    LaunchedEffect(view) {
        when (view) {
            GridView.Titles -> Unit
            GridView.Playlists -> nav.playlists.load()
            GridView.Collections -> nav.collections.load()
            GridView.Stations -> nav.stations.load(type)
        }
    }
    if (creating) NewPlaylist(type) { creating = false }
    if (creatingCollection) NewCollection { creatingCollection = false }
    val filters =
        listOfNotNull(
            GridFilter.All,
            GridFilter.Unwatched,
            GridFilter.InProgress,
            GridFilter.Kids.takeUnless { nav.app.isKid },
        )
    // The filter is stored per device, so a kid may find an adult's Kids.
    val filter = stored.takeIf { it in filters } ?: GridFilter.All
    // A Hide that keeps kids' titles out of the catalog has the Kids pill ask the server for them.
    LaunchedEffect(kind, filter, model.hideKidsContent, nav.app.offline) {
        if (filter == GridFilter.Kids && !nav.app.offline) model.loadKids(kind)
    }
    val views =
        listOfNotNull(
            GridView.Titles,
            GridView.Collections.takeUnless { tv },
            GridView.Playlists.takeIf { nav.playlists.offersView(type) },
            GridView.Stations,
        )
    val genres = c.genres(kind)
    val titles = c.grid(kind, filter, sort, genre)
    val wide = LocalWidth.current != WidthClass.Compact
    val count =
        when (view) {
            GridView.Playlists -> Format.plural(nav.playlists.of(type).size, "playlist")
            GridView.Stations -> Format.plural(nav.stations.of(type).size, "station")
            GridView.Collections -> Format.plural(nav.collections.collections.size, "collection")
            GridView.Titles -> Format.plural(titles.size, if (tv) "show" else "movie")
        }
    val genreName = genres.firstOrNull { it.id == genre }?.name ?: "Any"
    val reload: suspend () -> Unit = {
        when (view) {
            GridView.Titles -> model.reload()
            GridView.Playlists -> nav.playlists.load()
            GridView.Collections -> nav.collections.load()
            GridView.Stations -> nav.stations.load(type)
        }
    }

    Refreshable(reload) {
        LazyVerticalGrid(
            GridCells.Adaptive(if (wide) 136.dp else 104.dp),
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Header(
                        if (tv) "TV" else "Movies",
                        count,
                        trailing = { SelectToggle(selection, selectKind) },
                    ) {
                        if (!wide) {
                            var open by remember { mutableStateOf(false) }
                            Box {
                                IconAction(R.drawable.ic_filter, "Sort and filter", { open = true })
                                DropdownMenu(open, { open = false }) {
                                    Text(
                                        "Sort",
                                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        color = Palette.dim,
                                    )
                                    GridSort.entries.forEach {
                                        Checked(it.label, it == sort) {
                                            sort = it
                                            open = false
                                        }
                                    }
                                    if (genres.isNotEmpty()) {
                                        HorizontalDivider()
                                        Text(
                                            "Genre",
                                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            color = Palette.dim,
                                        )
                                        Checked("Any", genre == null) {
                                            genre = null
                                            open = false
                                        }
                                        genres.forEach {
                                            Checked(it.name, it.id == genre) {
                                                genre = it.id
                                                open = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ViewTabs(
                        views,
                        view,
                        {
                            when (it) {
                                GridView.Titles -> if (tv) "Shows" else "Movies"
                                else -> it.name
                            }
                        },
                    ) {
                        view = it
                    }
                }
            }
            if (view == GridView.Titles)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            filters.forEach { Chip(it.label, it == filter) { stored = it } }
                        }
                        if (wide) {
                            Select("Sort", sort.label, GridSort.entries, { it.label }) { sort = it }
                            if (genres.isNotEmpty())
                                Select(
                                    "Genre",
                                    genreName,
                                    listOf(null) + genres,
                                    { it?.name ?: "Any" },
                                ) {
                                    genre = it?.id
                                }
                        }
                    }
                }
            if (view == GridView.Playlists) {
                playlistCells(type, nav) { creating = true }
                item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(8.dp)) }
                return@LazyVerticalGrid
            }
            if (view == GridView.Stations) {
                stationCells(type, nav, wide)
                item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(8.dp)) }
                return@LazyVerticalGrid
            }
            if (view == GridView.Collections) {
                collectionCells(nav) { creatingCollection = true }
                item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(8.dp)) }
                return@LazyVerticalGrid
            }
            if (model.loaded && titles.isEmpty())
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (tv) "No shows match." else "No movies match.", color = Palette.dim)
                }
            items(titles, key = { it.id }) { item ->
                val unwatched = c.unwatchedCount(item.id)
                PosterTile(
                    item,
                    c.caption(item),
                    { nav.open(Route.Details(item.id)) },
                    selection = selection,
                    kind = placeholderKind(item.kind.value),
                ) {
                    if (tv && unwatched > 0) Badge("$unwatched new")
                    if (!tv && unwatched > 0) UnwatchedDot()
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(80.dp)) }
        }
        SelectionBar(
            selection,
            selectKind,
            titles.map { it.id },
            Modifier.align(Alignment.BottomCenter),
        ) {}
    }
}

/** The tablet's outlined Sort and Genre controls: a dim label, the value, a chevron. */
@Composable
private fun <T> Select(
    label: String,
    value: String,
    options: List<T>,
    name: (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            { open = true },
            Modifier.height(36.dp),
            shape = MaterialTheme.shapes.extraSmall,
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.text),
        ) {
            Text(label, color = Palette.dim)
            Text(value, Modifier.padding(start = 6.dp))
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                null,
                Modifier.padding(start = 6.dp).height(18.dp),
            )
        }
        DropdownMenu(open, { open = false }) {
            options.forEach {
                Checked(name(it), name(it) == value) {
                    onPick(it)
                    open = false
                }
            }
        }
    }
}

/** A sort or filter kept on this device under [key], so it outlasts the app. */
@Composable
inline fun <reified E : Enum<E>> rememberStored(key: String, default: E): MutableState<E> {
    val prefs = LocalContext.current.getSharedPreferences("browse", Context.MODE_PRIVATE)
    val state = remember(key) { mutableStateOf(enumNamed(prefs.getString(key, null), default)) }
    LaunchedEffect(key, state.value) { prefs.edit { putString(key, state.value.name) } }
    return state
}
