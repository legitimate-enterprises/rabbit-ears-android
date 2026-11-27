package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Download
import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.FolderInput
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.LibraryInput
import enterprises.legitimate.rabbitears.api.models.LibraryKind
import enterprises.legitimate.rabbitears.api.models.Platform
import enterprises.legitimate.rabbitears.api.models.SetupRequest
import enterprises.legitimate.rabbitears.api.models.SignInRequest
import enterprises.legitimate.rabbitears.api.models.UnlockRequest
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A real server on its own fixtures: scripts/downloads-e2e.sh starts one and sets RABBIT_EARS_E2E
 * to its address and RABBIT_EARS_FIXTURES to the media. A movie goes through the real downloader
 * onto disk, resumes from half, and decodes whole with ffmpeg.
 */
class DownloadsEndToEndTest {
    private val base = System.getenv("RABBIT_EARS_E2E")
    private val fixtures = System.getenv("RABBIT_EARS_FIXTURES")

    private val facts =
        DeviceFacts(
            listOf(
                CodecFacts("video/avc", true, listOf(AVC_HIGH to AVC_LEVEL_51), 3840, 2160, 60.0),
                CodecFacts("audio/mp4a-latm", true),
            ),
            ScreenFacts(2400, 1080, 120.0),
            emptyList(),
            PathFacts(),
        )

    @Test
    fun aMovieDownloadsResumesAndPlays() = runBlocking {
        assumeTrue("needs scripts/downloads-e2e.sh", base != null && fixtures != null)
        val endpoint = ServerEndpoint.parse(base!!)!!
        val api = signIn(endpoint)
        val me = api.call { auth.getMe() }
        val profile = me.account.id
        api.call { profiles.unlockProfile(profile, UnlockRequest(null)) }
        val movie = scannedMovie(api)

        val store = DownloadStore(Files.createTempDirectory("e2e-downloads").toFile())
        var scheduled = 0
        val downloader =
            Downloads(
                store,
                HttpTransfer { api.http },
                object : DownloadScheduler {
                    override fun schedule(wifiOnly: Boolean) {
                        scheduled++
                    }
                },
                { Snapshot.snapshot(facts, it.endpoint, QualityChoice.Original) },
                pollMs = 500,
            )
        val plan =
            downloader.plan(
                api,
                profile,
                DownloadTarget(itemId = movie),
                "Toy Story",
                DownloadQuality.original,
            )
        assertEquals(1, plan.count)
        downloader.add(api, plan)
        assertTrue(scheduled > 0)
        assertTrue("its metadata is kept", store.meta("item-$movie").exists())

        downloader.run(api, { profile }, emptySet(), unmetered = true)
        val title = store.title(movie)!!
        assertEquals(title.error, TitleState.OnDevice, title.state)
        val file = store.media(title.file!!)
        val source = File(fixtures, "library/Movies/Toy Story (1995)/Toy Story (1995).mp4")
        assertEquals(source.length(), file.length())
        assertTrue(
            "released on the server",
            api.call { downloads.listDownloads(profile) }.items.isEmpty(),
        )

        // Resumes from half with Range and If-Range against a fresh claim of the same file.
        val claim = api.call {
            downloads.createDownload(
                profile,
                enterprises.legitimate.rabbitears.api.models.DownloadInput(
                    movie,
                    DownloadQuality.original,
                    Snapshot.snapshot(facts, endpoint, QualityChoice.Original),
                ),
            )
        }
        val resumed = File(file.parentFile, "resumed.mp4")
        val part = File(resumed.path + ".part")
        RandomAccessFile(part, "rw").use { out ->
            out.write(file.readBytes(), 0, (file.length() / 2).toInt())
        }
        var started = -1L
        HttpTransfer { api.http }
            .fetch(endpoint.resolve(claim.url!!), resumed, title.etag) { got, _ ->
                if (started < 0) started = got
            }
        assertEquals("picked up where it stopped", file.length() / 2, started)
        assertArrayEquals(source.readBytes(), resumed.readBytes())
        api.call { downloads.deleteDownload(profile, claim.id) }

        // Played headlessly: every frame of the kept file decodes.
        val ffmpeg =
            ProcessBuilder("ffmpeg", "-v", "error", "-i", file.path, "-f", "null", "-")
                .redirectErrorStream(true)
                .start()
        val output = ffmpeg.inputStream.bufferedReader().readText()
        assertTrue(ffmpeg.waitFor(5, TimeUnit.MINUTES))
        assertEquals(output, 0, ffmpeg.exitValue())

        // The profile may still see it, so sync keeps it.
        downloader.sync(api, profile)
        assertEquals(TitleState.OnDevice, store.title(movie)?.state)
        assertTrue(store.local(profile, movie) != null)
    }

