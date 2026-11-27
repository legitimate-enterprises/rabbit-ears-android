package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Collection
import enterprises.legitimate.rabbitears.api.models.CollectionSource
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.caption
import enterprises.legitimate.rabbitears.kit.moved
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun CollectionTile(c: Collection, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    Column(modifier.clickable { nav.open(Route.Collection(c.id)) }) {
        Art(
            ArtworkStore.collection(c.id),
            artColor(c.id),
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small),
            PlaceholderKind.Collection,
        ) {
            StatusBadges(
                isDownloaded(DownloadTarget(collectionId = c.id)),
                ordered = c.customOrder == true,
            )
        }
        TileText(c.name, c.caption)
    }
}

/** The Movies tab's Collections view: New collection for adults, a note while empty, the tiles. */
fun LazyGridScope.collectionCells(nav: Navigator, onNew: () -> Unit) {
    val all = nav.collections.collections
    if (!nav.app.isKid)
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row { Pill("New collection", onNew, icon = R.drawable.ic_playlist_add) }
        }
    if (nav.collections.loaded && all.isEmpty())
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("No collections yet.", color = Palette.dim)
        }
    items(all, key = { it.id }) { CollectionTile(it) }
}

@Composable
fun NewCollection(onDone: () -> Unit) {
    val model = LocalNav.current.collections
    NameDialog("New collection", "Create", "", model.error, { model.create(it) != null }, onDone)
}

/** Picks one of the household's custom collections, or names a new one, and adds the movie. */
@Composable
fun AddToCollection(itemId: Long, onDone: () -> Unit) {
    val model = LocalNav.current.collections
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.load() }
    val add = { collection: Collection ->
        busy = true
        scope.launch {
            model.add(collection.id, itemId)
            busy = false
            if (model.error == null) onDone()
        }
    }
    AlertDialog(
        onDone,
        confirmButton = {
            TextButton(
                { scope.launch { model.create(name)?.let { add(it) } } },
                enabled = name.isNotBlank() && !busy,
            ) {
                Text("Create and add")
            }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
        title = { Text("Add to collection") },
        containerColor = Palette.raised,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                model.custom.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !busy) { add(c) }
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
                    label = { Text("New collection") },
                    singleLine = true,
                )
                model.error?.let { Text(it, color = Palette.accent) }
            }
        },
    )
}

@Composable
fun MovieCollections(collections: List<Collection>, tileWidth: Dp) {
    Strip("Collections", collections) { CollectionTile(it, Modifier.width(tileWidth)) }
}

/**
 * One collection: artwork, name, overview and its movies in the server's order. A custom one is
 * editable by adults; a TMDB one, and any one to a kid, is read-only.
 */
