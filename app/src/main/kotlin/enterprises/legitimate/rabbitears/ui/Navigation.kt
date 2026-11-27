package enterprises.legitimate.rabbitears.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.CatalogModel
import enterprises.legitimate.rabbitears.kit.CollectionsModel
import enterprises.legitimate.rabbitears.kit.Downloads
import enterprises.legitimate.rabbitears.kit.MusicModel
import enterprises.legitimate.rabbitears.kit.MusicPlayer
import enterprises.legitimate.rabbitears.kit.OccasionsModel
import enterprises.legitimate.rabbitears.kit.PlaylistRun
import enterprises.legitimate.rabbitears.kit.PlaylistsModel
import enterprises.legitimate.rabbitears.kit.StationsModel
import enterprises.legitimate.rabbitears.kit.WatchTogetherModel
import enterprises.legitimate.rabbitears.kit.YouTubeModel

/** A screen pushed over the current tab. */
sealed interface Route {
    data class Details(val id: String) : Route

    data object Search : Route

    /**
     * [audioTrackId] is the details screen's pick. [title] names a video, which the catalog does
     * not hold. [run] is the playlist being played, which goes on to its next row and names each
     * row's edition. [editionId] is the edition picked in details; none plays the default.
     */
    data class Player(
        val itemId: String,
        val startMs: Long = 0,
        val audioTrackId: String? = null,
        val title: String? = null,
        val run: PlaylistRun? = null,
        val editionId: String? = null,
    ) : Route

    data class Person(val id: String) : Route

    data class Artist(val id: String) : Route

    data class Album(val id: String) : Route

    data class Playlist(val id: String) : Route

    data class Collection(val id: String) : Route

    /** An occasion in season; carries it whole, as its screen shows its name and colours. */
    data class Occasion(val occasion: enterprises.legitimate.rabbitears.api.models.ActiveOccasion) :
        Route

    /** Now playing over everything, with Up next open when [queue]. */
    data class NowPlaying(val queue: Boolean = false) : Route

    data class Channel(val id: String) : Route

    data class Station(val id: String) : Route

    data class StationEditor(val stationId: String? = null) : Route

    data object Preferences : Route

    data object Household : Route

    /** Tuned in to a movies or TV station; a music station plays in the music player. */
    data class Tune(val station: enterprises.legitimate.rabbitears.api.models.Station) : Route

    data class Video(val id: String) : Route
}

/**
 * The browsing state of one profile: its catalog, music, YouTube, playlists, collections and
 * stations, and the screens pushed over the tabs. [music] is the process's player.
 */
class Navigator(
    val app: AppModel,
    val catalog: CatalogModel,
    val music: MusicPlayer? = null,
    val downloads: Downloads? = null,
) {
    private val offline = catalog.offline
    val stack = mutableStateListOf<Route>()
    val musicLibrary by lazy { MusicModel(catalog.api, offline) }
    val youtube by lazy { YouTubeModel(catalog.api, catalog.profileId, offline) }
    val playlists by lazy {
        PlaylistsModel(catalog.api, catalog.profileId, adult = !app.isKid, offline)
    }
    val collections by lazy { CollectionsModel(catalog.api, offline) }
    val stations by lazy { StationsModel(catalog.api) }
    val occasions by lazy { OccasionsModel(catalog.api, catalog.profileId) }
    private val togethers = mutableMapOf<String, WatchTogetherModel>()

    /** Watch together on a title, kept from details into the player. */
    fun together(itemId: String): WatchTogetherModel? =
        togethers.getOrPut(itemId) { WatchTogetherModel(catalog.api, catalog.profileId, itemId) }

    fun open(route: Route) {
        stack += route
    }

    /** Shows [route] in place of the top screen, so Back goes where that screen's would. */
    fun replace(route: Route) {
        stack.removeLastOrNull()
        stack += route
    }

    fun back() {
        stack.removeLastOrNull()
    }

    fun home() = stack.clear()

    fun play(
        itemId: String,
        startMs: Long = 0,
        audioTrackId: String? = null,
        title: String? = null,
        run: PlaylistRun? = null,
        editionId: String? = null,
    ) = open(Route.Player(itemId, startMs, audioTrackId, title, run, editionId))

    suspend fun switchProfile() = app.switchProfile()
}

val LocalNav = compositionLocalOf<Navigator> { error("no navigator") }

val LocalWidth = compositionLocalOf { WidthClass.Compact }
