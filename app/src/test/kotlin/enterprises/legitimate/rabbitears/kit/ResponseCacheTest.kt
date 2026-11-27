package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.DownloadTitle
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.PositionReport
import enterprises.legitimate.rabbitears.api.models.UnlockRequest
import enterprises.legitimate.rabbitears.api.models.WatchStateInput
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The server's answers kept on the device, read through by every model. */
class ResponseCacheTest {
    private val server = FakeServer()
    private val directory: File = Files.createTempDirectory("responses").toFile()
    private val cache = ResponseCache(directory)
    private val live = LiveFeed(cache)
    private val endpoint = ServerEndpoint.parse("media.example.com")!!
    private val api = Api(endpoint, "tok", server.http, cache = cache)

    init {
        server.on("GET /me", body = Json.me())
        server.on("GET /items", body = Json.page(Json.item("20", "movie", "Dune")))
        server.on("GET /profiles/1/watch-state", body = Json.page(Json.watch(20, 60_000)))
        server.on("PUT /profiles/1/watch-state/20", body = Json.watch(20, watched = true))
        runBlocking { api.call { auth.getMe() } }
    }

    private suspend fun movies(api: Api = this.api) =
        api.call { items.listItems(kind = ItemKind.movie) }.items.map { it.id }

    private suspend fun states() =
        api.call { profiles.listWatchStates("1") }.items.map { it.itemId }

    private fun asked(path: String, fake: FakeServer = server) = fake.called("GET $path").size

    private fun listsNext(id: String) =
        server.on("GET /items", body = Json.page(Json.item(id, "movie", "M")))

    private suspend fun markWatched() = api.call {
        profiles.putWatchState("1", "20", WatchStateInput(true))
    }

    /** Holds the server's answers to [route] until opened, at most five seconds. */
    private fun hold(route: String = "GET /items", then: () -> Unit = {}): CountDownLatch {
        val gate = CountDownLatch(1)
        server.before = {
            if (it == route) {
                gate.await(5, TimeUnit.SECONDS)
                then()
            }
        }
        return gate
    }

    /** Reads that do not wait on the server end well within this. */
    private suspend fun <T> atOnce(read: suspend () -> T): T {
        val started = System.nanoTime()
        val value = read()
        assertTrue("answered without waiting", System.nanoTime() - started < 1_000_000_000)
        return value
    }

    /** Polls for what arrives off the caller's thread, as a signal or a refresh. */
    private suspend fun until(done: () -> Boolean) {
        repeat(400) {
            if (done()) return
            delay(5)
        }
    }

    @Test
    fun aKeptAnswerIsReadAtOnceAndTheServersChangeIsKeptAndSignalledOnce() = runBlocking {
        movies()
        val gate = hold()
        listsNext("21")
        assertEquals("the kept answer", listOf("20"), atOnce { movies() })
        until { asked("/items") == 2 }
        assertEquals("the server is asked behind it", 2, asked("/items"))
        assertEquals(0, live.watched)

        gate.countDown()
        until { live.watched > 0 }
        delay(50)
        assertEquals(1, live.watched)
    }

    @Test
    fun answersRefreshedTogetherSignalOnce() = runBlocking {
        movies()
        states()
        val gate = CountDownLatch(1)
        server.before = { gate.await(5, TimeUnit.SECONDS) }
        listsNext("21")
        server.on("GET /profiles/1/watch-state", body = Json.page(Json.watch(21, 5)))
        movies()
        states()

        gate.countDown()
        until { live.watched > 0 }
        delay(50)
        assertEquals(1, live.watched)
        assertEquals(listOf("21"), movies())
        assertEquals(listOf("21"), states())
    }

    @Test
    fun theSameAnswerFromTheServerSignalsNothing() = runBlocking {
        movies()
        movies()
        until { asked("/items") == 2 }
        delay(50)
        assertEquals(0, live.watched)

        movies()
        until { asked("/items") == 3 }
        assertEquals("each read asks behind it again", 3, asked("/items"))
    }

