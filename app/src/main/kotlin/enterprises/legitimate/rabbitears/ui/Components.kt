package enterprises.legitimate.rabbitears.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.ProfileColors
import enterprises.legitimate.rabbitears.kit.Selection
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The boards' pill buttons: goldenrod for the main action, tonal for the rest. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick,
        modifier.height(40.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            if (primary) ButtonDefaults.buttonColors(Palette.accent, Palette.onAccent)
            else ButtonDefaults.buttonColors(Palette.tonal, Palette.text),
        contentPadding = PaddingValues(start = if (icon != null) 16.dp else 24.dp, end = 24.dp),
    ) {
        icon?.let {
            Icon(painterResource(it), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun IconAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, tonal: Boolean = false) {
    IconButton(
        onClick,
        colors =
            if (tonal)
                IconButtonDefaults.iconButtonColors(
                    containerColor = Palette.tonal,
                    contentColor = Palette.text,
                )
            else IconButtonDefaults.iconButtonColors(contentColor = Palette.text),
    ) {
        Icon(painterResource(icon), label)
    }
}

/** `4K`, `HDR`: small caps beside a title. */
@Composable
fun Tag(text: String) {
    Text(
        text,
        Modifier.background(Palette.tag, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** The watched fraction along the bottom of a tile; [label] is what a screen reader says of it. */
@Composable
fun BoxScope.TileBar(
    fraction: Double,
    label: String? = "${(fraction.coerceIn(0.0, 1.0) * 100).roundToInt()} percent watched",
) {
    Box(
        Modifier.align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.45f))
            .then(
                if (label == null) Modifier else Modifier.semantics { contentDescription = label },
            ),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Palette.accent),
        )
    }
}

/** A play arrow in a translucent circle, centered over a tile's art: the tile plays when tapped. */
@Composable
fun BoxScope.PlayMark() {
    Icon(
        painterResource(R.drawable.ic_play),
        null,
        Modifier.align(Alignment.Center)
            .size(40.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .padding(10.dp),
        tint = Color.White,
    )
}

/** The goldenrod badge, such as `6 new`, in a tile's top corner. */
@Composable
fun BoxScope.Badge(text: String) {
    Text(
        text,
        Modifier.align(Alignment.TopEnd)
            .padding(6.dp)
            .background(Palette.accent, RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        color = Palette.onAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun BoxScope.UnwatchedDot() {
    Box(
        Modifier.align(Alignment.TopEnd)
            .padding(8.dp)
            .size(14.dp)
            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            .padding(2.dp)
            .background(Palette.accent, CircleShape)
            .semantics { contentDescription = "Unwatched" },
    )
}

/**
 * A profile's initial on the color the server gave it, or its picture when the server has one. The
 * picture comes from the server, never from a third party.
 */
@Composable
fun Avatar(profile: Account, size: Dp, locked: Boolean = false) {
    val store = LocalArtwork.current
    val picture by
        produceState<ImageBitmap?>(null, profile.avatarUrl, store) {
            value = profile.avatarUrl?.let { store?.load(it) }
        }
    Box(Modifier.size(size)) {
        Box(
            Modifier.matchParent()
                .background(Color(ProfileColors.argb(profile.color)), CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            picture?.let {
                Image(it, null, Modifier.matchParent(), contentScale = ContentScale.Crop)
            }
                ?: Text(
                    profile.name.take(1).uppercase(),
                    color = Color.White,
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.Bold,
                )
        }
        if (locked)
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .size(28.dp)
                    .background(Palette.ground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_lock),
                    "PIN required",
                    Modifier.size(14.dp),
                    tint = Palette.accent,
                )
            }
    }
}

private fun Modifier.matchParent() = fillMaxWidth().aspectRatio(1f)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium)
}

/** The way from a strip to the whole list it shows part of; [name] is what it lists. */
class SeeAll(val name: String, val open: () -> Unit)

/** A titled horizontal strip of tiles, bleeding off the trailing edge as the boards draw it. */
@Composable
fun <T> Strip(
    title: String,
    items: List<T>,
    seeAll: SeeAll? = null,
    tile: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (seeAll == null) SectionTitle(title)
        else
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(title, Modifier.weight(1f))
                TextButton(
                    seeAll.open,
                    Modifier.semantics { contentDescription = "See all ${seeAll.name}" },
                ) {
                    Text("See all")
                }
            }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(items) { tile(it) } }
    }
}

