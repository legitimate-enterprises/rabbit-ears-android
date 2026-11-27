package enterprises.legitimate.rabbitears.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.RabbitEarsApp
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.AppModel.Phase
import enterprises.legitimate.rabbitears.kit.CatalogModel
import enterprises.legitimate.rabbitears.kit.DownloadMeta
import enterprises.legitimate.rabbitears.kit.LibrarySection
import enterprises.legitimate.rabbitears.kit.OfflineLibrary
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RECONNECT_MS = 30_000L

enum class Tab(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    /** The library kind the server must have for this tab to show; Home always shows. */
    val section: LibrarySection? = null,
) {
    Home(R.string.tab_home, R.drawable.ic_home),
    TV(R.string.tab_tv, R.drawable.ic_tv, LibrarySection.tv),
    Movies(R.string.tab_movies, R.drawable.ic_movies, LibrarySection.movies),
    Music(R.string.tab_music, R.drawable.ic_music, LibrarySection.music),
    YouTube(R.string.tab_youtube, R.drawable.ic_youtube, LibrarySection.youtube),
}

@Composable
fun Root(app: AppModel) {
    val scope = rememberCoroutineScope()
    val process = LocalContext.current.applicationContext as RabbitEarsApp
    // The session outlives the activity; only a cold start restores it.
    LaunchedEffect(app) { if (app.phase == Phase.Starting) app.restore() }
    // Music belongs to the profile browsing; choosing another or signing out ends it.
    LaunchedEffect(app.phase) { if (app.phase != Phase.Browsing) process.stopMusic() }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints {
            CompositionLocalProvider(LocalWidth provides WidthClass.of(maxWidth.value.toInt())) {
                when (app.phase) {
                    Phase.Starting -> Unit
                    Phase.Unreachable ->
                        Unreachable(
                            { scope.launch { app.restore() } },
                            { scope.launch { app.signOut() } },
                        )
                    Phase.SignedOut -> SignIn(app)
                    Phase.ChoosingHousehold -> WhichHousehold(app)
                    Phase.ChoosingProfile -> Who(app)
                    Phase.Browsing -> Browse(app)
                }
            }
        }
    }
}

@Composable
private fun Unreachable(retry: () -> Unit, signOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't reach your server", style = MaterialTheme.typography.headlineMedium)
        Text("Check that it is running and that this phone is online.", color = Palette.secondary)
        Pill("Try again", retry, primary = true)
        Pill("Sign out", signOut)
    }
}

/** One profile's catalog, artwork and screen stack, rebuilt when the profile changes. */
@Composable
private fun Browse(app: AppModel) {
    val api = app.api ?: return
    val profile = app.activeProfile ?: return
    val process = LocalContext.current.applicationContext as RabbitEarsApp
    val downloads = process.downloads
    val nav =
        remember(api, profile.id) {
            val offline = OfflineLibrary(downloads.store, profile.id) { app.offline }
            val catalog = CatalogModel(api, profile.id, offline, profile.hideKidsContent == true)
            Navigator(app, catalog, process.music(api, profile.id), downloads)
        }
    val artwork = remember(api) { ArtworkStore(api, DownloadMeta(downloads.store)) { app.offline } }
    val scope = rememberCoroutineScope()
    // Back online, the catalog loads again and what the profile downloaded is brought up to date.
    LaunchedEffect(nav, app.offline) {
        nav.catalog.load()
        if (!app.offline) downloads.sync(api, profile.id)
    }
    // Browsing downloads, the server is asked again now and then.
    LaunchedEffect(app.offline) {
        while (app.offline) {
            delay(RECONNECT_MS)
            app.reconnect()
        }
    }
    // In view, the server's live events keep what is shown current; out of view nothing is asked.
    val live = app.live
    LifecycleStartEffect(nav) {
        val following = scope.launch { if (live.follow(api)) app.signOut() }
        onStopOrDispose { following.cancel() }
    }
    LaunchedEffect(nav) { live.events.collect { nav.catalog.heard(it) } }
    // Hide kids' content belongs to the profile, so a change made on another device reaches the
    // profile read again on returning to the foreground and on a household change, and the catalog
    // follows it.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!app.offline) scope.launch { app.reload() }
    }
    LaunchedEffect(nav, live.household) { if (live.household > 0 && !app.offline) app.reload() }
    LaunchedEffect(nav, profile.hideKidsContent) {
        nav.catalog.hideKids(profile.hideKidsContent == true)
    }
    // The tabs follow the libraries the server has, read again on sign-in, on a library change and
    // on returning to the foreground.
    LaunchedEffect(nav, app.offline) { app.loadLibrarySections() }
    LaunchedEffect(nav) { live.events.collect { app.librariesChanged(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { scope.launch { app.loadLibrarySections() } }
    LaunchedEffect(nav, live.stations) { if (live.stations > 0) nav.stations.load() }
    LaunchedEffect(nav, live.playlists) { if (live.playlists > 0) nav.playlists.load() }
    // Back in the foreground, following again resumes where the stream stood, and a music
    // station's wait the device slept through is armed again. The video station rejoins on its own
    // screen's start.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { nav.music?.station?.resume() }
    CompositionLocalProvider(LocalNav provides nav, LocalArtwork provides artwork) {
        Box {
            Shell(nav)
            Notice(live)
        }
    }
}

@Composable
fun Shell(nav: Navigator) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val shown = nav.app.librarySections
    val tabs = Tab.entries.filter { it.section == null || it.section in shown }
    // A tab whose library the server lacks goes away, and its screens with it.
    val staying = LibrarySection.stay(tab.section, shown)
    LaunchedEffect(shown) {
        if (tab.section != staying) {
            tab = Tab.Home
            nav.home()
        }
    }
    val select = { t: Tab ->
        tab = t
        nav.home()
    }
    BackHandler(nav.stack.isNotEmpty()) { nav.back() }
    val route = nav.stack.lastOrNull()
    val music = nav.music
    music?.alert?.let {
        AlertDialog(
            { music.alert = null },
            { TextButton({ music.alert = null }) { Text("OK") } },
            text = { Text(it) },
            containerColor = Palette.raised,
        )
    }
    // The player and Now playing take the whole window, over the bar or rail.
    if (route is Route.Player) return Player(route)
    if (route is Route.NowPlaying) return NowPlaying(route)
    if (route is Route.Tune) return StationPlayer(route)
    // The expanded Music tab shows the now-playing pane in place of the bar, but not for a station.
    val pane =
        tab == Tab.Music &&
            route == null &&
            LocalWidth.current == WidthClass.Expanded &&
            music?.station == null
    val bar = music?.showing == true && !pane
    if (LocalWidth.current.bottomBar) {
        Column {
            Destination(tab, route, Modifier.weight(1f).padding(horizontal = 16.dp), true)
            if (bar) music?.let { MiniPlayer(it) }
            BottomBar(tabs, tab, select)
        }
    } else {
        Row {
            Rail(tabs, tab, select)
            VerticalDivider(color = Palette.divider)
            Column(Modifier.weight(1f)) {
                Destination(tab, route, Modifier.weight(1f).padding(horizontal = 24.dp), !bar)
                if (bar) music?.let { NowPlayingBar(it) }
            }
        }
    }
}

@Composable
private fun BottomBar(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Palette.raised) {
        val colors =
            NavigationBarItemDefaults.colors(
                selectedIconColor = Palette.accent,
                selectedTextColor = Palette.text,
                indicatorColor = Palette.indicator,
                unselectedIconColor = Palette.secondary,
                unselectedTextColor = Palette.secondary,
            )
        tabs.forEach {
            NavigationBarItem(
                selected = it == selected,
                onClick = { onSelect(it) },
                icon = { Icon(painterResource(it.icon), contentDescription = null) },
                label = { TabLabel(it, it == selected) },
                colors = colors,
            )
        }
    }
}

