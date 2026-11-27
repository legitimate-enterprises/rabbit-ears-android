package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Collection
import enterprises.legitimate.rabbitears.api.models.DownloadTitle.Kind
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.MusicAlbum
import enterprises.legitimate.rabbitears.api.models.MusicArtist
import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.api.models.Playlist
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import enterprises.legitimate.rabbitears.api.models.PlaylistPlayOrderItem
import enterprises.legitimate.rabbitears.api.models.WatchState
import enterprises.legitimate.rabbitears.api.models.YouTubeChannel
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** A change made on this device, as the server would answer it. */
fun LocalWatch.asWatchState(): WatchState {
    val at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneOffset.UTC)
    return WatchState(itemId, positionMs, watched, 0, at, editionId, at)
}

/** [OfflineLibrary.or] with downloads, else [remote]. */
suspend fun <T> OfflineLibrary?.answer(local: OfflineLibrary.() -> T, remote: suspend () -> T): T =
    if (this == null) remote() else or(local, remote)

/**
 * What one profile has on this device, in the shapes the server answers with, so the usual models
 * and screens show it when the server can't be reached. [unreachable] is whether the app already
 * knows the server is out of reach, so nothing waits on it.
 */
class OfflineLibrary(
    private val store: DownloadStore,
    val profileId: String,
    private val unreachable: () -> Boolean = { false },
) {
    private val meta = DownloadMeta(store)

    val isUnreachable: Boolean
        get() = unreachable()

    private fun onDevice(kind: Kind) =
        store.titles(profileId).filter { it.kind == kind && it.state == TitleState.OnDevice }

    fun has(itemId: String) = store.onDevice(profileId, itemId) != null

    /**
     * The server's answer from [remote], or the device's from [local] when the server is out of
     * reach and its last answer was not kept; a refusal from the server stands.
     */
    suspend fun <T> or(local: OfflineLibrary.() -> T, remote: suspend () -> T): T {
        return try {
            if (unreachable())
                withContext(ResponseCache.keptOnly.asContextElement(true)) { remote() }
            else remote()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            local()
        }
    }

    /** Downloaded movies and episodes, with the seasons and shows above them. */
    fun catalog(): Catalog {
        val titles =
            (onDevice(Kind.movie) + onDevice(Kind.episode)).mapNotNull { meta.item(it.itemId) }
        val items = titles.flatMap { generateSequence(it) { i -> i.parentId?.let(meta::item) } }
        return Catalog(items.distinctBy { it.id }, watchStates())
    }

    fun watchStates(): List<WatchState> = store.watches(profileId).map { it.asWatchState() }

    fun watchState(itemId: String): WatchState? = store.watch(profileId, itemId)?.asWatchState()

    /** Keeps a change made with no server, as of now, for the next sync. */
    fun record(
        itemId: String,
        positionMs: Long,
        watched: Boolean,
        editionId: String? = null,
        ended: Boolean = true,
    ) =
        store.record(
            profileId,
            itemId,
            if (watched) 0 else positionMs,
            watched,
            System.currentTimeMillis(),
            editionId,
            ended,
        )

    fun songs(): List<MusicSong> =
        onDevice(Kind.song)
            .mapNotNull { meta.song(it.itemId) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    fun albums(): List<MusicAlbum> = songs().map { it.albumId }.distinct().mapNotNull(meta::album)

    fun artists(): List<MusicArtist> =
        songs()
            .flatMap { listOf(it.artistId, it.albumArtistId) }
            .distinct()
            .mapNotNull(meta::artist)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun album(id: String): AlbumDetail? {
        val album = meta.album(id) ?: return null
        val songs =
            songs()
                .filter { it.albumId == id }
                .sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
        return AlbumDetail(album, songs)
    }

    fun songsBy(artistId: String): List<MusicSong> =
        songs()
            .filter { it.artistId == artistId || it.albumArtistId == artistId }
            .sortedWith(
                compareBy({ it.albumTitle }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }),
            )

    fun artist(id: String): ArtistDetail? {
        val artist = meta.artist(id) ?: return null
        val albums =
            songsBy(id)
                .map { it.albumId }
                .distinct()
                .mapNotNull(meta::album)
                .sortedByDescending { it.year ?: 0 }
        return ArtistDetail(artist, songsBy(id).take(POPULAR), albums)
    }

    /** Downloaded videos with this device's watch state, newest first. */
    fun videos(): List<YouTubeVideo> =
        onDevice(Kind.video)
            .mapNotNull { meta.video(it.itemId) }
            .map { v ->
                store.watch(profileId, v.id)?.let {
                    v.copy(watched = it.watched, positionMs = it.positionMs)
                } ?: v
            }
            .sortedByDescending { it.publishedOn ?: it.addedAt.toLocalDate() }

    fun inProgress(): List<YouTubeVideo> = videos().filter { it.positionMs > 0 && !it.watched }

    fun channels(): List<YouTubeChannel> =
        videos()
            .map { it.channelId }
            .distinct()
            .mapNotNull(meta::channel)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    fun channel(id: String): ChannelDetail? =
        meta.channel(id)?.let { ChannelDetail(it, videos().filter { v -> v.channelId == id }) }

    fun video(id: String): YouTubeVideo? = videos().firstOrNull { it.id == id }

    fun playlists(): List<Playlist> =
        store.requests(profileId).mapNotNull { r ->
            r.target.playlistId?.let { meta.playlist(profileId, it) }
        }

    fun entries(playlistId: String): List<PlaylistEntry>? = meta.entries(profileId, playlistId)

    /** The playlist's rows that are on the device, in play order. */
    fun playOrder(playlistId: String): List<PlaylistPlayOrderItem>? =
        meta.playOrder(profileId, playlistId)?.filter { has(it.itemId) }

    fun collections(): List<Collection> =
        store.requests(profileId).mapNotNull { r -> r.target.collectionId?.let(meta::collection) }

    fun collection(id: String): Collection? = meta.collection(id)

    fun collectionMovies(id: String): List<Item>? = meta.collectionMovies(id)?.filter { has(it.id) }

    /** What follows [itemId] on the device: the next downloaded episode or channel video. */
    fun upNext(itemId: String, run: PlaylistRun?): UpNextPlay? {
        if (run != null) {
            val rest = run.rows.drop(run.index + 1)
            val i = rest.indexOfFirst { has(it.itemId) }
            if (i < 0) return null
            val row = rest[i]
            val title = row.item?.let(Catalog::title) ?: row.song?.title ?: ""
            return UpNextPlay(title, row.itemId, run.copy(index = run.index + 1 + i), row.editionId)
        }
        catalog().let { c ->
            val item = c.item(itemId) ?: return@let
            val show = c.show(item) ?: return null
            val episodes = c.episodes(show.id)
            val next = episodes.dropWhile { it.id != itemId }.drop(1).firstOrNull() ?: return null
            return UpNextPlay(Catalog.title(next), next.id, null, null)
        }
        val video = meta.video(itemId) ?: return null
        val channel =
            videos()
                .filter { it.channelId == video.channelId }
                .sortedBy { it.publishedOn ?: it.addedAt.toLocalDate() }
        val next = channel.dropWhile { it.id != itemId }.drop(1).firstOrNull() ?: return null
        return UpNextPlay(next.title, next.id, null, null)
    }

    companion object {
        const val POPULAR = 5
    }
}
