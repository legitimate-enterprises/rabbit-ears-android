package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import enterprises.legitimate.rabbitears.kit.Selection
import enterprises.legitimate.rabbitears.kit.TitleKind
import enterprises.legitimate.rabbitears.kit.resumeProgress

/** A movie, show, season, episode or video that a tile or row stands for. */
sealed interface Held {
    val id: String

    /** [local] is an action only this row has, which follows the shared ones. */
    class Title(
        val item: Item,
        val local: (@Composable (close: () -> Unit) -> Unit)? = null,
    ) : Held {
        override val id
            get() = item.id
    }

    /** [changed] runs after a write to the video's watched state, for a list that shows it. */
    class Video(val video: YouTubeVideo, val changed: () -> Unit = {}) : Held {
        override val id
            get() = video.id
    }
}

/** The watched fraction its tile or row shows, as its menu's Play would resume. */
@Composable
fun Held.progress(): Double? =
    when (this) {
        is Held.Title ->
            LocalNav.current.catalog.catalog.let { it.resumeProgress(it.item(id) ?: item) }
        is Held.Video -> video.resumeProgress()
    }

/**
 * A tile or row's long press opens the menu of what [held] offers, anchored to it: Select where the
 * screen has a [selection], Play, then the actions of the title's page. [content] takes the gesture
 * to put on its root; [open] is its tap.
 */
@Composable
fun Hold(
    held: Held,
    selection: Selection?,
    open: () -> Unit,
    modifier: Modifier = Modifier,
    details: (() -> Unit)? = null,
    openLabel: String? = null,
    content: @Composable (gesture: Modifier) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    val close = { shown = false }
    Box(modifier) {
        content(Modifier.pick(selection, held.id, { shown = true }, openLabel, open))
        when (held) {
            is Held.Title -> {
                val item = held.item
                ActionMenu(
                    shown,
                    close,
                    TitleKind.of(item.kind).playlistKind!!,
                    item.id,
                    item,
                    extras = { dismiss ->
                        SelectEntry(selection, held.id, dismiss)
                        PlayEntry(item, dismiss)
                        details?.let {
                            MenuAction("Go to details") {
                                dismiss()
                                it()
                            }
                        }
                    },
                    after = held.local,
                )
            }
            is Held.Video ->
                VideoMenu(shown, close, held.video, held.changed) { dismiss ->
                    SelectEntry(selection, held.id, dismiss)
                    PlayEntry(held.video, dismiss)
                }
        }
    }
}

/** What a long press did before the menu: begin selecting with this one picked. */
@Composable
private fun SelectEntry(selection: Selection?, id: String, close: () -> Unit) {
    if (selection == null) return
    MenuAction("Select") {
        close()
        selection.begin(id)
    }
}

@Composable
private fun PlayEntry(item: Item, close: () -> Unit) {
    val nav = LocalNav.current
    val start = nav.catalog.catalog.let { it.start(it.item(item.id) ?: item) } ?: return
    MenuAction(if (start.positionMs > 0) "Resume" else "Play") {
        close()
        nav.play(start.itemId, start.positionMs, editionId = start.editionId)
    }
}

@Composable
private fun PlayEntry(video: YouTubeVideo, close: () -> Unit) {
    val nav = LocalNav.current
    MenuAction(if (video.positionMs > 0) "Resume" else "Play") {
        close()
        nav.play(video.id, video.positionMs, title = video.title)
    }
}
