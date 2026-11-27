package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.AudioDetails
import enterprises.legitimate.rabbitears.api.models.Genre
import enterprises.legitimate.rabbitears.api.models.LibraryKind
import enterprises.legitimate.rabbitears.api.models.MusicAlbum
import enterprises.legitimate.rabbitears.api.models.MusicAlbumSort
import enterprises.legitimate.rabbitears.api.models.MusicArtist
import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.api.models.MusicSongSort
import enterprises.legitimate.rabbitears.api.models.Verdict
import java.math.BigDecimal
import java.util.Locale

/** Artist: the header, Popular (the five most played) and the albums, newest first. */
data class ArtistDetail(
    val artist: MusicArtist,
    val popular: List<MusicSong>,
    val albums: List<MusicAlbum>,
)

/** Album: the header and its songs in disc then track order. */
data class AlbumDetail(val album: MusicAlbum, val songs: List<MusicSong>)

enum class AlbumSort(val label: String) {
    Title("Title"),
    Artist("Artist"),
    Year("Year"),
    Added("Recently added"),
}

enum class SongSort(val label: String, val api: MusicSongSort) {
    Title("Title", MusicSongSort.title),
    Artist("Artist", MusicSongSort.artist),
    Album("Album", MusicSongSort.album),
    Added("Recently added", MusicSongSort.added),
    Popular("Popular", MusicSongSort.popular),
}

/**
 * The active profile's music. The server answers a kid with empty pages, so nothing here checks the
 * profile kind. The Music tab's lists load once per profile and genre, and the songs once per sort
 * too, and are kept.
 */
class MusicModel(val api: Api, private val offline: OfflineLibrary? = null) {
    var albums by mutableStateOf<List<MusicAlbum>?>(null)
        private set

    var artists by mutableStateOf<List<MusicArtist>?>(null)
        private set

    var songs by mutableStateOf<List<MusicSong>?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** What each list was last read for, [LiveFeed.watched] included. */
    private var albumsFor: Pair<String?, Int>? = null
    private var artistsFor: Pair<String?, Int>? = null
    private var songsFor: Triple<SongSort, String?, Int>? = null

    /** The next load of each list reads it again, as a pull to refresh needs. */
    fun forget() {
        albumsFor = null
        artistsFor = null
        songsFor = null
    }

    /**
     * shortcut: each list is read whole, as the video catalog is; page on scroll once a library
     * passes tens of thousands of songs. Offline, the device's lists stand whatever the genre. A
     * list is read again once [watched] has moved.
     */
    suspend fun loadAlbums(genreId: String? = null, watched: Int = 0) {
        if (albums != null && albumsFor == genreId to watched) return
        read({ albums() }) {
                pages { o ->
                    api.call {
                            music.listMusicAlbums(genreId = genreId, limit = PAGE, offset = o ?: 0)
                        }
                        .let { it.items to it.nextOffset }
                }
            }
            ?.let {
                albums = it
                albumsFor = genreId to watched
            }
    }

    suspend fun loadArtists(genreId: String? = null, watched: Int = 0) {
        if (artists != null && artistsFor == genreId to watched) return
        read({ artists() }) {
                pages { o ->
                    api.call {
                            music.listMusicArtists(genreId = genreId, limit = PAGE, offset = o ?: 0)
                        }
                        .let { it.items to it.nextOffset }
                }
            }
            ?.let {
                artists = it
                artistsFor = genreId to watched
            }
    }

    suspend fun loadSongs(
        sort: SongSort = SongSort.Title,
        genreId: String? = null,
        watched: Int = 0,
    ) {
        if (songs != null && songsFor == Triple(sort, genreId, watched)) return
        read({ songs() }) {
                pages { o ->
                    api.call {
                            music.listMusicSongs(
                                genreId = genreId,
                                sort = sort.api,
                                limit = PAGE,
                                offset = o ?: 0,
                            )
                        }
                        .let { it.items to it.nextOffset }
                }
            }
            ?.let {
                songs = it
                songsFor = Triple(sort, genreId, watched)
            }
    }

    /** The genre menu's options: the genres of music libraries only, by name; none offline. */
    suspend fun genres(): List<Genre> = runCatching {
        offline.answer({ emptyList() }) {
            pages { o ->
                api.call { libraries.listLibraries(PAGE, o ?: 0) }.let { it.items to it.nextOffset }
            }
                .filter { it.kind == LibraryKind.music }
                .flatMap { library ->
                    pages { o ->
                        api.call { items.listGenres(library.id, PAGE, o ?: 0) }
                            .let { it.items to it.nextOffset }
                    }
                }
                .distinctBy { it.id }
                .sortedBy { it.name }
        }
    }
        .getOrDefault(emptyList())