    /**
     * An album whose FLAC and MP3 songs this device cannot play, claimed while transfers wait for
     * Wi-Fi: every conversion gets ready on the server with nothing transferred, then the album
     * comes down once the network allows.
     */
    @Test
    fun anAlbumsConversionsGoAheadWithoutWaitingOnTransfers() = runBlocking {
        assumeTrue("needs scripts/downloads-e2e.sh", base != null && fixtures != null)
        val api = signIn(ServerEndpoint.parse(base!!)!!)
        val profile = api.call { auth.getMe() }.account.id
        api.call { profiles.unlockProfile(profile, UnlockRequest(null)) }
        val album = scannedAlbum(api)
        val store = DownloadStore(Files.createTempDirectory("e2e-album").toFile())
        var transfers = 0
        val downloader =
            Downloads(
                store,
                object : Transfer {
                    val http = HttpTransfer { api.http }

                    override suspend fun fetch(
                        url: String,
                        into: File,
                        etag: String?,
                        progress: (Long, String?) -> Unit,
                    ) {
                        transfers++
                        http.fetch(url, into, etag, progress)
                    }
                },
                object : DownloadScheduler {
                    override fun schedule(wifiOnly: Boolean) = Unit
                },
                { Snapshot.snapshot(facts, it.endpoint, QualityChoice.Original) },
                pollMs = 500,
            )
        val plan =
            downloader.plan(
                api,
                profile,
                DownloadTarget(itemId = album),
                "First Light",
                DownloadQuality.aac256,
            )
        assertEquals(SONGS, plan.count)
        downloader.add(api, plan)

        downloader.run(api, { profile }, emptySet(), unmetered = false)
        val songs = store.state.titles.values
        assertTrue(songs.all { it.state == TitleState.WaitingForWifi && it.claim != null })
        suspend fun claims() = api.call { downloads.listDownloads(profile) }.items
        suspend fun converting() = claims().any { it.status != Download.Status.ready }
        assertTrue("some songs need converting", converting())
        var tries = 0
        while (converting() && tries++ < CONVERT_TRIES) delay(1_000)
        assertEquals(
            "every conversion is ready with nothing transferred",
            List(SONGS) { Download.Status.ready },
            claims().map { it.status },
        )
        assertEquals(0, transfers)

        downloader.run(api, { profile }, emptySet(), unmetered = true)
        assertTrue(store.state.titles.values.all { it.state == TitleState.OnDevice })
        assertTrue("released on the server", claims().isEmpty())
    }

    /** The album "First Light" once a Music library over the fixtures has scanned its songs. */
    private suspend fun scannedAlbum(api: Api): String {
        val found = api.call { libraries.listLibraries() }.items
        if (found.none { it.kind == LibraryKind.music }) {
            val library = api.call {
                libraries.createLibrary(LibraryInput("Music", LibraryKind.music))
            }
            api.call { libraries.addLibraryFolder(library.id, FolderInput("$fixtures/music")) }
        }
        repeat(SCAN_TRIES) {
            val album =
                api.call { music.listMusicAlbums(q = "First Light") }
                    .items
                    .firstOrNull { it.songCount == SONGS }
            if (album != null) return album.id
            delay(1_000)
        }
        error("First Light was never scanned")
    }

    private suspend fun signIn(endpoint: ServerEndpoint): Api {
        val open = Api(endpoint, null)
        val health = open.call { system.getHealth() }
        val result =
            if (health.setupRequired)
                open.call {
                    auth.setup(SetupRequest("Den", "Ada", EMAIL, PASSWORD, "e2e", Platform.android))
                }
            else open.call { auth.signIn(SignInRequest(EMAIL, PASSWORD, "e2e", Platform.android)) }
        return Api(endpoint, result.token)
    }

    /** Toy Story once a Movies library over the fixtures has scanned it. */
    private suspend fun scannedMovie(api: Api): String {
        val found = api.call { libraries.listLibraries() }.items
        if (found.none { it.kind == LibraryKind.movies }) {
            val library = api.call {
                libraries.createLibrary(LibraryInput("Movies", LibraryKind.movies))
            }
            api.call {
                libraries.addLibraryFolder(library.id, FolderInput("$fixtures/library/Movies"))
            }
        }
        repeat(SCAN_TRIES) {
            val movie =
                api.call { items.listItems(kind = ItemKind.movie) }
                    .items
                    .firstOrNull { it.parsedTitle == "Toy Story" }
            if (
                movie != null &&
                    api.call { media.listMediaFiles(movie.id, limit = 1) }.items.isNotEmpty()
            )
                return movie.id
            delay(1_000)
        }
        error("Toy Story was never scanned")
    }

    companion object {
        const val EMAIL = "ada@example.com"
        const val PASSWORD = "correct horse battery"
        const val SCAN_TRIES = 120
        const val CONVERT_TRIES = 120
        /** The songs of the fixtures' album: FLAC, MP3 and AAC. */
        const val SONGS = 3
        /** MediaCodecInfo's AVCProfileHigh and AVCLevel51. */
        const val AVC_HIGH = 8
        const val AVC_LEVEL_51 = 0x4000
    }
}
