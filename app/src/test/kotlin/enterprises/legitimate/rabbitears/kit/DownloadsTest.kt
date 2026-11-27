package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.AudioRouteCapabilities
import enterprises.legitimate.rabbitears.api.models.CapabilitySnapshot
import enterprises.legitimate.rabbitears.api.models.DecoderCapabilities
import enterprises.legitimate.rabbitears.api.models.DisplayCapabilities
import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.DownloadTitle
import enterprises.legitimate.rabbitears.api.models.NetworkPathCapabilities
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsTest {
    private val server = FakeServer()
    private val api = server.api()
    private var free = Long.MAX_VALUE
    private val store = DownloadStore(Files.createTempDirectory("downloads").toFile(), { free })
    private val fetched = mutableListOf<String>()
    private var failFetch: Exception? = null
    private var midFetch: () -> Unit = {}
    private val transfer =
        object : Transfer {
            override suspend fun fetch(
                url: String,
                into: File,
                etag: String?,
                progress: (Long, String?) -> Unit,
            ) {
                failFetch?.let { throw it }
                progress(0, "\"e1\"")
                midFetch()
                // The next report is where a removal made meanwhile stops the real transfer.
                progress(1, "\"e1\"")
                fetched += url
                into.parentFile?.mkdirs()
                into.writeText("media of $url")
                progress(into.length(), "\"e1\"")
            }
        }
    private var scheduled = 0
    private var whenScheduled: () -> Unit = {}
    private val downloads =
        Downloads(
            store,
            transfer,
            object : DownloadScheduler {
                override fun schedule(wifiOnly: Boolean) {
                    scheduled++
                    whenScheduled()
                }
            },
            { snapshot },
            pollMs = 0,
        )

    private val snapshot =
        CapabilitySnapshot(
            DecoderCapabilities(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            DisplayCapabilities(1920, 1080, 60.0, emptyList()),
            AudioRouteCapabilities(2, emptyList()),
            NetworkPathCapabilities(true, false, false, false),
        )

    private fun title(
        id: String,
        kind: String = "movie",
        watched: Boolean = false,
        size: Long = 10,
    ) =
        """{"itemId":$id,"kind":"$kind","libraryId":"1","available":true,"watched":$watched,""" +
            """"positionMs":0,"sizeBytes":$size,"quality":"original"}"""

    private fun resolution(vararg titles: String) =
        """{"titles":[${titles.joinToString(",")}],"sizeBytes":0}"""

    private fun download(
        id: String,
        item: String,
        status: String = "ready",
        subtitles: String = "[]",
    ) =
        """{"id":$id,"profileId":"1","itemId":$item,"mediaFileId":"3","quality":"original",""" +
            """"status":"$status","verdict":"direct_play","container":"mp4","sizeBytes":12,""" +
            """"estimated":false,"progress":1,"subtitles":$subtitles,"createdAt":"${Json.T}","modifiedAt":"${Json.T}",""" +
            """"url":"/api/v2/downloads/$id/file"}"""

    private fun batch(vararg results: String) = """{"results":[${results.joinToString(",")}]}"""

    private fun claimed(item: String, id: String, status: String = "ready") =
        """{"itemId":$item,"download":${download(id, item, status)}}"""

    private fun refused(item: String, code: String) =
        """{"itemId":$item,"error":{"code":"$code","message":"$code"}}"""

    /** The item ids a batch claim asked for, in its order. */
    private fun asked(call: FakeServer.Call) =
        Regex(""""itemId":"?(\w+)""").findAll(call.body).map { it.groupValues[1] }.toList()

    /** Answers every metadata call a save makes, so a plan can be filed. */
    private fun quietMeta() {
        server.on("GET /items/1", body = Json.item("1", "movie", "Dune"))
        server.on("GET /items/2", body = Json.item("2", "movie", "Flow"))
        server.on("GET /items/1/artwork/poster", 404)
        server.on("GET /items/1/artwork/backdrop", 404)
        server.on("GET /items/2/artwork/poster", 404)
        server.on("GET /items/2/artwork/backdrop", 404)
    }

    private suspend fun filed(target: DownloadTarget = DownloadTarget(itemId = "1")) {
        quietMeta()
        val plan = downloads.plan(api, "1", target, "Dune", DownloadQuality.original)
        downloads.add(api, plan)
    }

    @Test
    fun aTitleIsClaimedWaitedForFetchedAndLetGo() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        assertEquals(1, scheduled)
        assertEquals(TitleState.Pending, store.title("1")?.state)
        assertTrue("its metadata is kept", store.meta("item-1").exists())

        server.on("POST /profiles/1/downloads/batch", body = batch(claimed("1", "5", "converting")))
        server.on(
            "GET /profiles/1/downloads/5",
            body =
                download(
                    "5",
                    "1",
                    subtitles =
                        """[{"trackId":"8","isForced":true,"url":"/api/v2/downloads/5/subtitles/8.vtt","language":"en"}]""",
                ),
        )
        server.on("DELETE /profiles/1/downloads/5", 204)
        assertFalse(downloads.run(api, active = { "1" }, kids = emptySet(), unmetered = true))

        val title = store.title("1")!!
        assertEquals(TitleState.OnDevice, title.state)
        assertEquals("1.mp4", title.file)
        assertTrue(store.media("1.mp4").exists())
        assertEquals(
            listOf(
                "http://media.example.com:8477/api/v2/downloads/5/file",
                "http://media.example.com:8477/api/v2/downloads/5/subtitles/8.vtt",
            ),
            fetched,
        )
        assertTrue(title.subtitles.single().forced)
        assertEquals(1, server.called("GET /profiles/1/downloads/5").size)
        assertEquals(1, server.called("DELETE /profiles/1/downloads/5").size)
        assertTrue(server.called("POST /profiles/1/downloads").isEmpty())
        assertTrue(
            server
                .called("POST /profiles/1/downloads/batch")
                .single()
                .body
                .contains("\"quality\":\"original\""),
        )
        assertNull("its claim is let go of", store.title("1")?.claim)
        assertTrue(store.local("1", "1") != null)
    }

    @Test
    fun aRequestIsScheduledBeforeItsMetadataIsFetched() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        var kept: Boolean? = null
        whenScheduled = { kept = store.meta("item-1").exists() }
        filed()
        assertEquals(false, kept)
        assertTrue(store.meta("item-1").exists())
    }

    @Test
    fun aSeasonAndItsShowAreFetchedOnceForAllTheirEpisodes() = runBlocking {
        val episodes = (11..13).map { "$it" }
        val titles = episodes.map { title(it, "episode") }.toTypedArray()
        server.on("POST /profiles/1/downloads/resolve", body = resolution(*titles))
        server.on("GET /items/9", body = Json.item("9", "show"))
        server.on("GET /items/10", body = Json.item("10", "season", parent = 9))
        for (id in episodes) server.on(
            "GET /items/$id",
            body = Json.item(id, "episode", parent = 10),
        )
        for (id in 9L..13L) {
            server.on("GET /items/$id/artwork/poster", 404)
            server.on("GET /items/$id/artwork/backdrop", 404)
        }
        val plan =
            downloads.plan(api, "1", DownloadTarget(itemId = "9"), "Show", DownloadQuality.original)
        downloads.add(api, plan)

        assertEquals(1, server.called("GET /items/9").size)
        assertEquals(1, server.called("GET /items/10").size)
        assertEquals(1, server.called("GET /items/10/artwork/poster").size)
        for (id in episodes) assertTrue(store.meta("item-$id").exists())
        assertTrue(store.meta("item-9").exists())
    }

    @Test
    fun syncFetchesMetadataThatFailedToArriveBefore() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        server.on("GET /collections/9", 404)
        server.on("GET /items/1", 404)
        val target = DownloadTarget(collectionId = "9")
        downloads.add(api, downloads.plan(api, "1", target, "Pack", DownloadQuality.original))
        assertFalse(store.meta("item-1").exists())

        quietMeta()
        server.on("POST /profiles/1/downloads/sync", body = """{"keep":[1],"remove":[]}""")
        downloads.sync(api, "1")
        assertTrue(store.meta("item-1").exists())
    }

    @Test
    fun onAMeteredNetworkWithWifiOnlyTitlesAreClaimedAndWaitForWifi() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.on("POST /profiles/1/downloads/batch", body = batch(claimed("1", "5", "converting")))
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = false))
        assertEquals(TitleState.WaitingForWifi, store.title("1")?.state)
        assertEquals(Claim("5", "1"), store.title("1")?.claim)
        assertTrue("nothing transfers", fetched.isEmpty())
    }

    @Test
    fun dataSaverOnAMeteredNetworkHoldsTransfersWhateverTheWifiRule() = runBlocking {
        store.setDevice(store.device.copy(wifiOnly = false))
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.on("POST /profiles/1/downloads/batch", body = batch(claimed("1", "5", "converting")))
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = false, dataSaver = true))
        assertEquals(TitleState.WaitingForDataSaver, store.title("1")?.state)
        assertTrue("nothing transfers", fetched.isEmpty())

        server.on("GET /profiles/1/downloads/5", body = download("5", "1"))
        server.on("DELETE /profiles/1/downloads/5", 204)
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true, dataSaver = true))
        assertEquals(TitleState.OnDevice, store.title("1")?.state)
        assertEquals("claimed once", 1, server.called("POST /profiles/1/downloads/batch").size)
    }

    @Test
    fun downloadsWaitOnAMeteredNetworkForWifiOnlyOrDataSaver() {
        for (wifiOnly in listOf(true, false)) for (dataSaver in listOf(true, false)) assertNull(
            Downloads.waitingFor(wifiOnly, metered = false, dataSaver),
        )
        assertEquals(TitleState.WaitingForWifi, Downloads.waitingFor(true, true, false))
        assertEquals(TitleState.WaitingForWifi, Downloads.waitingFor(true, true, true))
        assertEquals(TitleState.WaitingForDataSaver, Downloads.waitingFor(false, true, true))
        assertNull(Downloads.waitingFor(false, true, false))
    }

    @Test
    fun everyTitleOfARequestIsClaimedInOneBatchBeforeTheFirstTransfer() = runBlocking {
        server.on(
            "POST /profiles/1/downloads/resolve",
            body = resolution(title("1"), title("2"), title("3")),
        )
        filed(DownloadTarget(collectionId = "9"))
        server.on(
            "POST /profiles/1/downloads/batch",
            body =
                batch(
                    claimed("1", "5", "converting"),
                    claimed("2", "6", "queued"),
                    claimed("3", "7"),
                ),
        )
        for ((item, id) in listOf("1" to "5", "2" to "6", "3" to "7")) {
            server.on("GET /profiles/1/downloads/$id", body = download(id, item))
            server.on("DELETE /profiles/1/downloads/$id", 204)
        }
        var claimedBefore: List<List<String>>? = null
        midFetch = {
            if (claimedBefore == null)
                claimedBefore = server.called("POST /profiles/1/downloads/batch").map(::asked)
        }
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true))

        assertEquals(listOf(listOf("1", "2", "3")), claimedBefore)
        assertEquals(1, server.called("POST /profiles/1/downloads/batch").size)
        assertTrue(server.called("POST /profiles/1/downloads").isEmpty())
        for (id in 1..3) assertEquals(TitleState.OnDevice, store.title("$id")?.state)
        assertEquals(
            (5..7).map { "http://media.example.com:8477/api/v2/downloads/$it/file" },
            fetched,
        )
    }

    @Test
    fun aTitleTheBatchCouldNotClaimFailsAloneAsItsOwnClaimWould() = runBlocking {
        server.on(
            "POST /profiles/1/downloads/resolve",
            body = resolution(title("1"), title("2"), title("3")),
        )
        filed(DownloadTarget(collectionId = "9"))
        server.on(
            "POST /profiles/1/downloads/batch",
            body = batch(claimed("1", "5"), refused("2", "conflict"), refused("3", "not_found")),
        )
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = false))
        assertEquals(TitleState.WaitingForWifi, store.title("1")?.state)
        assertEquals(TitleState.Failed, store.title("2")?.state)
        assertEquals("Can’t be kept on this device.", store.title("2")?.error)
        assertEquals(TitleState.Failed, store.title("3")?.state)
        assertEquals("No longer available.", store.title("3")?.error)
    }

    @Test
    fun aClaimGoneBeforeItsTurnIsClaimedAgainOnce() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.on("POST /profiles/1/downloads/batch", body = batch(claimed("1", "5", "converting")))
        server.error("GET /profiles/1/downloads/5", 404, "not_found")
        server.on("POST /profiles/1/downloads", 201, download("6", "1"))
        server.on("DELETE /profiles/1/downloads/6", 204)
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertEquals(TitleState.OnDevice, store.title("1")?.state)
        assertEquals(1, server.called("GET /profiles/1/downloads/5").size)
        assertEquals(1, server.called("POST /profiles/1/downloads").size)
        assertEquals(listOf("http://media.example.com:8477/api/v2/downloads/6/file"), fetched)
        assertTrue(
            "a lost claim is not let go of",
            server.called("DELETE /profiles/1/downloads/5").isEmpty(),
        )
    }

    @Test
    fun moreTitlesThanTheServerTakesAtOnceAreClaimedInSeveralBatches() = runBlocking {
        val many =
            (1..5001)
                .map { "$it" }
                .map {
                    DownloadTitle(
                        it,
                        DownloadTitle.Kind.song,
                        "1",
                        available = true,
                        watched = false,
                        positionMs = 0,
                        sizeBytes = 1,
                    )
                }
        store.put("1", DownloadTarget(playlistId = "3"), "Mix", DownloadQuality.original, many)
        server.on("POST /profiles/1/downloads/batch", body = batch())
        downloads.run(api, { "1" }, emptySet(), unmetered = false)
        val batches = server.called("POST /profiles/1/downloads/batch").map(::asked)
        assertEquals(listOf(5000, 1), batches.map { it.size })
        assertEquals((1..5001).map { "$it" }, batches.flatten())
    }

    @Test
    fun aRemovedRequestLetsGoOfTheClaimsItNeverFetched() = runBlocking {
        server.on(
            "POST /profiles/1/downloads/resolve",
            body = resolution(title("1"), title("2"), title("3")),
        )
        filed(DownloadTarget(collectionId = "9"))
        server.on(
            "POST /profiles/1/downloads/batch",
            body =
                batch(
                    claimed("1", "5", "converting"),
                    claimed("2", "6", "queued"),
                    claimed("3", "7"),
                ),
        )
        downloads.run(api, { "1" }, emptySet(), unmetered = false)
        store.flush()
        assertEquals(
            "a restart keeps its claims",
            Claim("6", "1"),
            DownloadStore(store.dir).title("2")?.claim,
        )

        for (id in 5L..7L) server.on("DELETE /profiles/1/downloads/$id", 204)
        val before = scheduled
        downloads.remove("1", DownloadTarget(collectionId = "9"))
        assertTrue("a run is due to let them go", scheduled > before)
        downloads.run(api, { "1" }, emptySet(), unmetered = false)
        for (id in 5L..7L) assertEquals(1, server.called("DELETE /profiles/1/downloads/$id").size)
        assertTrue(store.state.releases.isEmpty())
        assertEquals("claimed once", 1, server.called("POST /profiles/1/downloads/batch").size)
    }

    @Test
    fun anotherAdultsTitleWaitsUntilTheyAreActiveAndAKidsGoesNow() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        assertFalse(downloads.run(api, active = { "2" }, kids = emptySet(), unmetered = true))
        assertEquals(TitleState.Pending, store.title("1")?.state)

        server.on("POST /profiles/1/downloads", 201, download("5", "1"))
        server.on("DELETE /profiles/1/downloads/5", 204)
        downloads.run(api, active = { "2" }, kids = setOf("1"), unmetered = true)
        assertEquals(TitleState.OnDevice, store.title("1")?.state)
    }

    private fun asked(profileId: String, itemId: String) {
        store.put(
            profileId,
            DownloadTarget(itemId = itemId),
            "T$itemId",
            DownloadQuality.original,
            listOf(
                DownloadTitle(
                    itemId,
                    DownloadTitle.Kind.movie,
                    "1",
                    available = true,
                    watched = false,
                    positionMs = 0,
                    sizeBytes = 10,
                ),
            ),
        )
    }

    @Test
    fun aTitleIsClaimedAsWhoeverIsActiveWhenItsTurnComes() = runBlocking {
        asked("1", "1")
        asked("2", "2")
        asked("1", "3")
        server.on("POST /profiles/1/downloads", 201, download("5", "1"))
        server.on("DELETE /profiles/1/downloads/5", 204)
        server.on("POST /profiles/2/downloads", 201, download("6", "2"))
        server.on("DELETE /profiles/2/downloads/6", 204)
        // Profile 2 becomes active once the first file is on the device.
        val active = { if (fetched.isEmpty()) "1" else "2" }
        assertFalse(downloads.run(api, active, emptySet(), unmetered = true))

        assertEquals(TitleState.OnDevice, store.title("1")?.state)
        assertEquals(TitleState.OnDevice, store.title("2")?.state)
        assertEquals("another adult's title waits", TitleState.Pending, store.title("3")?.state)
        assertEquals(1, server.called("POST /profiles/1/downloads").size)
        assertEquals(1, server.called("POST /profiles/2/downloads").size)
    }

    @Test
    fun withAKidActiveAnotherKidsTitleWaitsAndDoesNotFail() = runBlocking {
        asked("1", "1")
        asked("2", "2")
        server.error("POST /profiles/2/downloads", 403, "forbidden")
        server.on("POST /profiles/1/downloads", 201, download("5", "1"))
        server.on("DELETE /profiles/1/downloads/5", 204)
        assertFalse(downloads.run(api, { "1" }, kids = setOf("1", "2"), unmetered = true))

        assertEquals(TitleState.OnDevice, store.title("1")?.state)
        assertEquals(TitleState.Pending, store.title("2")?.state)
        assertTrue(server.called("POST /profiles/2/downloads").isEmpty())
    }

    @Test
    fun aLostConnectionLeavesTheClaimToResumeAndAsksToRunAgain() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.on("POST /profiles/1/downloads", 201, download("5", "1"))
        failFetch = IOException("gone")
        assertTrue(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertEquals(TitleState.Pending, store.title("1")?.state)
        assertEquals("kept to resume against", Claim("5", "1"), store.title("1")?.claim)
        assertTrue(server.called("DELETE /profiles/1/downloads/5").isEmpty())
    }

    @Test
    fun anotherProfileBecomingActiveDuringAConversionLeavesTheTitleWaitingWithItsClaim() =
        runBlocking {
            server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
            filed()
            server.on(
                "POST /profiles/1/downloads/batch",
                body = batch(claimed("1", "9", "converting")),
            )
            server.on("GET /profiles/1/downloads/9", body = download("9", "1", "converting"))
            server.error("POST /profiles/1/downloads", 404, "not_found")
            server.on("DELETE /profiles/1/downloads/9", 204)
            val polls = { server.called("GET /profiles/1/downloads/9").size }
            server.before = {
                if (it == "GET /profiles/1/downloads/9" && polls() > 1)
                    server.error("GET /profiles/1/downloads/9", 404, "not_found")
            }
            downloads.run(api, { if (polls() > 1) "2" else "1" }, emptySet(), unmetered = true)
            assertEquals(TitleState.Pending, store.title("1")?.state)
            assertEquals(
                "kept for when its profile is back",
                Claim("9", "1"),
                store.title("1")?.claim,
            )
            assertTrue(server.called("DELETE /profiles/1/downloads/9").isEmpty())
        }

    @Test
    fun aConnectionLostOnTheSubtitlesFetchesOnlyTheSubtitlesAgain() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        val subtitles =
            """[{"trackId":"8","isForced":false,"url":"/api/v2/downloads/5/subtitles/8.vtt"}]"""
        server.on("POST /profiles/1/downloads", 201, download("5", "1", subtitles = subtitles))
        server.on("GET /profiles/1/downloads/5", body = download("5", "1", subtitles = subtitles))
        server.on("DELETE /profiles/1/downloads/5", 204)
        var lost = true
        midFetch = {
            if (lost && fetched.size == 1) {
                lost = false
                throw IOException("gone")
            }
        }
        assertTrue(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertEquals(TitleState.Pending, store.title("1")?.state)
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertEquals(TitleState.OnDevice, store.title("1")?.state)
        assertEquals(
            "the whole file is not fetched again",
            1,
            fetched.count { it.endsWith("/downloads/5/file") },
        )
    }

    @Test
    fun aTitleRemovedMidTransferStopsTheTransferAndLetsGoOfItsClaim() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.on("POST /profiles/1/downloads", 201, download("5", "1"))
        server.on("DELETE /profiles/1/downloads/5", 204)
        midFetch = { downloads.remove("1", DownloadTarget(itemId = "1")) }
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertTrue("the transfer stopped", fetched.isEmpty())
        assertNull(store.title("1"))
        assertEquals(1, server.called("DELETE /profiles/1/downloads/5").size)
    }

    @Test
    fun aFullDiskWaitsForRoomInsteadOfRetrying() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.on("POST /profiles/1/downloads", 201, download("5", "1"))
        midFetch = {
            free = 0
            throw IOException("No space left on device")
        }
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertEquals(TitleState.WaitingForSpace, store.title("1")?.state)
        assertEquals(1, server.called("POST /profiles/1/downloads").size)
        assertTrue(server.called("DELETE /profiles/1/downloads/5").isEmpty())
    }

    @Test
    fun aTitleTheDeviceCannotKeepFailsAndRetries() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.error("POST /profiles/1/downloads", 409, "conflict")
        downloads.run(api, { "1" }, emptySet(), unmetered = true)
        assertEquals(TitleState.Failed, store.title("1")?.state)
        assertEquals("Can’t be kept on this device.", store.title("1")?.error)
        downloads.retry("1")
        assertEquals(TitleState.Pending, store.title("1")?.state)
    }

    @Test
    fun aDeviceAtWhosWatchingStopsAndKeepsItsPlace() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1"), title("2")))
        filed(DownloadTarget(collectionId = "9"))
        server.error("POST /profiles/1/downloads", 403, "device_locked")
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertEquals(1, server.called("POST /profiles/1/downloads").size)
        assertEquals(TitleState.Pending, store.title("1")?.state)
    }

    @Test
    fun aRevokedDeviceLosesEverything() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.error("POST /profiles/1/downloads", 401, "unauthorized")
        downloads.run(api, { "1" }, emptySet(), unmetered = true)
        assertTrue(store.state.requests.isEmpty())
        assertNull(store.title("1"))
    }

    /** A proxy or auth layer in front of the server may answer 401 with a body of its own. */
    @Test
    fun a401TheServerDidNotSendKeepsEveryDownload() = runBlocking {
        val proxied = "<html>401 Authorization Required</html>"
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1"), title("2")))
        filed(DownloadTarget(collectionId = "9"))
        store.setTitle("1") { it.copy(state = TitleState.OnDevice, file = "1.mp4") }
        store.media("1.mp4").apply { parentFile?.mkdirs() }.writeText("media")

        server.on("POST /profiles/1/downloads", 401, proxied)
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertEquals(TitleState.Pending, store.title("2")?.state)

        server.on("POST /profiles/1/downloads", 201, download("5", "2"))
        failFetch = ApiError(401, "internal", "The server answered 401.")
        assertFalse(downloads.run(api, { "1" }, emptySet(), unmetered = true))
        assertEquals(TitleState.Pending, store.title("2")?.state)

        store.record("1", "1", 300, watched = false, atMs = 1_000)
        server.on("PUT /profiles/1/watch-state/1", 401, proxied)
        downloads.sync(api, "1")
        server.on("PUT /profiles/1/watch-state/1", body = Json.watch(1, position = 300))
        server.on("POST /profiles/1/downloads/sync", 401, proxied)
        downloads.sync(api, "1")
        assertEquals(TitleState.OnDevice, store.title("1")?.state)
        assertTrue(store.media("1.mp4").exists())
        assertEquals(1, store.requests("1").size)
    }

    /** A proxy in front of the server may answer 404 with a body of its own. */
    @Test
    fun a404TheServerDidNotSendKeepsTheProfilesRequestsDownloadsAndChanges() = runBlocking {
        val proxied = "<html>404 Not Found</html>"
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed(DownloadTarget(collectionId = "9"))
        store.setTitle("1") { it.copy(state = TitleState.OnDevice, file = "1.mp4") }
        store.record("1", "1", 300, watched = false, atMs = 1_000)

        server.on("PUT /profiles/1/watch-state/1", 404, proxied)
        downloads.sync(api, "1")
        assertEquals(listOf("1"), store.unsynced("1").map { it.itemId })

        server.on("PUT /profiles/1/watch-state/1", body = Json.watch(1, position = 300))
        server.on("POST /profiles/1/downloads/sync", 404, proxied)
        downloads.sync(api, "1")
        assertEquals(TitleState.OnDevice, store.title("1")?.state)

        server.on("POST /profiles/1/downloads/sync", body = """{"keep":[1],"remove":[]}""")
        server.on("POST /profiles/1/downloads/resolve", 404, proxied)
        downloads.sync(api, "1")
        assertEquals(1, store.requests("1").size)
        assertEquals(TitleState.OnDevice, store.title("1")?.state)
    }

    @Test
    fun aBigPlanSaysSoAndDontAskAgainIsKept() = runBlocking {
        val many = (1..51).map { title("$it", size = 1) }.toTypedArray()
        server.on("POST /profiles/1/downloads/resolve", body = resolution(*many))
        val plan =
            downloads.plan(
                api,
                "1",
                DownloadTarget(playlistId = "3"),
                "Mix",
                DownloadQuality.original,
            )
        assertEquals(51, plan.count)
        assertEquals(51L, plan.bytes)
        assertTrue(plan.big)
        server.on("GET /profiles/1/playlists/3", 404)
        for (id in 1L..51L) server.on("GET /items/$id", 404)
        downloads.add(api, plan, dontAskAgain = true)
        assertFalse(
            downloads
                .plan(api, "1", DownloadTarget(playlistId = "4"), "Mix", DownloadQuality.original)
                .big,
        )
    }

    @Test
    fun syncSendsOfflineChangesOldestFirstWithWhenTheyHappened() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1"), title("2")))
        filed(DownloadTarget(collectionId = "9"))
        store.record("1", "2", 500, watched = false, atMs = 2_000)
        store.record("1", "1", 300, watched = false, atMs = 1_000)
        server.on("PUT /profiles/1/watch-state/1", body = Json.watch(1, position = 300))
        server.on("PUT /profiles/1/watch-state/2", body = Json.watch(2, watched = true))
        server.on("POST /profiles/1/downloads/sync", body = """{"keep":[1],"remove":[2]}""")
        server.on("GET /collections/9", 404)
        // A refresh that fails leaves what sync dropped.
        server.on("POST /profiles/1/downloads/resolve", 500, "{}")
        downloads.sync(api, "1")

        val puts = server.calls.filter { it.route.startsWith("PUT /profiles/1/watch-state/") }
        assertEquals(
            listOf("PUT /profiles/1/watch-state/1", "PUT /profiles/1/watch-state/2"),
            puts.map { it.route },
        )
        assertTrue(puts[0].body.contains("\"at\":\"1970-01-01T00:00:01Z\""))
        assertTrue(store.unsynced("1").isEmpty())
        assertNull("the server no longer lets it be seen", store.title("2"))
        assertEquals(listOf("1"), store.requests("1").single().titles)
    }

    @Test
    fun syncBringsAStandingRequestUpToDate() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed(DownloadTarget(collectionId = "9"))
        server.on("POST /profiles/1/downloads/sync", body = """{"keep":[1],"remove":[]}""")
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1"), title("2")))
        downloads.sync(api, "1")
        assertEquals(listOf("1", "2"), store.requests("1").single().titles)
        assertTrue(store.meta("item-2").exists())
    }

    @Test
    fun aProfileTheServerNoLongerHasLosesItsDownloads() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        server.error("POST /profiles/1/downloads/sync", 404, "not_found")
        downloads.sync(api, "1")
        assertTrue(store.requests("1").isEmpty())
    }

    @Test
    fun aTargetGoneFromTheServerTakesItsRequest() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed(DownloadTarget(collectionId = "9"))
        server.on("POST /profiles/1/downloads/sync", body = """{"keep":[1],"remove":[]}""")
        server.error("POST /profiles/1/downloads/resolve", 404, "not_found")
        downloads.sync(api, "1")
        assertTrue(store.requests("1").isEmpty())
    }

    @Test
    fun aSyncThatFailsStillFetchesWhatWaits() = runBlocking {
        server.on("POST /profiles/1/downloads/resolve", body = resolution(title("1")))
        filed()
        val before = scheduled
        store.record("1", "1", 300, watched = false, atMs = 1_000)
        server.on("PUT /profiles/1/watch-state/1", 503, "{}")
        downloads.sync(api, "1")
        assertEquals(before + 1, scheduled)

        server.on("PUT /profiles/1/watch-state/1", body = Json.watch(1, position = 300))
        server.on("POST /profiles/1/downloads/sync", 500, "{}")
        downloads.sync(api, "1")
        assertEquals(before + 2, scheduled)
    }
}