    @Test
    fun readingAgainOnTheSignalTakesTheNewAnswerWithoutAskingAgain() = runBlocking {
        val reads = mutableListOf<List<String>>()
        val view =
            launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                live.events.collect {
                    val read = movies()
                    synchronized(reads) { reads += read }
                }
            }
        movies()
        listsNext("21")
        movies()

        until { synchronized(reads) { reads.isNotEmpty() } }
        delay(100)
        view.cancel()
        assertEquals(listOf(listOf("21")), reads)
        assertEquals("answered from what the refresh kept", 2, asked("/items"))
        assertEquals(1, live.watched)
    }

    @Test
    fun withTheServerGoneAKeptAnswerIsReadAtOnceAndStays() = runBlocking {
        movies()
        val gate = hold { throw IOException("no route to host") }
        assertEquals(listOf("20"), atOnce { movies() })
        until { asked("/items") == 2 }

        gate.countDown()
        repeat(400) {
            if (asked("/items") >= 3) return@repeat
            assertEquals(listOf("20"), movies())
            delay(5)
        }
        assertEquals("the failed refresh is over, so the next read asks again", 3, asked("/items"))
        delay(50)
        assertEquals("nothing kept is dropped", listOf("20"), movies())
        assertEquals(0, live.watched)
    }

    @Test
    fun anErrorFromTheServerDropsTheKeptAnswerAndSignals() = runBlocking {
        movies()
        server.error("GET /items", 404, "not_found")
        assertEquals(listOf("20"), movies())

        until { live.watched > 0 }
        assertEquals(1, live.watched)
        val gone = runCatching { movies() }.exceptionOrNull()
        assertEquals("the dropped answer is not read again", 404, ApiError.from(gone!!)?.status)
        assertEquals("with nothing kept the read waits on the server", 3, asked("/items"))
    }

    @Test
    fun anErrorAskedFirstAfterAWriteDropsTheKeptAnswerSoAFailingServerCannotBringItBack() =
        runBlocking {
            movies()
            markWatched()
            server.error("GET /items", 404, "not_found")
            val gone = runCatching { movies() }.exceptionOrNull()
            assertEquals(404, ApiError.from(gone!!)?.status)

            server.error("GET /items", 503, "internal")
            val failing = runCatching { movies() }
            assertTrue("the refused answer is not read again", failing.isFailure)
        }

    @Test
    fun aServerThatFailsLeavesTheKeptAnswerAsOneThatIsGoneDoes() = runBlocking {
        movies()
        server.error("GET /items", 503, "internal")
        assertEquals(listOf("20"), movies())
        until { asked("/items") == 2 }
        delay(50)
        assertEquals(0, live.watched)

        markWatched()
        assertEquals("the kept one, though asked for first", listOf("20"), movies())
        assertEquals(3, asked("/items"))
    }

    @Test
    fun afterAWriteTheServerAnsweredTheNextReadAsksFirst() = runBlocking {
        movies()
        listsNext("21")
        markWatched()
        assertEquals("the server's answer, waited on", listOf("21"), movies())
        assertEquals(listOf("21"), movies())
        until { asked("/items") == 3 }
        assertEquals("then reads take the kept answer and ask behind it", 3, asked("/items"))

        delay(50)
        listsNext("22")
        markWatched()
        server.before = { if (it == "GET /items") throw IOException("no route to host") }
        assertEquals("the kept one while the server is gone", listOf("21"), movies())
    }

    @Test
    fun aWriteThatNeverReachedTheServerLeavesReadsOnTheKeptAnswer() = runBlocking {
        movies()
        server.before = { throw IOException("no route to host") }
        runCatching { markWatched() }
        val gate = hold()
        listsNext("21")
        assertEquals(listOf("20"), atOnce { movies() })
        gate.countDown()
    }

    @Test
    fun thePlayersReportsAndQuestionsAskedByPostMarkNothing() = runBlocking {
        server.on("PUT /playback/sessions/7/position", 204)
        server.on("POST /profiles/1/downloads/resolve", body = """{"titles":[],"sizeBytes":0}""")
        movies()
        api.call { playback.reportPlaybackPosition("7", PositionReport(60_000)) }
        api.http
            .newCall(
                Request.Builder()
                    .url("${endpoint.api}/profiles/1/downloads/resolve")
                    .post("{}".toRequestBody())
                    .build(),
            )
            .execute()
            .close()
        val gate = hold()
        listsNext("21")
        assertEquals(listOf("20"), atOnce { movies() })
        gate.countDown()
    }

    @Test
    fun aLaunchPaintsTheKeptLibraryBeforeTheServerAnswers() = runBlocking {
        CatalogModel(api, "1").load()

        val slow = FakeServer()
        slow.on("GET /me", body = Json.me())
        val gate = CountDownLatch(1)
        slow.before = {
            if (it != "GET /me") {
                gate.await(5, TimeUnit.SECONDS)
                throw IOException("timed out")
            }
        }
        val relaunched = Api(endpoint, "tok", slow.http, cache = ResponseCache(directory))
        relaunched.call { auth.getMe() }
        val model = CatalogModel(relaunched, "1")
        atOnce { model.load() }
        assertTrue(model.loaded)
        assertEquals("Dune", model.catalog.item("20")?.parsedTitle)
        assertTrue(model.catalog.isInProgress("20"))
        until { asked("/items", slow) > 0 }
        assertTrue("then asks the server", asked("/items", slow) > 0)

        gate.countDown()
        delay(100)
        assertNull("a refresh that fails behind a painted library says nothing", model.error)
        assertEquals("Dune", model.catalog.item("20")?.parsedTitle)
    }

    @Test
    fun aProfileIsOnlyEverAnsweredWithItsOwnKeptAnswers() = runBlocking {
        server.on("POST /me/lock", 204)
        server.on("POST /profiles/2/unlock", 204)
        assertEquals(listOf("20"), movies())

        api.call { profiles.lockDevice() }
        api.call { profiles.unlockProfile("2", UnlockRequest("1234")) }
        server.on("GET /items", body = Json.page(Json.item("30", "movie", "Bluey")))
        var unreachable = true
        server.before = { if (unreachable) throw IOException("no route to host") }
        val leaked = runCatching { movies() }
        assertTrue(
            "the kid was answered with ${leaked.getOrNull()}",
            leaked.exceptionOrNull() is IOException,
        )

        unreachable = false
        assertEquals(listOf("30"), movies())
        unreachable = true
        assertEquals("the kid's own last answer", listOf("30"), movies())

        val relaunched = Api(endpoint, "tok", server.http, cache = ResponseCache(directory))
        val unknown = runCatching { movies(relaunched) }
        assertTrue(
            "no profile is known to be active until the server or the downloads say which",
            unknown.exceptionOrNull() is IOException,
        )
    }

    @Test
    fun onlyTheServersSuccessfulReadsAreKept() = runBlocking {
        server.error("GET /items", 500, "internal")
        runCatching { movies() }
        server.on("GET /items", body = Json.page(Json.item("20", "movie", "Dune")))
        movies()
        assertEquals("an error is not kept", 2, asked("/items"))

        val always =
            listOf(
                "/events",
                "/profiles/1/watch-state/20",
                "/profiles/1/downloads/3",
                "/playback/sessions/7",
                "/stations",
            )
        for (path in always) server.on("GET $path", body = "{}")
        repeat(2) {
            for (path in always) api.http
                .newCall(Request.Builder().url(endpoint.api + path).build())
                .execute()
                .close()
        }
        for (path in always) assertEquals("$path is asked each time", 2, asked(path))
    }

    @Test
    fun aWatchStateReadAfterPlayComesFromTheServer() = runBlocking {
        server.on("GET /profiles/1/watch-state/20", body = Json.watch(20, 60_000))
        val model = CatalogModel(api, "1")
        model.refreshWatchState("20")
        assertTrue(model.catalog.isInProgress("20"))
        server.on("GET /profiles/1/watch-state/20", body = Json.watch(20, watched = true))
        model.refreshWatchState("20")
        assertTrue(model.catalog.isWatched("20"))
    }

    /** Dune downloaded for profile 1, with what the downloads keep of it. */
    private fun downloaded(): DownloadStore {
        val store = DownloadStore(Files.createTempDirectory("downloads").toFile())
        val dune =
            DownloadTitle(
                "20",
                DownloadTitle.Kind.movie,
                "1",
                available = true,
                watched = false,
                positionMs = 0,
                runtimeMs = 1,
            )
        store.put(
            "1",
            DownloadTarget(itemId = "20"),
            "Dune",
            DownloadQuality.original,
            listOf(dune),
        )
        store.setTitle("20") { it.copy(state = TitleState.OnDevice, file = "20.mp4") }
        store
            .meta("item-20")
            .apply { parentFile!!.mkdirs() }
            .writeText(Json.item("20", "movie", "Dune"))
        return store
    }

    @Test
    fun withTheServerKnownGoneTheLibraryLastAnsweredShowsNotOnlyWhatIsDownloaded() = runBlocking {
        val store = downloaded()
        val cache = ResponseCache(Files.createTempDirectory("responses").toFile(), store)
        val api = Api(endpoint, "tok", server.http, cache = cache)
        api.call { auth.getMe() }
        server.on(
            "GET /items",
            body = Json.page(Json.item("20", "movie", "Dune"), Json.item("21", "movie", "Heat")),
        )
        server.on("GET /profiles/1/watch-state", body = Json.page(Json.watch(21, 5_000)))
        var unreachable = false
        val model = CatalogModel(api, "1", OfflineLibrary(store, "1") { unreachable })
        model.load()

        unreachable = true
        server.before = { fail("nothing waits on a server known to be gone: $it") }
        model.setWatched(true, model.catalog.item("20")!!)
        model.load()
        assertNotNull("the server's last answer, not only the download", model.catalog.item("21"))
        assertTrue(
            "the server's last answer for what is not downloaded",
            model.catalog.isInProgress("21"),
        )
        assertTrue("this device's change, not yet on the server", model.catalog.isWatched("20"))

        val bare = CatalogModel(api, "1", OfflineLibrary(store, "1") { true })
        cache.clear()
        bare.load()
        assertNull("with nothing kept, only what is downloaded", bare.catalog.item("21"))
        assertNotNull(bare.catalog.item("20"))
    }

    @Test
    fun signingOutKeepsNothingAndALaunchWithoutTheServerShowsTheLibraryLastSeen() = runBlocking {
        val store = downloaded()
        val credentials = MemoryCredentialStore()
        credentials.server = "http://192.168.1.20:8477"
        credentials.token = "t1"
        server.on("POST /me/lock", 204)
        server.on("POST /profiles/1/unlock", 204)
        val directory = Files.createTempDirectory("responses").toFile()
        val app =
            AppModel(credentials, "Pixel", server.http, store, ResponseCache(directory, store))
        app.restore()
        server.on(
            "GET /items",
            body = Json.page(Json.item("20", "movie", "Dune"), Json.item("21", "movie", "Heat")),
        )
        CatalogModel(app.signedIn(), "1").load()

        server.before = { throw IOException("no route to host") }
        val after =
            AppModel(credentials, "Pixel", server.http, store, ResponseCache(directory, store))
        after.restore()
        assertTrue(after.offline)
        val model =
            CatalogModel(after.signedIn(), "1", OfflineLibrary(store, "1") { after.offline })
        model.load()
        assertNotNull("the library last seen", model.catalog.item("21"))

        server.before = {}
        server.on("POST /auth/sign-out", 204)
        after.signOut()
        assertFalse("nothing kept stays", directory.exists())
    }
}
