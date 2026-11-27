package enterprises.legitimate.rabbitears.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.kit.SelectKind
import enterprises.legitimate.rabbitears.kit.Selection
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * A tap opens, or picks while [selection] is active. A long press runs [more], which opens a menu
 * that has Select in it, unless a selection is active; without [more] it begins selecting.
 */
fun Modifier.pick(
    selection: Selection?,
    id: Long,
    more: (() -> Unit)? = null,
    openLabel: String? = null,
    open: () -> Unit,
): Modifier {
    if (selection == null && more == null)
        return clickable(onClickLabel = openLabel, onClick = open)
    val active = selection?.active == true
    val picked = selection != null && selection.active && id in selection.picked
    val menu = more.takeIf { !active }
    return this.then(if (active) Modifier.alpha(if (picked) 1f else 0.6f) else Modifier)
        .then(
            if (picked) Modifier.border(3.dp, Palette.accent, RoundedCornerShape(8.dp))
            else Modifier,
        )
        .semantics { if (active) selected = picked }
        .combinedClickable(
            onClickLabel = openLabel,
            // The long-click label is what TalkBack lists as the tile's action.
            onLongClickLabel = if (menu != null) "More actions" else "Select",
            onLongClick = { if (menu != null) menu() else selection?.begin(id) },
            onClick = { if (active) selection?.toggle(id) else open() },
        )
}

@Composable
fun SelectToggle(selection: Selection, kind: SelectKind?) {
    val adult = !LocalNav.current.app.isKid
    if (kind == null || !kind.watches && !adult) return
    TextButton({ if (selection.active) selection.end() else selection.begin() }) {
        Text(if (selection.active) "Done" else "Select", color = Palette.text)
    }
}

/**
 * [shown] is every id the screen lists under its filter, genre and sort, which Select all picks.
 * [changed] runs after a YouTube write, whose lists the screen reads again.
 */
@Composable
fun SelectionBar(
    selection: Selection,
    kind: SelectKind?,
    shown: List<Long>,
    modifier: Modifier,
    changed: () -> Unit,
) {
    BackHandler(selection.active) { selection.end() }
    if (!selection.active || kind == null) return
    val nav = LocalNav.current
    val adult = !nav.app.isKid
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<AddTarget?>(null) }
    val ids = selection.picked
    val run = { action: suspend () -> Unit ->
        open = false
        scope.launch {
            action()
            selection.end()
            if (kind.youtube) changed()
        }
    }
    val items = { ids.mapNotNull { nav.catalog.catalog.item(it) } }
    val watched = { on: Boolean ->
        run {
            if (kind.youtube) nav.youtube.setWatched(ids, on)
            else nav.catalog.setWatched(on, items())
        }
    }
    val kids = { on: Boolean ->
        run {
            if (kind.youtube) nav.youtube.setForKids(ids, on)
            else nav.catalog.setKidsContent(items(), on)
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Palette.raised, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ids.size} selected", Modifier.weight(1f), fontWeight = FontWeight.Medium)
        val everything = selection.allPicked(shown)
        TextButton(
            { if (everything) selection.deselectAll() else selection.selectAll(shown) },
            enabled = shown.isNotEmpty(),
        ) {
            Text(if (everything) "Deselect all" else "Select all", color = Palette.text)
        }
        Box {
            TextButton({ open = true }, enabled = ids.isNotEmpty()) {
                Text("Actions", color = Palette.text)
            }
            DropdownMenu(open, { open = false }) {
                if (kind.watches) {
                    MenuAction("Mark watched") { watched(true) }
                    MenuAction("Mark unwatched") { watched(false) }
                }
                if (adult && kind.kidsContent) {
                    MenuAction("Kids' content") { kids(true) }
                    MenuAction("Not kids' content") { kids(false) }
                }
                if (adult && kind.playlistKind != null) {
                    MenuAction("Add to playlist") {
                        open = false
                        adding = AddTarget.Playlist
                    }
                    MenuAction("Add to station") {
                        open = false
                        adding = AddTarget.Station
                    }
                }
            }
        }
        TextButton({ selection.end() }) {
            Text("Done", color = Palette.accent, fontWeight = FontWeight.SemiBold)
        }
    }
    val list = kind.playlistKind
    adding?.let { target ->
        if (list != null) AddTo(target, list, ids.first(), itemIds = ids) { adding = null }
    }
}
