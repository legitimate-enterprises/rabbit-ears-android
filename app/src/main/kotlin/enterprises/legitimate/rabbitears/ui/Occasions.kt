package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.api.models.ActiveOccasion
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.OccasionWash
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlin.math.max

/**
 * One occasion: every title the profile may see in it, under the occasion's name and over a soft
 * wash of its colours.
 */
@Composable
fun OccasionScreen(occasion: ActiveOccasion) {
    val nav = LocalNav.current
    val loaded = rememberLoaded(occasion.id) { nav.occasions.members(occasion.id) }
    Box(Modifier.fillMaxSize().occasionWash(occasion.colors)) {
        Refreshable({ loaded.reload() }) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { BackBar("Back") }
                item { Text(occasion.name, style = MaterialTheme.typography.headlineMedium) }
                if (loaded.value?.isEmpty() == true)
                    item { Text("Nothing in this occasion yet.", color = Palette.dim) }
                items(loaded.value.orEmpty(), key = { it.id }) { title ->
                    MediaRow(
                        Catalog.title(title),
                        nav.catalog.catalog.caption(title),
                        { nav.open(Route.Details(title.id)) },
                        { AlbumArt(title.id, 48.dp, 6.dp) },
                        downloaded = isDownloaded(title.id),
                        held = Held.Title(title, null),
                    )
                }
                item { Box(Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * Three very large soft spots of the occasion's colours at the top corners and top edge, a little
 * stronger behind the title, fading out below the first rows. Static; without three valid colours
 * the background stays plain.
 */
private fun Modifier.occasionWash(colors: List<String>?): Modifier {
    val palette = OccasionWash.palette(colors) ?: return this
    return drawBehind {
        val radius = max(size.width * 0.8f, size.height * 0.5f)
        val spots =
            listOf(
                Offset(0f, 0f) to 0.24f,
                Offset(size.width / 2, 0f) to 0.30f,
                Offset(size.width, 0f) to 0.24f,
            )
        palette.zip(spots).forEach { (argb, spot) ->
            val (center, strength) = spot
            val color = Color(argb)
            drawRect(
                Brush.radialGradient(
                    listOf(
                        color.copy(alpha = strength),
                        color.copy(alpha = strength * 0.4f),
                        Color.Transparent,
                    ),
                    center,
                    radius,
                ),
            )
        }
    }
}
