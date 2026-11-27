package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.KidAllowList
import enterprises.legitimate.rabbitears.kit.TitleAction
import enterprises.legitimate.rabbitears.kit.TitleKind
import enterprises.legitimate.rabbitears.kit.titleActions
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * The ⋮ menu of a page an adult can file: for a movie, show, season or episode [item], what
 * [titleActions] lists, then Add to playlist and Add to station, and for a movie Add to collection,
 * after which [onCollected] runs. A page's own entries come first as [extras]. A kid has no menu
 * unless the page has [extras].
 */
@Composable
fun MoreMenu(
    kind: PlaylistKind,
    id: String,
    item: Item? = null,
    collection: Boolean = false,
    editionId: String? = null,
    onCollected: () -> Unit = {},
    /** What Download names it in Preferences; a title [item] names itself. */
    name: String? = null,
    extras: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    val nav = LocalNav.current
    if (nav.app.isKid && extras == null && nav.downloads == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_more, "More", { open = true })
        ActionMenu(
            open,
            { open = false },
            kind,
            id,
            item,
            collection,
            editionId,
            onCollected,
            name,
            extras,
        )
    }
}

/**
 * The menu [MoreMenu] and a long press on a title share, with [extras] before and [after] what the
 * profile may do to it. What its entries open is hosted here, outside the dropdown, so it outlives
 * the menu closing. Nothing is read until the menu opens.
 */
@Composable
fun ActionMenu(
    open: Boolean,
    close: () -> Unit,
    kind: PlaylistKind,
    id: String,
    item: Item? = null,
    collection: Boolean = false,
    editionId: String? = null,
    onCollected: () -> Unit = {},
    name: String? = null,
    extras: (@Composable (close: () -> Unit) -> Unit)? = null,
    after: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    val nav = LocalNav.current
    var adding by remember { mutableStateOf<AddTarget?>(null) }
    var collecting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<DownloadAsk?>(null) }
    DropdownMenu(open, close) {
        val actions = titleActions(item?.let { TitleKind.of(it.kind) }, !nav.app.isKid)
        val target =
            if (collection) DownloadTarget(collectionId = id)
            else DownloadTarget(itemId = id, editionId = editionId)
        val ask =
            DownloadAsk(
                target,
                name ?: item?.let(Catalog::title).orEmpty(),
                kind == PlaylistKind.music,
            )
        extras?.invoke(close)
        DownloadMenuItem(ask, close) { downloading = it }
        item?.let { ItemActions(it, actions) }
        if (actions.isNotEmpty()) {
            if (item != null) HorizontalDivider(color = Palette.divider)
            if (TitleAction.Playlist in actions)
                MenuAction("Add to playlist") {
                    close()
                    adding = AddTarget.Playlist
                }
            if (TitleAction.Station in actions)
                MenuAction("Add to station") {
                    close()
                    adding = AddTarget.Station
                }
            if (TitleAction.Collection in actions)
                MenuAction("Add to collection") {
                    close()
                    collecting = true
                }
        }
        after?.invoke(close)
    }
    adding?.let { AddTo(it, kind, id, collection, editionId) { adding = null } }
    if (collecting)
        AddToCollection(id) {
            collecting = false
            onCollected()
        }
    downloading?.let { DownloadDialog(it) { downloading = null } }
}

/**
 * A video's menu: Download, and an adult's Mark as watched. That closes the menu, since the video
 * shown is not read again until [changed] runs.
 */
@Composable
fun VideoMenu(
    open: Boolean,
    close: () -> Unit,
    video: YouTubeVideo,
    changed: () -> Unit,
    extras: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf<DownloadAsk?>(null) }
    DropdownMenu(open, close) {
        extras?.invoke(close)
        DownloadMenuItem(
            DownloadAsk(DownloadTarget(itemId = video.id), video.title, false),
            close,
        ) {
            downloading = it
        }
        if (TitleAction.Watched in titleActions(TitleKind.Video, !nav.app.isKid))
            MenuAction(if (video.watched) "Mark as unwatched" else "Mark as watched") {
                close()
                scope.launch { if (nav.youtube.setWatched(video, !video.watched)) changed() }
            }
    }
    downloading?.let { DownloadDialog(it) { downloading = null } }
}

/** The ⋮ of a video's page; [changed] runs after its watched state is written. */
@Composable
fun VideoMore(video: YouTubeVideo, changed: () -> Unit) {
    val nav = LocalNav.current
    if (nav.app.isKid && nav.downloads == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_more, "More", { open = true })
        VideoMenu(open, { open = false }, video, changed)
    }
}

/** A title's own settings, which stay open so several can be set at once. */
@Composable
private fun ItemActions(shown: Item, actions: List<TitleAction>) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val item = nav.catalog.catalog.item(shown.id) ?: shown
    if (TitleAction.Watched in actions) {
        val watched = nav.catalog.catalog.unwatchedCount(item.id) == 0
        MenuAction(if (watched) "Mark as unwatched" else "Mark as watched") {
            scope.launch { nav.catalog.setWatched(!watched, item) }
        }
    }
    if (TitleAction.KidsContent in actions) {
        Checked("Kids’ content", item.kidsContent == true) {
            scope.launch { nav.catalog.setKidsContent(item, item.kidsContent != true) }
        }
        if (item.kidsContentOverride != null)
            MenuAction("Use rating") { scope.launch { nav.catalog.useRating(item.id) } }
        AllowedForKids(item.id, nav.app.listKids)
    }
}

/**
 * One checkbox per list-managed kid, checked when that kid's allow list holds [id]; nothing when
 * the household has no such kid.
 */
@Composable
fun AllowedForKids(id: String, kids: List<Account>) {
    if (kids.isEmpty()) return
    val api = LocalNav.current.catalog.api
    val allow = remember(id) { KidAllowList(api, id) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(allow) { allow.load() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Allowed for kids on a list", color = Palette.dim, fontSize = 12.sp)
        kids.forEach { kid ->
            Row(
                Modifier.fillMaxWidth().clickable { scope.launch { allow.toggle(kid.id) } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    kid.id in allow.allowed,
                    null,
                    colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                )
                Text(kid.name)
            }
        }
        allow.error?.let { Text(it, color = Palette.accent, fontSize = 12.sp) }
    }
}