@Composable
fun TileText(title: String, caption: String?) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!caption.isNullOrEmpty())
            Text(
                caption,
                fontSize = 12.sp,
                color = Palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
    }
}

/** A 2:3 poster with its title and caption under it; a long press opens [item]'s menu. */
@Composable
fun PosterTile(
    item: Item,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: Selection? = null,
    kind: PlaceholderKind? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val held = Held.Title(item)
    val progress = held.progress()
    Hold(held, selection, onClick, modifier) { gesture ->
        Column(gesture) {
            Art(
                ArtworkStore.item(item.id),
                artColor(item.id),
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small),
                kind,
            ) {
                overlay()
                progress?.let { TileBar(it) }
                StatusBadges(isDownloaded(item.id), overBar = progress != null)
            }
            TileText(Catalog.title(item), caption)
        }
    }
}

/**
 * A 16:9 tile: a movie's or show's backdrop, an episode's still, a video's thumbnail. A long press
 * opens [held]'s menu. A tile with [plays], the words a screen reader says of its tap, shows a play
 * arrow over the art and plays on [onClick]; [details] is then in the menu.
 */
@Composable
fun WideTile(
    held: Held,
    art: String,
    title: String,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Double? = held.progress(),
    kind: PlaceholderKind? = null,
    plays: String? = null,
    details: (() -> Unit)? = null,
) {
    Hold(held, null, onClick, modifier, details, plays) { gesture ->
        Column(gesture) {
            Art(
                art,
                artColor(held.id),
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small),
                kind,
            ) {
                if (plays != null) PlayMark()
                progress?.let { TileBar(it) }
                StatusBadges(isDownloaded(held.id), overBar = progress != null)
            }
            TileText(title, caption)
        }
    }
}

/**
 * The page header: title and count, [trailing] at any width, then on a phone the page's own
 * actions, Search and the profile; with a rail, a search pill stands in and the profile sits on the
 * rail.
 */
@Composable
fun Header(
    title: String,
    count: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalNav.current
    val compact = LocalWidth.current == WidthClass.Compact
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            count?.let { Text(it, color = Palette.dim, modifier = Modifier.padding(bottom = 6.dp)) }
        }
        trailing()
        if (compact) {
            actions()
            IconAction(R.drawable.ic_search, "Search", { nav.open(Route.Search) })
            ProfileButton()
        } else SearchPill()
    }
}

/**
 * The profile's avatar, and its name when [named], opening the profile menu: switch profile, an
 * adult's preferences, and sign out.
 */
@Composable
fun ProfileButton(named: Boolean = false) {
    val nav = LocalNav.current
    val profile = nav.app.activeProfile ?: return
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.clickable { open = true }
                .padding(6.dp)
                .semantics { contentDescription = "Profile: ${profile.name}" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(profile, 32.dp)
            if (named)
                Text(
                    profile.name,
                    Modifier.widthIn(max = 64.dp),
                    fontSize = 11.sp,
                    color = Palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
        DropdownMenu(open, { open = false }) {
            MenuAction("Switch profile") {
                open = false
                scope.launch { nav.switchProfile() }
            }
            if (!nav.app.isKid)
                MenuAction("Preferences") {
                    open = false
                    nav.open(Route.Preferences)
                }
            MenuAction("Sign out") {
                open = false
                scope.launch { nav.app.signOut() }
            }
        }
    }
}

/** The tablet header's search field; it opens Search, where typing happens. */
@Composable
fun SearchPill() {
    val nav = LocalNav.current
    Row(
        Modifier.widthIn(max = 320.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.raised)
            .clickable { nav.open(Route.Search) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = Palette.secondary)
        Text("Search", color = Palette.dim, fontSize = 16.sp)
    }
}

/**
 * The details header: Back, then Search on a phone or the search pill with a rail, then the page's
 * own [actions], such as its menu.
 */
@Composable
fun BackBar(label: String, actions: @Composable RowScope.() -> Unit = {}) {
    val nav = LocalNav.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(R.drawable.ic_back, label, { nav.back() })
        Spacer(Modifier.weight(1f))
        if (LocalWidth.current == WidthClass.Compact)
            IconAction(R.drawable.ic_search, "Search", { nav.open(Route.Search) })
        else SearchPill()
        actions()
    }
}

