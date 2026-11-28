package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.Favorite
import enterprises.legitimate.rabbitears.api.models.FavoriteKind
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.MusicAlbum
import enterprises.legitimate.rabbitears.api.models.MusicArtist
import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** What the Favorites screen shows, each kind most recently starred first. */
data class FavoriteShelves(
    val movies: List<Item> = emptyList(),
    val shows: List<Item> = emptyList(),
    val seasons: List<Item> = emptyList(),
    val episodes: List<Item> = emptyList(),
    val artists: List<MusicArtist> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val songs: List<MusicSong> = emptyList(),
    val videos: List<YouTubeVideo> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            listOf(movies, shows, seasons, episodes, artists, albums, songs, videos).all {
                it.isEmpty()
            }
}

/**
 * The profile's favorites, most recently starred first as the server lists them, and the star's
 * writes. A write shows at once and is taken back when the server refuses it. shortcut: online
 * only, a write without the server fails; queue it as watch state is once favorites sync.
 */
class FavoritesModel(val api: Api, val profileId: String) {
    var favorites by mutableStateOf<List<Favorite>>(emptyList())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val ids: Set<String>
        get() = favorites.mapTo(HashSet()) { it.itemId }

    operator fun contains(id: String) = favorites.any { it.itemId == id }

    /** The ids of [kind]'s favorites, most recently starred first. */
    fun of(kind: FavoriteKind): List<String> =
        favorites.filter { it.kind == kind }.map { it.itemId }

    suspend fun load() {
        try {
            favorites = pages { offset ->
                api.call { profiles.listFavorites(profileId, limit = PAGE, offset = offset ?: 0) }
                    .let { it.items to it.nextOffset }
            }
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    /** Stars [id] when [on], else unstars it; a refused write leaves the star as it was. */
    suspend fun set(id: String, kind: FavoriteKind, on: Boolean) {
        val was = favorites.firstOrNull { it.itemId == id }
        if ((was != null) == on) return
        favorites =
            if (on) listOf(Favorite(id, kind, OffsetDateTime.now(ZoneOffset.UTC))) + favorites
            else favorites - was!!
        try {
            // A screen or menu that closes mid-write must not take back a star the server took.
            withContext(NonCancellable) {
                api.call {
                    if (on) profiles.putFavorite(profileId, id)
                    else profiles.deleteFavorite(profileId, id)
                }
            }
            error = null
        } catch (e: Exception) {
            // Only this id is put back, so another star written meanwhile stays.
            favorites =
                if (on) favorites.filter { it.itemId != id }
                else (favorites + was!!).sortedWith(newestFirst)
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    /**
     * Each favorite as its tile or row needs it: a movie, show, season or episode from [catalog],
     * anything else from the server. One the server no longer answers for is left out. shortcut:
     * one read per favorite the catalog lacks; batch them once the server lists favorites with
     * their items.
     */
    suspend fun shelves(catalog: Catalog): FavoriteShelves = coroutineScope {
        fun <T> read(kind: FavoriteKind, get: Api.(String) -> T) =
            of(kind).map { id -> async { runCatching { api.call { get(id) } }.getOrNull() } }
        fun items(kind: FavoriteKind) =
            of(kind).map { id ->
                async {
                    catalog.item(id) ?: runCatching { api.call { items.getItem(id) } }.getOrNull()
                }
            }
        val movies = items(FavoriteKind.movie)
        val shows = items(FavoriteKind.show)
        val seasons = items(FavoriteKind.season)
        val episodes = items(FavoriteKind.episode)
        val artists = read(FavoriteKind.artist) { music.getMusicArtist(it) }
        val albums = read(FavoriteKind.album) { music.getMusicAlbum(it) }
        val songs = read(FavoriteKind.song) { music.getMusicSong(it) }
        val videos = read(FavoriteKind.video) { youtube.getYouTubeVideo(it) }
        FavoriteShelves(
            movies.awaitAll().filterNotNull(),
            shows.awaitAll().filterNotNull(),
            seasons.awaitAll().filterNotNull(),
            episodes.awaitAll().filterNotNull(),
            artists.awaitAll().filterNotNull(),
            albums.awaitAll().filterNotNull(),
            songs.awaitAll().filterNotNull(),
            videos.awaitAll().filterNotNull(),
        )
    }

    companion object {
        const val PAGE = 200
        private val newestFirst =
            compareByDescending<Favorite> { it.createdAt }.thenBy { it.itemId }
    }
}