@Composable
fun CollectionScreen(id: Long) {
    val nav = LocalNav.current
    val model = nav.collections
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    var dragged by remember { mutableLongStateOf(-1L) }
    var startAt by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    // The reload that follows a move clears the model's error, so a refusal is kept here.
    var refused by remember { mutableStateOf<String?>(null) }
    var moves by remember { mutableIntStateOf(0) }
    val rowPx = with(LocalDensity.current) { ORDER_ROW.toPx() }
    LaunchedEffect(id) { model.get(id) }
    val collection = model.collections.firstOrNull { it.id == id }
    val loaded = rememberLoaded(id, version) { model.movies(id) }
    // Sends a movie that now sits elsewhere than [from], then shows the server's order. Only the
    // newest interaction's reload is applied, so an older one cannot undo a move shown since.
    val settle = { movieId: Long, from: Int ->
        moves += 1
        val mine = moves
        val at = loaded.value.orEmpty().indexOfFirst { it.id == movieId }
        scope.launch {
            if (at != from && !model.move(id, movieId, at)) refused = model.error
            if (mine == moves) loaded.reload()
        }
    }
    val step = { movieId: Long, index: Int, by: Int ->
        refused = null
        loaded.value = loaded.value.orEmpty().moved(movieId, index + by)
        settle(movieId, index)
    }
    val movies = loaded.value
    val adult = !nav.app.isKid
    val editable = adult && collection?.source == CollectionSource.custom
    if (renaming && collection != null)
        NameDialog(
            "Rename collection",
            "Save",
            collection.name,
            model.error,
            { model.rename(collection, it) },
        ) {
            renaming = false
        }
    val reload: suspend () -> Unit = {
        model.get(id)
        loaded.reload()
    }
    Refreshable(reload) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                BackBar("Back") {
                    if (ordering) TextButton({ ordering = false }) { Text("Done") }
                    else if (collection != null)
                        MoreMenu(
                            PlaylistKind.movies,
                            id,
                            collection = true,
                            name = collection.name,
                            extras =
                                if (editable) {
                                    { close ->
                                        MenuAction("Edit order") {
                                            close()
                                            ordering = true
                                        }
                                        if (collection.customOrder == true)
                                            MenuAction("Reset to release order") {
                                                close()
                                                scope.launch {
                                                    if (model.resetOrder(id)) loaded.reload()
                                                }
                                            }
                                        MenuAction("Rename collection") {
                                            close()
                                            renaming = true
                                        }
                                        MenuAction("Delete collection") {
                                            close()
                                            scope.launch {
                                                if (model.delete(collection)) nav.back()
                                            }
                                        }
                                    }
                                } else null,
                        )
                }
            }
            collection ?: return@LazyColumn
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val width = if (LocalWidth.current == WidthClass.Expanded) 160.dp else 112.dp
                    Art(
                        ArtworkStore.collection(id),
                        artColor(id),
                        Modifier.width(width)
                            .height(width * 1.5f)
                            .clip(MaterialTheme.shapes.medium),
                        PlaceholderKind.Collection,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(collection.name, style = MaterialTheme.typography.headlineMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(collection.caption, color = Palette.dim)
                            if (collection.customOrder == true)
                                Text("In your order", color = Palette.dim)
                        }
                    }
                }
            }
            collection.overview
                ?.takeIf { it.isNotBlank() }
                ?.let { item { Text(it, color = Palette.secondary) } }
            (refused ?: model.error)?.let { item { Text(it, color = Palette.accent) } }
            if (editable && movies?.isEmpty() == true)
                item {
                    Text("No movies yet. Add them from a movie's details.", color = Palette.dim)
                }
            if (ordering)
                itemsIndexed(movies.orEmpty(), key = { _, m -> m.id }) { index, movie ->
                    val title = Catalog.title(movie)
                    Row(
                        Modifier.fillMaxWidth()
                            .height(ORDER_ROW)
                            .graphicsLayer {
                                translationY = if (movie.id == dragged) offset else 0f
                            }
                            .semantics {
                                customActions =
                                    listOfNotNull(
                                        CustomAccessibilityAction("Move up") {
                                                step(movie.id, index, -1)
                                                true
                                            }
                                            .takeIf { index > 0 },
                                        CustomAccessibilityAction("Move down") {
                                                step(movie.id, index, 1)
                                                true
                                            }
                                            .takeIf { index < movies.orEmpty().lastIndex },
                                    )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "${index + 1}",
                            Modifier.width(24.dp),
                            color = Palette.dim,
                            textAlign = TextAlign.End,
                        )
                        AlbumArt(movie.id, 48.dp, 6.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                nav.catalog.catalog.caption(movie),
                                fontSize = 12.sp,
                                color = Palette.dim,
                                maxLines = 1,
                            )
                        }
                        Icon(
                            painterResource(R.drawable.ic_drag),
                            "Reorder $title",
                            Modifier.size(44.dp).padding(10.dp).pointerInput(movie.id) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        refused = null
                                        moves += 1
                                        dragged = movie.id
                                        offset = 0f
                                        startAt =
                                            loaded.value.orEmpty().indexOfFirst {
                                                it.id == movie.id
                                            }
                                    },
                                    onDragEnd = {
                                        dragged = -1
                                        settle(movie.id, startAt)
                                    },
                                    onDragCancel = {
                                        dragged = -1
                                        settle(
                                            movie.id,
                                            loaded.value.orEmpty().indexOfFirst {
                                                it.id == movie.id
                                            },
                                        )
                                    },
                                ) { _, delta ->
                                    offset += delta
                                    val shown = loaded.value.orEmpty()
                                    val at = shown.indexOfFirst { it.id == movie.id }
                                    val to = at + (offset / rowPx).roundToInt().coerceIn(-1, 1)
                                    if (to != at && to in shown.indices) {
                                        loaded.value = shown.moved(movie.id, to)
                                        offset -= (to - at) * rowPx
                                    }
                                }
                            },
                            tint = Palette.secondary,
                        )
                    }
                }
            else
                items(movies.orEmpty(), key = { it.id }) { movie ->
                    val remove = {
                        scope.launch {
                            model.remove(id, movie.id)
                            version++
                        }
                        Unit
                    }
                    MediaRow(
                        Catalog.title(movie),
                        nav.catalog.catalog.caption(movie),
                        { nav.open(Route.Details(movie.id)) },
                        { AlbumArt(movie.id, 48.dp, 6.dp) },
                        "Remove from collection".takeIf { editable },
                        downloaded = isDownloaded(movie.id),
                        onRemove = remove,
                        held =
                            Held.Title(
                                movie,
                                if (editable) {
                                    { close ->
                                        MenuAction("Remove from collection") {
                                            close()
                                            remove()
                                        }
                                    }
                                } else null,
                            ),
                    )
                }
            item { Box(Modifier.height(16.dp)) }
        }
    }
}

/** Rows are a fixed height so a drag moves one place per row's height. */
private val ORDER_ROW = 64.dp
