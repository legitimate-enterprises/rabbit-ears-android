package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.DownloadTitle
import enterprises.legitimate.rabbitears.api.models.DownloadTitle.Kind
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.PlaylistPlayOrderItem
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The usual models with no server: they show and play what is on the device. */
class OfflineTest {
    private val store = DownloadStore(Files.createTempDirectory("offline").toFile())
    private val server = FakeServer()
    private var unreachable = true
    private val offline = OfflineLibrary(store, "1") { unreachable }
    private val scope = CoroutineScope(Job())

    @After fun cancel() = scope.cancel()

    private fun meta(key: String, json: String) =
        store.meta(key).apply { parentFile!!.mkdirs() }.writeText(json)

    private fun onDevice(target: DownloadTarget, vararg titles: Pair<String, Kind>) {
        store.put(
            "1",
            target,
            "x",
            DownloadQuality.original,
            titles.map { (id, kind) ->
                DownloadTitle(
                    id,
                    kind,
                    "1",
                    available = true,
                    watched = false,
                    positionMs = 0,
                    runtimeMs = 100_000,
                )
            },
        )
        titles.forEach { (id, _) ->
            store.media("$id.mp4").apply { parentFile!!.mkdirs() }.writeText("media")
            store.setTitle(id) { it.copy(state = TitleState.OnDevice, file = "$id.mp4") }
        }
    }

    /** Severance with two downloaded episodes of three, and Dune. */
    private fun library() {
        meta("item-1", Json.item("1", "movie", "Dune"))
        meta("item-10", Json.item("10", "show", "Severance"))
        meta("item-11", Json.item("11", "season", "Season 1", parent = 10, season = 1))
        meta(
            "item-101",
            Json.item("101", "episode", "Good News", parent = 11, season = 1, episode = 1),
        )
        meta(
            "item-103",
            Json.item("103", "episode", "In Perpetuity", parent = 11, season = 1, episode = 3),
        )
        onDevice(DownloadTarget(itemId = "10"), "101" to Kind.episode, "103" to Kind.episode)
        onDevice(DownloadTarget(itemId = "1"), "1" to Kind.movie)
    }

    @Test
    fun theCatalogIsWhatIsDownloadedWithItsShowAbove() = runBlocking {
        library()
        val model = CatalogModel(server.api(), "1", offline)
        model.load()
        assertNull(model.error)
        assertTrue(server.calls.isEmpty())
        assertEquals(listOf("1"), model.catalog.grid(ItemKind.movie).map { it.id })
        assertEquals(listOf("10"), model.catalog.grid(ItemKind.show).map { it.id })
        assertEquals(listOf("101", "103"), model.catalog.episodes("10").map { it.id })
    }

    @Test
    fun aServerThatDoesNotAnswerFallsBackToTheDevice() = runBlocking {
        library()
        unreachable = false
        val down = FakeServer().apply { before = { throw java.io.IOException("down") } }
        val model = CatalogModel(down.api(), "1", offline)
        model.load()
        assertEquals(listOf("1"), model.catalog.grid(ItemKind.movie).map { it.id })
    }

    @Test
    fun aServerRefusalIsNotHiddenByTheDevice() = runBlocking {
        library()
        unreachable = false
        server.error("GET /items", 500, "internal")
        server.error("GET /profiles/1/watch-state", 500, "internal")
        val model = CatalogModel(server.api(), "1", offline)
        model.load()
        assertTrue(model.error != null)
        assertTrue(model.catalog.grid(ItemKind.movie).isEmpty())
    }

    @Test
    fun markingWatchedOfflineIsKeptForTheNextSync() = runBlocking {
        library()
        val model = CatalogModel(server.api(), "1", offline)
        model.load()
        model.setWatched(true, model.catalog.item("1")!!)
        assertTrue(model.catalog.isWatched("1"))
        assertEquals(listOf("1"), store.unsynced("1").map { it.itemId })
    }

    @Test
    fun upNextIsTheNextDownloadedEpisode() {
        library()
        assertEquals("103", offline.upNext("101", null)?.itemId)
        assertNull(offline.upNext("103", null))
    }

    @Test
    fun outOfReachShuffleTakesOnlyWhatIsOnTheDevice() {
        library()
        val kept =
            listOf("101", "102", "103").map {
                Serializer.kotlinxSerializationJson.decodeFromString(
                    Item.serializer(),
                    Json.item(it, "episode", parent = 11, season = 1, episode = it.toInt() - 100),
                )
            }
        assertEquals(listOf("101", "103"), offline.playable(kept).map { it.id })
        unreachable = false
        assertEquals(listOf("101", "102", "103"), offline.playable(kept).map { it.id })
    }

    @Test
    fun aShuffleOnTheDeviceSkipsWhatIsNotDownloaded() {
        library()
        val rows = listOf("103", "102", "101").map { row(it) }
        val next = offline.upNext("103", PlaylistRun(null, rows))
        assertEquals("101", next?.itemId)
        assertEquals(2, next?.run?.index)
        assertNull(offline.upNext("101", next?.run))
    }

    private fun row(id: String) =
        Serializer.kotlinxSerializationJson.decodeFromString<PlaylistPlayOrderItem>(
            """{"entryId":$id,"itemId":$id,"kind":"episode"}""",
        )