/** A menu item that shows a check while [checked]. */
@Composable
fun Checked(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        { Text(label) },
        onClick,
        trailingIcon =
            if (checked)
                ({ Icon(painterResource(R.drawable.ic_check), null, tint = Palette.accent) })
            else null,
    )
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected,
        onClick,
        { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon =
            if (selected)
                ({ Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)) })
            else null,
        shape = MaterialTheme.shapes.small,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                labelColor = Palette.text,
                selectedContainerColor = Palette.accent,
                selectedLabelColor = Palette.onAccent,
                selectedLeadingIconColor = Palette.onAccent,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                true,
                selected,
                borderColor = Palette.outline,
                selectedBorderColor = Palette.accent,
            ),
    )
}

/**
 * The views a library screen switches between, under its header and bleeding past the content
 * padding to the window's edges. One view draws nothing.
 */
@Composable
fun <T> ViewTabs(views: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    if (views.size < 2) return
    val phone = LocalWidth.current.bottomBar
    // The content padding Shell gives the destination.
    val bleed = if (phone) 16.dp else 24.dp
    Column(
        Modifier.layout { measurable, constraints ->
            val extra = bleed.roundToPx()
            val placeable =
                measurable.measure(
                    constraints.copy(
                        minWidth = constraints.maxWidth + 2 * extra,
                        maxWidth = constraints.maxWidth + 2 * extra,
                    ),
                )
            layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
        },
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState())
                .padding(start = if (phone) 4.dp else 12.dp)
                .selectableGroup(),
        ) {
            views.forEach { view ->
                val current = view == selected
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Box(
                    Modifier.height(48.dp)
                        .widthIn(min = 48.dp)
                        .then(if (focused) Modifier.border(2.dp, Palette.accent) else Modifier)
                        .selectable(current, interaction, ripple(), role = Role.Tab) {
                            onSelect(view)
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(view),
                        color = if (current) Palette.text else Palette.secondary,
                        fontWeight = if (current) FontWeight.Bold else null,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (current)
                        Box(
                            Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(
                                    Palette.accent,
                                    RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                                ),
                        )
                }
            }
        }
        HorizontalDivider(color = Palette.tonal)
    }
}

/** A key and value row in a details list, such as Director. */
@Composable
fun KeyValue(key: String, keyWidth: Dp, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, Modifier.width(keyWidth), color = Palette.dim)
        Box(Modifier.weight(1f)) { value() }
    }
}

/** A dialog asking for one name; [onSubmit] answers whether it was taken, [error] why not. */
@Composable
fun NameDialog(
    title: String,
    confirm: String,
    initial: String,
    error: String?,
    onSubmit: suspend (String) -> Boolean,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDone,
        confirmButton = {
            TextButton(
                { scope.launch { if (onSubmit(name)) onDone() } },
                enabled = name.isNotBlank(),
            ) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
        title = { Text(title) },
        containerColor = Palette.raised,
        text = {
            Column {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                )
                error?.let { Text(it, color = Palette.accent) }
            }
        },
    )
}