@Composable
private fun Rail(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationRail(
        containerColor = Palette.ground,
        modifier = Modifier.width(80.dp).fillMaxHeight(),
        header = {
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_logo),
                    contentDescription = stringResource(R.string.app_name),
                    tint = Palette.accent,
                )
            }
        },
    ) {
        val colors =
            NavigationRailItemDefaults.colors(
                selectedIconColor = Palette.accent,
                selectedTextColor = Palette.text,
                indicatorColor = Palette.indicator,
                unselectedIconColor = Palette.secondary,
                unselectedTextColor = Palette.secondary,
            )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tabs.forEach {
                NavigationRailItem(
                    selected = it == selected,
                    onClick = { onSelect(it) },
                    icon = { Icon(painterResource(it.icon), contentDescription = null) },
                    label = { TabLabel(it, it == selected) },
                    colors = colors,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(bottom = 16.dp)) { ProfileButton(named = true) }
    }
}

@Composable
private fun TabLabel(tab: Tab, selected: Boolean) {
    Text(
        stringResource(tab.label),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
    )
}

@Composable
private fun Destination(tab: Tab, route: Route?, modifier: Modifier, bottom: Boolean) {
    // The bottom bar, the rail or the now-playing bar already pads for the system bars on its
    // side.
    val sides =
        if (LocalWidth.current.bottomBar) WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        else if (bottom) WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom
        else WindowInsetsSides.Top + WindowInsetsSides.End
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(sides))) {
        when (route) {
            is Route.Details -> Details(route.id)
            Route.Search -> Search()
            is Route.Person -> PersonScreen(route.id)
            is Route.Artist -> ArtistScreen(route.id)
            is Route.Album -> AlbumScreen(route.id)
            is Route.Playlist -> PlaylistScreen(route.id)
            is Route.Collection -> CollectionScreen(route.id)
            is Route.Occasion -> OccasionScreen(route.occasion)
            is Route.Channel -> ChannelScreen(route.id)
            is Route.Video -> VideoScreen(route.id)
            is Route.Station -> StationScreen(route.id)
            is Route.StationEditor -> StationEditor(route.stationId)
            Route.Preferences -> Preferences()
            Route.Household -> ManageHousehold()
            else ->
                when (tab) {
                    Tab.Home -> Home()
                    Tab.TV -> Grid(ItemKind.show)
                    Tab.Movies -> Grid(ItemKind.movie)
                    Tab.Music -> MusicTab()
                    Tab.YouTube -> YouTubeTab()
                }
        }
    }
}
