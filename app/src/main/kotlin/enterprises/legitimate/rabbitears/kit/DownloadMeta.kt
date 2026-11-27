package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Collection
import enterprises.legitimate.rabbitears.api.models.DownloadTitle
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.MusicAlbum
import enterprises.legitimate.rabbitears.api.models.MusicArtist
import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.api.models.Playlist
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import enterprises.legitimate.rabbitears.api.models.PlaylistPlayOrderItem
import enterprises.legitimate.rabbitears.api.models.YouTubeChannel
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * The metadata and artwork of what is downloaded, as the server answered for it, kept beside the
 * media so the usual screens show it with no server. Items, songs and videos are the same for every
 * profile; playlists are kept per profile, as each sees its own.
 */
class DownloadMeta(private val store: DownloadStore) {
    private val json = DownloadStore.json

    fun <T> get(key: String, serializer: KSerializer<T>): T? =
        store
            .meta(key)
            .takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString(serializer, it.readText()) }.getOrNull() }

    private fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        val file = store.meta(key)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, value))
    }

    fun item(id: String) = get("item-$id", Item.serializer())

    fun song(id: String) = get("song-$id", MusicSong.serializer())

    fun album(id: String) = get("album-$id", MusicAlbum.serializer())

    fun artist(id: String) = get("artist-$id", MusicArtist.serializer())

    fun video(id: String) = get("video-$id", YouTubeVideo.serializer())

    fun channel(id: String) = get("channel-$id", YouTubeChannel.serializer())

    /** Whether [title]'s metadata is kept, with all that its screens need beside it. */
    fun has(title: DownloadTitle) =
        store
            .meta(
                when (title.kind) {
                    DownloadTitle.Kind.movie,
                    DownloadTitle.Kind.episode -> "item-${title.itemId}"
                    DownloadTitle.Kind.song -> "song-${title.itemId}"
                    DownloadTitle.Kind.video -> "video-${title.itemId}"
                },
            )
            .exists()

    fun playlist(profileId: String, id: String) =
        get("playlist-$profileId-$id", Playlist.serializer())

    fun entries(profileId: String, id: String) =
        get("entries-$profileId-$id", ListSerializer(PlaylistEntry.serializer()))

    fun playOrder(profileId: String, id: String) =
        get("order-$profileId-$id", ListSerializer(PlaylistPlayOrderItem.serializer()))

    fun collection(id: String) = get("collection-$id", Collection.serializer())

    fun collectionMovies(id: String) = get("movies-$id", ListSerializer(Item.serializer()))

    /** The image at an artwork path, such as `/api/v2/items/7/artwork/poster`. */
    fun art(path: String): ByteArray? = artFile(path).takeIf { it.exists() }?.readBytes()

    private fun artFile(path: String) =
        File(store.dir, "art/" + path.trim('/').replace(Regex("[^A-Za-z0-9]+"), "-"))

    /**
     * Keeps what the request's screens need, the playlist's or collection's own pages each time.
     * What fails to arrive is left for the next sync.
     */
    suspend fun save(
        api: Api,
        profileId: String,
        request: DownloadRequest,
        titles: List<DownloadTitle>,
    ) {
        attempt {
            request.target.playlistId?.let { id ->
                put(
                    "playlist-$profileId-$id",
                    Playlist.serializer(),
                    api.call { playlists.getPlaylist(profileId, id) },
                )
                val entries = pages { o ->
                    api.call { playlists.listPlaylistEntries(profileId, id, PAGE, o ?: 0) }
                        .let { it.items to it.nextOffset }
                }
                put("entries-$profileId-$id", ListSerializer(PlaylistEntry.serializer()), entries)
                val order = pages { o ->
                    api.call { playlists.listPlaylistPlayOrder(profileId, id, PAGE, o ?: 0) }
                        .let { it.items to it.nextOffset }
                }
                put(
                    "order-$profileId-$id",
                    ListSerializer(PlaylistPlayOrderItem.serializer()),
                    order,
                )
            }
        }
        attempt {
            request.target.collectionId?.let { id ->
                put(
                    "collection-$id",
                    Collection.serializer(),
                    api.call { collections.getCollection(id) },
                )
                val movies = pages { o ->
                    api.call { collections.listCollectionMovies(id, PAGE, o ?: 0) }
                        .let { it.items to it.nextOffset }
                }
                put("movies-$id", ListSerializer(Item.serializer()), movies)
                art(api, "/api/v2/collections/$id/artwork/poster")
            }
        }
        val done = mutableSetOf<String>()
        for (title in titles) attempt { saveTitle(api, title, done) }
    }

    /**
     * Keeps what one title's screens need, skipping what [done] says this save already kept. The
     * title's own file is written last, so that it stands for all it needs being kept.
     */
    private suspend fun saveTitle(api: Api, title: DownloadTitle, done: MutableSet<String>) {
        when (title.kind) {
            DownloadTitle.Kind.movie,
            DownloadTitle.Kind.episode -> {
                val chain = mutableListOf<Item>()
                var id: String? = title.itemId
                while (id != null && "item-$id" !in done) {
                    val item = api.call { items.getItem(id!!) }
                    chain += item
                    id = item.parentId
                }
                for (item in chain.asReversed()) {
                    itemArt(api, item.id)
                    put("item-${item.id}", Item.serializer(), item)
                    done += "item-${item.id}"
                }
            }
            DownloadTitle.Kind.song -> {
                val song = api.call { music.getMusicSong(title.itemId) }
                once(done, "album-${song.albumId}") {
                    put(
                        "album-${song.albumId}",
                        MusicAlbum.serializer(),
                        api.call { music.getMusicAlbum(song.albumId) },
                    )
                    itemArt(api, song.albumId)
                }
                once(done, "artist-${song.artistId}") {
                    put(
                        "artist-${song.artistId}",
                        MusicArtist.serializer(),
                        api.call { music.getMusicArtist(song.artistId) },
                    )
                    itemArt(api, song.artistId)
                }
                if (song.albumArtistId != song.artistId)
                    once(done, "artist-${song.albumArtistId}") {
                        put(
                            "artist-${song.albumArtistId}",
                            MusicArtist.serializer(),
                            api.call { music.getMusicArtist(song.albumArtistId) },
                        )
                    }
                put("song-${song.id}", MusicSong.serializer(), song)
            }
            DownloadTitle.Kind.video -> {
                val video = api.call { youtube.getYouTubeVideo(title.itemId) }
                once(done, "channel-${video.channelId}") {
                    put(
                        "channel-${video.channelId}",
                        YouTubeChannel.serializer(),
                        api.call { youtube.getYouTubeChannel(video.channelId) },
                    )
                    itemArt(api, video.channelId)
                }
                itemArt(api, video.id)
                put("video-${video.id}", YouTubeVideo.serializer(), video)
            }
        }
    }

    private suspend fun once(done: MutableSet<String>, key: String, keep: suspend () -> Unit) {
        if (key in done) return
        keep()
        done += key
    }

    private suspend fun itemArt(api: Api, id: String) {
        art(api, "/api/v2/items/$id/artwork/poster")
        art(api, "/api/v2/items/$id/artwork/backdrop")
    }

    private suspend fun art(api: Api, path: String) =
        withContext(Dispatchers.IO) {
            val body = api.bytes(path).body ?: return@withContext
            val file = artFile(path)
            file.parentFile?.mkdirs()
            file.writeBytes(body)
        }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    companion object {
        const val PAGE = 200
    }
}
