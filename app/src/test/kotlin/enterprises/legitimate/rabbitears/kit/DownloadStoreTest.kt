package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.DownloadTitle
import enterprises.legitimate.rabbitears.api.models.DownloadTitle.Kind
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStoreTest {
    private val dir = Files.createTempDirectory("downloads").toFile()
    private val store = DownloadStore(dir)
    private val q = DownloadQuality.original

    private fun title(
        id: String,
        kind: Kind = Kind.song,
        library: String = "1",
        size: Long = 100,
        watched: Boolean = false,
    ) =
        DownloadTitle(
            id,
            kind,
            library,
            available = true,
            watched = watched,
            positionMs = 0,
            sizeBytes = size,
        )

    private fun item(id: String) = DownloadTarget(itemId = id)

    private fun refs(itemId: String) = store.state.requests.count { itemId in it.titles }

    /** Stores the title's media as if fetched. */
    private fun fetched(id: String) {
        store.media("$id.m4a").apply { parentFile!!.mkdirs() }.writeText("audio")
        store.setTitle(id) { it.copy(file = "$id.m4a", state = TitleState.OnDevice) }
    }

    @Test
    fun aTitleIsBadgedOnTheDeviceAndAContainerOnceAllOfItsRequestIs() {
        val mix = DownloadTarget(playlistId = "5")
        val set = DownloadTarget(collectionId = "9")
        store.put("1", item("30"), "Album", q, listOf(title("7"), title("8")))
        store.put("1", mix, "Mix", q, listOf(title("7")))
        store.put("1", set, "Dune", q, listOf(title("8")))
        assertFalse(store.badged("1", item("30")))
        assertFalse(store.badged("1", item("7")))

        fetched("7")
        assertTrue(store.badged("1", item("7")))
        assertTrue(store.badged("1", mix))
        assertFalse(store.badged("1", item("30")))
        assertFalse(store.badged("1", set))
        assertFalse(store.badged("2", item("7")))
        assertFalse(store.badged("1", DownloadTarget(playlistId = "6")))

        fetched("8")
        assertTrue(store.badged("1", item("30")))
        assertTrue(store.badged("1", set))
    }

    @Test
    fun aTitleInTwoRequestsAndTwoProfilesIsStoredOnceAndGoesWithTheLast() {
        val playlist =
            store.put(
                "1",
                DownloadTarget(playlistId = "5"),
                "Mix",
                q,
                listOf(title("7"), title("8")),
            )
        val album = store.put("1", item("30"), "Album", q, listOf(title("7")))
        val kid = store.put("2", item("7"), "Song", q, listOf(title("7")))
        fetched("7")
        assertEquals(1, store.state.titles.keys.count { it == "7" })
        assertEquals(3, refs("7"))

        store.remove(playlist.id)
        store.remove(album.id)
        store.flush()
        assertTrue(store.media("7.m4a").exists())
        assertNull(store.title("8"))
        assertEquals(TitleState.OnDevice, store.onDevice("2", "7")?.state)
        assertNull(store.onDevice("1", "7"))

        store.remove(kid.id)
        store.flush()
        assertEquals(0, refs("7"))
        assertNull(store.title("7"))
        assertFalse(store.media("7.m4a").exists())
    }

    @Test
    fun aStandingRequestTakesWhatArrivesAndDropsWhatLeaves() {
        val show = DownloadTarget(itemId = "10")
        val episodes = { ids: List<String> -> ids.map { title(it, Kind.episode) } }
        val first = store.put("1", show, "Severance", q, episodes(listOf("101", "102")))
        assertTrue(first.container)
        fetched("101")
        val again = store.put("1", show, "Severance", q, episodes(listOf("102", "103")))
        assertEquals(first.id, again.id)
        assertEquals(listOf("102", "103"), again.titles)
        assertNull(store.title("101"))
        store.flush()
        assertFalse(store.media("101.m4a").exists())
        assertEquals(TitleState.Pending, store.title("103")?.state)
        assertEquals(1, store.requests("1").size)
    }

    @Test
    fun aSingleTitleIsNoContainer() {
        assertFalse(store.put("1", item("1"), "Dune", q, listOf(title("1", Kind.movie))).container)
    }

    @Test
    fun keepAfterWatchedIsPerLibraryAndAContainerLeavesWhatItRemoves() {
        val tv = "2"
        val otherTv = "3"
        store.setSettings("1", ProfileDownloadSettings(keep = mapOf(otherTv to true)))
        val show =
            store.put(
                "1",
                item("10"),
                "Show",
                q,
                listOf(
                    title("101", Kind.episode, tv, watched = true),
                    title("102", Kind.episode, tv),
                ),
            )
        assertEquals("a watched episode is not downloaded", listOf("102"), show.titles)
        val kept =
            store.put(
                "1",
                item("20"),
                "Kept",
                q,
                listOf(title("201", Kind.episode, otherTv, watched = true)),
            )
        assertEquals(listOf("201"), kept.titles)

        fetched("102")
        store.record("1", "102", 0, watched = true, atMs = 5)
        assertNull("watched, it is removed", store.title("102"))
        store.flush()
        assertFalse(store.media("102.m4a").exists())

        store.record("1", "201", 0, watched = true, atMs = 5)
        assertTrue(store.title("201") != null)
    }

    @Test
    fun keepDefaultsFollowTheTitleKind() {
        assertTrue(store.keeps("1", "1", Kind.movie))
        assertFalse(store.keeps("1", "1", Kind.episode))
        assertFalse(store.keeps("1", "1", Kind.video))
        store.setSettings("1", ProfileDownloadSettings(keep = mapOf("1" to false)))
        assertTrue("music always keeps", store.keeps("1", "1", Kind.song))
        assertFalse(store.keeps("1", "1", Kind.movie))
    }

    @Test
    fun aWatchedTitleAskedForByItselfStays() {
        val episode =
            store.put("1", item("101"), "E1", q, listOf(title("101", Kind.episode, watched = true)))
        assertEquals(listOf("101"), episode.titles)
        store.record("1", "101", 0, watched = true, atMs = 5)
        assertTrue(store.title("101") != null)
    }

    @Test
    fun aBigRequestIsAskedAboutUntilTheProfileSaysNotTo() {
        store.setDevice(DeviceDownloadSettings(allowanceBytes = 1000))
        assertFalse(store.isBig("1", 50, 200))
        assertTrue(store.isBig("1", 51, 0))
        assertTrue(store.isBig("1", 1, 201))
        store.setSettings("1", store.settings("1").copy(dontAskBig = true))
        assertFalse(store.isBig("1", 51, 201))
        assertTrue("another profile still asks", store.isBig("2", 51, 0))
    }

    @Test
    fun newBytesCountOnlyWhatIsNotStored() {
        store.put("1", item("1"), "A", q, listOf(title("1", size = 300)))
        assertEquals(50, store.newBytes(listOf(title("1", size = 300), title("2", size = 50))))
    }

    @Test
    fun pastTheAllowanceTitlesWaitForSpaceAndOneThatFitsGoesFirst() {
        store.setDevice(DeviceDownloadSettings(allowanceBytes = 1000))
        store.put(
            "1",
            DownloadTarget(playlistId = "1"),
            "P",
            q,
            listOf(title("1", size = 700), title("2", size = 600), title("3", size = 200)),
        )
        store.setTitle("1") { it.copy(state = TitleState.OnDevice) }
        assertEquals("3", store.next()?.itemId)
        assertEquals(TitleState.WaitingForSpace, store.title("2")?.state)
        store.setTitle("3") { it.copy(state = TitleState.OnDevice) }
        assertNull(store.next())

        store.setDevice(DeviceDownloadSettings(allowanceBytes = 2000))
        assertEquals("2", store.next()?.itemId)
    }

    @Test
    fun aTitleThatWouldFillTheDiskWaitsForSpaceWhateverTheAllowance() {
        var free = DownloadStore.RESERVE_BYTES + 150
        val store = DownloadStore(dir, free = { free })
        store.put("1", item("1"), "A", q, listOf(title("1", size = 200)))
        assertNull(store.next())
        assertEquals(TitleState.WaitingForSpace, store.title("1")?.state)

        free = DownloadStore.RESERVE_BYTES + 1000
        assertEquals("1", store.next()?.itemId)
    }

    @Test
    fun aPartialFileOnlyNeedsRoomForWhatIsLeft() {
        val store = DownloadStore(dir, free = { DownloadStore.RESERVE_BYTES + 50 })
        store.put("1", item("1"), "A", q, listOf(title("1", size = 200)))
        store.setTitle("1") { it.copy(file = "1.m4a") }
        store.media("1.m4a.part").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(180))
        assertEquals("1", store.next()?.itemId)
    }

    @Test
    fun aFailedTitleWaitsForRetry() {
        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.setTitle("1") { it.copy(state = TitleState.Failed, error = "x") }
        assertNull(store.next())
        store.retry("1")
        assertEquals("1", store.next()?.itemId)
    }

    @Test
    fun signingOutWipesEveryFileAndRequest() {
        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.put("2", item("2"), "B", q, listOf(title("2")))
        fetched("1")
        store.meta("item-1").apply { parentFile!!.mkdirs() }.writeText("{}")
        store.wipe()
        assertTrue(store.state.requests.isEmpty())
        assertTrue(store.state.titles.isEmpty())
        store.flush()
        assertFalse(store.media("1.m4a").exists())
        assertFalse(store.meta("item-1").exists())
        assertTrue(DownloadStore(dir).state.requests.isEmpty())
    }

    @Test
    fun signingOutLeavesDeletingTheFilesToTheWriter() {
        val hold = CountDownLatch(1)
        val writer = Executors.newSingleThreadExecutor()
        val held = DownloadStore(dir, writer = writer)
        held.put("1", item("1"), "A", q, listOf(title("1")))
        held.media("1.m4a").apply { parentFile!!.mkdirs() }.writeText("audio")
        held.setTitle("1") { it.copy(file = "1.m4a", state = TitleState.OnDevice) }
        writer.execute { hold.await() }
        try {
            val wiping = thread { held.wipe() }
            wiping.join(1_000)
            assertFalse("the caller waited on the disk", wiping.isAlive)
            assertTrue(held.state.titles.isEmpty())
        } finally {
            hold.countDown()
        }
        held.flush()
        assertFalse(held.media("1.m4a").exists())
        assertTrue(DownloadStore(dir).state.requests.isEmpty())
        writer.shutdown()
    }

    @Test
    fun aProfileTheServerNoLongerKnowsLosesOnlyItsOwn() {
        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.put("2", item("1"), "A", q, listOf(title("1")))
        store.put("2", item("2"), "B", q, listOf(title("2")))
        store.wipe("2")
        assertEquals(1, refs("1"))
        assertNull(store.title("2"))
    }

    @Test
    fun syncDropsWhatTheProfileMayNoLongerSee() {
        store.put("1", DownloadTarget(playlistId = "1"), "P", q, listOf(title("1"), title("2")))
        store.drop("1", listOf("2"))
        assertEquals(listOf("1"), store.requests("1").single().titles)
        assertNull(store.title("2"))
    }

    @Test
    fun stateSurvivesARestart() {
        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.setSettings("1", ProfileDownloadSettings(preferLocal = false))
        store.flush()
        val again = DownloadStore(dir)
        assertEquals(listOf("1"), again.requests("1").single().titles)
        assertFalse(again.settings("1").preferLocal)
    }

    @Test
    fun changesAreWrittenByTheWriterAndSeveralShareOneWrite() {
        val queued = mutableListOf<Runnable>()
        val store = DownloadStore(dir, writer = { queued += it })
        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.setSettings("1", ProfileDownloadSettings(preferLocal = false))
        assertFalse("the caller's thread writes nothing", File(dir, "state.json").exists())
        assertEquals(1, queued.size)

        queued.forEach { it.run() }
        val again = DownloadStore(dir)
        assertEquals(listOf("1"), again.requests("1").single().titles)
        assertFalse("the write holds the latest change", again.settings("1").preferLocal)
    }

    @Test
    fun aChangeThatChangesNothingWritesNothing() {
        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.flush()
        val written = File(dir, "state.json").apply { setLastModified(1_000) }

        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.setDevice(store.device)
        store.setTitle("1") { it }
        store.flush()
        assertEquals(1_000L, written.lastModified())
    }

    @Test
    fun progressStaysInMemoryButANewEtagIsWrittenAtOnce() {
        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.setTitle("1") { it.copy(file = "1.m4a", etag = "\"e1\"") }
        store.flush()
        val written = File(dir, "state.json").apply { setLastModified(1_000) }

        store.progress("1", 500, "\"e1\"")
        store.flush()
        assertEquals(500L, store.title("1")?.receivedBytes)
        assertEquals("a megabyte of progress writes nothing", 1_000L, written.lastModified())

        store.progress("1", 600, "\"e2\"")
        store.flush()
        assertEquals("\"e2\"", DownloadStore(dir).title("1")?.etag)
    }

    @Test
    fun aTitleLeftDownloadingByADeadProcessResumesWhereItStopped() {
        store.put("1", item("1"), "A", q, listOf(title("1")))
        store.setTitle("1") {
            it.copy(
                state = TitleState.Downloading,
                file = "1.m4a",
                etag = "\"e1\"",
                sizeBytes = 100,
                receivedBytes = 40,
            )
        }
        store.flush()
        val again = DownloadStore(dir)
        val resumed = again.title("1")!!
        assertEquals(TitleState.Pending, resumed.state)
        assertEquals("1.m4a", resumed.file)
        assertEquals("\"e1\"", resumed.etag)
        assertEquals(40L, resumed.receivedBytes)
        assertEquals("1", again.next()?.itemId)
    }

    @Test
    fun localChangesWaitOldestFirstAndALaterOneOutlivesTheSync() {
        store.put(
            "1",
            DownloadTarget(playlistId = "1"),
            "P",
            q,
            listOf(title("1", Kind.movie), title("2", Kind.movie)),
        )
        store.record("1", "2", 500, watched = false, atMs = 20)
        store.record("1", "1", 300, watched = false, atMs = 10)
        assertEquals(listOf("1", "2"), store.unsynced("1").map { it.itemId })

        val sent = store.unsynced("1").first()
        store.record("1", "1", 900, watched = false, atMs = 30)
        store.synced("1", sent.copy(synced = true), sentAtMs = sent.atMs)
        assertEquals("the later change still waits", 900L, store.watch("1", "1")?.positionMs)
        assertFalse(store.watch("1", "1")!!.synced)

        val server = LocalWatch("2", 0, watched = true, atMs = 40, synced = true)
        store.synced("1", server, sentAtMs = 20)
        assertEquals(true, store.watch("1", "2")?.watched)
    }

    @Test
    fun aResolveSeedsWatchStateButNotOverAnUnsentChange() {
        store.put("1", item("1"), "A", q, listOf(title("1", Kind.movie)))
        store.record("1", "1", 700, watched = false, atMs = 10)
        store.put("1", item("1"), "A", q, listOf(title("1", Kind.movie, watched = true)))
        assertEquals(700L, store.watch("1", "1")?.positionMs)
    }
}
