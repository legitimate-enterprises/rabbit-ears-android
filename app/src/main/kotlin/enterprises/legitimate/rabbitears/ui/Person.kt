package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.Person
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.placeholderKind
import enterprises.legitimate.rabbitears.ui.theme.Palette

/** A person and every movie and show crediting them that the profile may see. */
@Composable
fun PersonScreen(id: Long) {
    val nav = LocalNav.current
    val c = nav.catalog.catalog
    var loaded by remember(id) { mutableStateOf(false) }
    val found by
        produceState<Pair<Person, List<Item>>?>(null, id) {
            value = nav.catalog.person(id)
            loaded = true
        }
    val wide = LocalWidth.current != WidthClass.Compact
    LazyVerticalGrid(
        GridCells.Adaptive(if (wide) 136.dp else 104.dp),
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { BackBar("Back") }
        val (person, titles) =
            found
                ?: return@LazyVerticalGrid run {
                    if (loaded)
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("Not available.", color = Palette.dim)
                        }
                }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Art(
                    ArtworkStore.person(person.id),
                    avatarColor(person.id),
                    Modifier.size(112.dp).clip(CircleShape),
                )
                Text(person.name, style = MaterialTheme.typography.headlineMedium)
                Text(Format.plural(titles.size, "title"), color = Palette.dim)
            }
        }
        items(titles, key = { it.id }) {
            val item = c.item(it.id) ?: it
            PosterTile(
                item,
                c.caption(item),
                { nav.open(Route.Details(item.id)) },
                kind = placeholderKind(item.kind.value),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(16.dp)) }
    }
}