    @Test
    fun musicAndYouTubeShowWhatIsDownloaded() = runBlocking {
        meta(
            "song-7",
            """{"id":"7","libraryId":"2","albumId":"30","albumTitle":"Blue","albumArtistId":"40",""" +
                """"artistId":"40","artistName":"Joni","title":"River","lossless":true,"trackNumber":2}""",
        )
        meta(
            "album-30",
            """{"id":"30","libraryId":"2","artistId":"40","artistName":"Joni","title":"Blue",""" +
                """"songCount":10,"durationMs":1,"lossless":true,"genres":[],"addedAt":"${Json.T}"}""",
        )
        meta(
            "artist-40",
            """{"id":"40","libraryId":"2","name":"Joni","sortName":"Joni","albumCount":1,"songCount":10,"genres":[],"addedAt":"${Json.T}"}""",
        )
        meta(
            "video-9",
            """{"id":"9","libraryId":"3","channelId":"90","channelTitle":"Tom","title":"Vlog",""" +
                """"watched":false,"positionMs":0,"addedAt":"${Json.T}"}""",
        )
        meta(
            "channel-90",
            """{"id":"90","libraryId":"3","title":"Tom","videoCount":5,"unwatchedCount":5,"addedAt":"${Json.T}"}""",
        )
        onDevice(DownloadTarget(itemId = "30"), "7" to Kind.song)
        onDevice(DownloadTarget(itemId = "9"), "9" to Kind.video)
        val music = MusicModel(server.api(), offline)
        assertEquals(listOf("7"), music.album("30")?.songs?.map { it.id })
        music.loadAlbums()
        assertEquals(listOf("30"), music.albums?.map { it.id })
        val youtube = YouTubeModel(server.api(), "1", offline)
        youtube.loadChannels()
        assertEquals(listOf("90"), youtube.channels?.map { it.id })
        assertEquals(listOf("9"), youtube.channel("90")?.videos?.map { it.id })
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun aPlaylistPlaysOnlyItsDownloadedRows() = runBlocking {
        library()
        meta(
            "order-1-5",
            """[{"entryId":"1","itemId":"101","kind":"episode"},{"entryId":"1","itemId":"102","kind":"episode"},""" +
                """{"entryId":"1","itemId":"103","kind":"episode"}]""",
        )
        val playlists = PlaylistsModel(server.api(), "1", offline = offline)
        assertEquals(listOf("101", "103"), playlists.playOrder("5")?.map { it.itemId })
    }

    @Test
    fun aPlaylistStoredBeforeTheSharedFlagStillListsUnbadged() = runBlocking {
        library()
        meta(
            "playlist-1-5",
            """{"id":"5","profileId":"1","name":"Road trip","kind":"tv","access":"owner",""" +
                """"itemCount":2,"durationMs":0,"createdAt":"${Json.T}","modifiedAt":"${Json.T}"}""",
        )
        onDevice(DownloadTarget(playlistId = "5"), "101" to Kind.episode)
        val playlists = PlaylistsModel(server.api(), "1", offline = offline)
        playlists.load()
        assertEquals(listOf("5"), playlists.playlists.map { it.id })
        assertFalse(playlists.playlists.single().isShared)
    }

    @Test
    fun aLocalCopyPlaysWithNoSessionAndKeepsItsProgressWithItsInstant() = runBlocking {
        library()
        val output = PlayerModelTest.Output()
        val facts = DeviceFacts(emptyList(), null, emptyList(), PathFacts())
        val copy = store.local("1", "1")!!
        val model =
            PlayerModel(
                server.api(),
                "1",
                "1",
                output,
                PlayerModelTest.Capabilities(facts),
                scope,
                local = copy,
                preferLocal = true,
                offline = offline,
            )
        val before = System.currentTimeMillis()
        model.start(5_000)
        assertTrue(model.isLocal)
        assertEquals(copy.file.toURI().toString(), output.loads.single().first)
        assertEquals(5_000L, output.loads.single().second)
        assertFalse(output.loads.single().third)
        output.positionMs = 95_000
        assertTrue(
            "stopping says the copy played, so its progress reaches the server",
            model.stop(),
        )
        assertTrue(server.calls.isEmpty())
        val mark = store.watch("1", "1")!!
        assertTrue("past 90% it is watched", mark.watched)
        assertFalse(mark.synced)
        assertTrue(mark.atMs >= before)
    }

    @Test
    fun aTickPastNinetyPercentKeepsTheFileUntilPlaybackStops() = runBlocking {
        library()
        val output = PlayerModelTest.Output()
        val facts = DeviceFacts(emptyList(), null, emptyList(), PathFacts())
        val model =
            PlayerModel(
                server.api(),
                "1",
                "101",
                output,
                PlayerModelTest.Capabilities(facts),
                scope,
                local = store.local("1", "101"),
                preferLocal = true,
                offline = offline,
            )
        model.start(0)
        output.positionMs = 95_000
        model.tick()
        assertTrue(
            "it is remembered as watched, should the process die",
            store.watch("1", "101")!!.watched,
        )
        assertNotNull("the file under the player stays", store.local("1", "101"))
        assertEquals(
            "Up next at the end is still offered",
            "103",
            offline.upNext("101", null)?.itemId,
        )
        model.stop()
        assertNull("once stopped, the keep policy removes it", store.local("1", "101"))
    }

    @Test
    fun preferLocalOffStreamsWhileTheServerAnswers() = runBlocking {
        library()
        unreachable = false
        val output = PlayerModelTest.Output()
        val facts = DeviceFacts(emptyList(), null, emptyList(), PathFacts())
        server.on("POST /playback/sessions", 500, """{"code":"internal","message":"x"}""")
        val model =
            PlayerModel(
                server.api(),
                "1",
                "1",
                output,
                PlayerModelTest.Capabilities(facts),
                scope,
                local = store.local("1", "1"),
                preferLocal = false,
                offline = offline,
            )
        model.start(0)
        assertFalse(model.isLocal)
        assertEquals(1, server.called("POST /playback/sessions").size)
    }
}