    suspend fun artist(id: String): ArtistDetail? =
        read({ artist(id) }) {
            ArtistDetail(
                api.call { music.getMusicArtist(id) },
                api.call {
                        music.listMusicSongs(artistId = id, sort = MusicSongSort.popular, limit = 5)
                    }
                    .items,
                pages { o ->
                    api.call {
                            music.listMusicAlbums(
                                artistId = id,
                                sort = MusicAlbumSort.year,
                                limit = PAGE,
                                offset = o ?: 0,
                            )
                        }
                        .let { it.items to it.nextOffset }
                }
                    .sortedByDescending { it.year ?: 0 },
            )
        }

    suspend fun album(id: String): AlbumDetail? =
        read({ album(id) }) {
            AlbumDetail(
                api.call { music.getMusicAlbum(id) },
                pages { o ->
                    api.call { music.listMusicSongs(albumId = id, limit = PAGE, offset = o ?: 0) }
                        .let { it.items to it.nextOffset }
                },
            )
        }

    /** Every song an artist plays, album by album, for Play and Shuffle on Artist. */
    suspend fun songsBy(artistId: String): List<MusicSong> =
        read({ songsBy(artistId) }) {
                pages { o ->
                    api.call {
                            music.listMusicSongs(
                                artistId = artistId,
                                sort = MusicSongSort.album,
                                limit = PAGE,
                                offset = o ?: 0,
                            )
                        }
                        .let { it.items to it.nextOffset }
                }
            }
            .orEmpty()

    /** Null after an error, which [error] names; [local] answers when the server can't. */
    private suspend fun <T> read(local: OfflineLibrary.() -> T?, block: suspend () -> T?): T? =
        try {
            (if (offline != null) offline.or(local, block) else block()).also { error = null }
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
            null
        }

    companion object {
        const val PAGE = 200

        fun sorted(albums: List<MusicAlbum>, sort: AlbumSort): List<MusicAlbum> =
            when (sort) {
                AlbumSort.Title ->
                    albums.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                AlbumSort.Artist ->
                    albums.sortedWith(
                        compareBy<MusicAlbum, String>(String.CASE_INSENSITIVE_ORDER) {
                                it.artistName
                            }
                            .thenBy { it.year ?: 0 },
                    )
                AlbumSort.Year -> albums.sortedByDescending { it.year ?: 0 }
                AlbumSort.Added -> albums.sortedByDescending { it.addedAt }
            }
    }
}

/**
 * One row of the player's file details. A [lossless] value wears the Lossless tag; [success] is
 * null for a plain value, true for the success color and false for the caution color.
 */
data class DetailRow(
    val label: String,
    val value: String,
    val lossless: Boolean = false,
    val success: Boolean? = null,
)

/** The details behind the album art, "Source" then "Delivered"; a row with no value is left out. */
fun Format.audioDetails(audio: AudioDetails): List<Pair<String, List<DetailRow>>> {
    val (source, delivered) = audio.source to audio.delivered
    return listOf(
        "Source" to
            listOfNotNull(
                source.codec?.let { DetailRow("Format", codec(it), lossless = source.lossless) },
                source.bitrate?.let { DetailRow("Bitrate", kbps(it)) },
                source.sampleRateHz?.let {
                    DetailRow(
                        "Sample rate",
                        "${BigDecimal(it).movePointLeft(3).stripTrailingZeros().toPlainString()} kHz",
                    )
                },
                source.bitDepth?.let { DetailRow("Bit depth", "$it-bit") },
                source.channels?.let {
                    DetailRow(
                        "Channels",
                        when (it) {
                            1 -> "Mono"
                            2 -> "Stereo"
                            else -> "$it channels"
                        },
                    )
                },
                source.sizeBytes?.let { DetailRow("Size", bytes(it)) },
            ),
        "Delivered" to
            listOfNotNull(
                DetailRow(
                    "Playback",
                    verdict(delivered.verdict),
                    success = delivered.verdict in listOf(Verdict.direct_play, Verdict.remux),
                ),
                DetailRow("Codec", codec(delivered.codec)),
                delivered.bitrate?.let { DetailRow("Bitrate", kbps(it)) },
            ),
    )
}

/** `2,116 kb/s` from bits per second. */
private fun kbps(bitsPerSecond: Long): String =
    "%,d kb/s".format(Locale.US, (bitsPerSecond + 500) / 1000)

/** An ffprobe codec name as listeners know it: flac is FLAC, pcm_s16le is PCM. */
fun Format.codec(name: String): String = name.substringBefore('_').uppercase()

/** A song's length: `3:48`. */
fun Format.length(ms: Long?): String = ms?.let { clock((it + 500) / 1000) }.orEmpty()
