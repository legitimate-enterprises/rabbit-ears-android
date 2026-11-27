package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.PlaylistPlayOrderItem
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistsTest {
    private val server = FakeServer()
    private val model = PlaylistsModel(server.api(), 1)

    private fun playlist(
        id: Long,
        kind: String = "music",
        count: Int = 0,
        access: String = "owner",
        owner: String? = null,
    ) =
        """{"id":$id,"profileId":1,"name":"Road trip","kind":"$kind","itemCount":$count,""" +
            """"access":"$access",${owner?.let { "\"ownerName\":\"$it\"," } ?: ""}""" +
            """"durationMs":0,"createdAt":"${Json.T}","updatedAt":"${Json.T}"}"""

    @Test
    fun aSharedPlaylistSaysWhoAndWhatTheProfileMayDo() = runBlocking {
        server.on(
            "GET /profiles/1/playlists",
            body =
                Json.page(
                    playlist(1),
                    playlist(2, access = "read", owner = "Ana"),
                    playlist(3, access = "write", owner = "Ana"),
                ),
        )
        model.load()
        val (mine, read, write) = model.playlists
        assertTrue(mine.isOwned)
        assertNull(mine.sharedBy)
        assertEquals("Shared by Ana", read.sharedBy)
        assertFalse(read.isEditable)
        assertTrue(write.isEditable)
        assertFalse(write.isOwned)
        assertEquals(listOf(1L, 3L), model.editable(PlaylistKind.music).map { it.id })
    }

    @Test
    fun aKidGetsAPlaylistsViewOnlyForWhatIsSharedAndAddsToNone() = runBlocking {
        val kid = PlaylistsModel(server.api(), 1, adult = false)
        server.on("GET /profiles/1/playlists", body = Json.page(playlist(2, access = "read")))
        kid.load()
        assertTrue(kid.offersView(PlaylistKind.music))
        assertFalse(kid.offersView(PlaylistKind.tv))
        assertEquals(emptyList<Long>(), kid.editable(PlaylistKind.music).map { it.id })
        assertTrue(model.offersView(PlaylistKind.tv))
    }

    private fun entry(id: Long, kind: String, extra: String, count: Int = 1) =
        """{"id":$id,"playlistId":1,"position":$id,"itemId":$id,"kind":"$kind","itemCount":$count,""" +
            """"durationMs":0,"addedAt":"${Json.T}",$extra}"""

    private val movie = """"item":${Json.item(7, "movie", "Night Ferry")}"""

    private fun row(entryId: Long, itemId: Long, kind: String, extra: String = "") =
        """{"entryId":$entryId,"itemId":$itemId,"kind":"$kind"$extra}"""

    @Test
    fun loadsAndFiltersByKind() = runBlocking {
        server.on("GET /profiles/1/playlists", body = Json.page(playlist(1), playlist(2, "tv")))
        model.load()
        assertEquals(listOf(1L), model.of(PlaylistKind.music).map { it.id })
        assertEquals(listOf(2L), model.of(PlaylistKind.tv).map { it.id })
        assertTrue(model.loaded)
    }

    @Test
    fun createTrimsTheNameAndSkipsABlankOne() = runBlocking {
        server.on("POST /profiles/1/playlists", 201, playlist(3))
        assertNull(model.create("   ", PlaylistKind.music))
        assertEquals(3L, model.create("  Road trip ", PlaylistKind.music)?.id)
        assertTrue(server.calls.single().body.contains(""""name":"Road trip""""))
        assertTrue(server.calls.single().body.contains(""""kind":"music""""))
        assertEquals(listOf(3L), model.playlists.map { it.id })
    }

    @Test
    fun renameKeepsTheKindAndReplacesThePlaylistInPlace() = runBlocking {
        server.on("GET /profiles/1/playlists", body = Json.page(playlist(1), playlist(2, "tv")))
        model.load()
        server.on(
            "PUT /profiles/1/playlists/1",
            body = playlist(1).replace(""""Road trip"""", """"Night drive""""),
        )
        assertFalse(model.rename(model.playlists[0], "   "))
        assertTrue(model.rename(model.playlists[0], "  Night drive "))
        val put = server.calls.last()
        assertTrue(put.body.contains(""""name":"Night drive""""))
        assertTrue(put.body.contains(""""kind":"music""""))
        assertEquals(listOf("Night drive", "Road trip"), model.playlists.map { it.name })
    }

    @Test
    fun addingRefreshesTheCountAndARefusalIsNamed() = runBlocking {
        server.on("GET /profiles/1/playlists", body = Json.page(playlist(1)))
        model.load()
        server.on("GET /profiles/1/playlists/1", body = playlist(1, count = 1))
        server.on(
            "POST /profiles/1/playlists/1/entries",
            201,
            entry(9, "song", """"song":${MusicJson.song(7)}"""),
        )
        assertTrue(model.add(7, 1))
        assertEquals(1, model.playlists.single().itemCount)
        server.error("POST /profiles/1/playlists/1/entries", 400, "bad_request", "Wrong kind.")
        assertFalse(model.add(8, 1))
        assertEquals("Wrong kind.", model.error)
    }

    @Test
    fun aChosenEditionIsAddedWithTheItem() = runBlocking {
        server.on("GET /profiles/1/playlists/1", body = playlist(1, "movies", count = 1))
        server.on("POST /profiles/1/playlists/1/entries", 201, entry(9, "movie", movie))
        assertTrue(model.add(7, 1, editionId = 32))
        assertTrue(server.calls.first().body.contains(""""editionId":32"""))
        assertTrue(model.editions.isEmpty())
    }

    @Test
    fun aTitleWithSeveralEditionsAsksWhichAndAddsAgain() = runBlocking {
        server.on(
            "POST /profiles/1/playlists/1/entries",
            400,
            """{"code":"bad_request","message":"Name an edition.",""" +
                """"fields":[{"field":"editionId","message":"required"}]}""",
        )
        server.on(
            "GET /items/7",
            body =
                Json.item(7, "movie", "Night Ferry").dropLast(1) +
                    ""","editions":[{"id":31,"name":"Standard","isDefault":true,"copies":[]},""" +
                    """{"id":32,"name":"Extended","isDefault":false,"copies":[]}]}""",
        )
        assertFalse(model.add(7, 1))
        assertNull(model.error)
        assertEquals(listOf(31L, 32L), model.editions.map { it.id })
        server.on("GET /profiles/1/playlists/1", body = playlist(1, "movies", count = 1))
        server.on("POST /profiles/1/playlists/1/entries", 201, entry(9, "movie", movie))
        assertTrue(model.add(7, 1, editionId = 32))
        assertTrue(
            server
                .called("POST /profiles/1/playlists/1/entries")
                .last()
                .body
                .contains(""""editionId":32"""),
        )
        assertTrue(model.editions.isEmpty())
    }

    @Test
    fun anotherRefusalIsNamedAndAsksNothing() = runBlocking {
        server.error("POST /profiles/1/playlists/1/entries", 400, "bad_request", "Wrong kind.")
        assertFalse(model.add(7, 1))
        assertEquals("Wrong kind.", model.error)
        assertTrue(model.editions.isEmpty())
    }

    @Test
    fun aRowNamesItsEditionOnlyWhenTheServerDoes() {
        fun parse(json: String) =
            Serializer.kotlinxSerializationJson.decodeFromString(PlaylistEntry.serializer(), json)
        assertEquals(
            "Night Ferry · Extended",
            parse(entry(9, "movie", """$movie,"editionId":32,"editionName":"Extended"""")).title,
        )
        assertEquals("Night Ferry", parse(entry(9, "movie", """$movie,"editionId":31""")).title)
    }

    @Test
    fun thePlayOrderIsTheServersRowsInOrder() = runBlocking {
        server.on(
            "GET /profiles/1/playlists/1/play-order",
            body =
                Json.page(
                    row(4, 10, "episode", ""","item":${Json.item(10, "episode", "Pilot")}"""),
                    row(5, 7, "song", ""","song":${MusicJson.song(7)}"""),
                ),
        )
        val rows = model.playOrder(1)!!
        assertEquals(listOf(10L, 7L), rows.map { it.itemId })
        assertEquals(listOf(4L, 5L), rows.map { it.entryId })
        assertEquals("Song 7", rows[1].song?.title)
    }

    @Test
    fun rowsAreCountedAsWhatTheyStandFor() {
        val show = entry(1, "show", """"item":${Json.item(20, "show", "Dark Harbor")}""", 12)
        val season =
            entry(
                2,
                "season",
                """"item":${Json.item(21, "season", "Season 2")},""" +
                    """"show":${Json.item(20, "show", "Dark Harbor")}""",
                6,
            )
        val album = entry(3, "album", """"album":${MusicJson.album(50, "In Rainbows")}""", 9)
        val song = entry(4, "song", """"song":${MusicJson.song(7, title = "Nude")}""")
        fun parse(json: String) =
            Serializer.kotlinxSerializationJson.decodeFromString(PlaylistEntry.serializer(), json)
        assertEquals("Dark Harbor", parse(show).title)
        assertEquals("12 episodes", parse(show).caption)
        assertEquals("Dark Harbor · Season 2", parse(season).title)
        assertEquals("6 episodes", parse(season).caption)
        assertEquals("In Rainbows", parse(album).title)
        assertEquals("9 songs", parse(album).caption)
        assertEquals("Nude", parse(song).title)
        assertEquals("Radiohead · 3:20", parse(song).caption)
    }

    @Test
    fun aRunWalksTheRowsAndEndsOnTheLast() {
        fun parse(json: String) =
            Serializer.kotlinxSerializationJson.decodeFromString(
                PlaylistPlayOrderItem.serializer(),
                json,
            )
        val rows =
            listOf(
                parse(row(1, 10, "movie", ""","editionId":32,"editionName":"Extended"""")),
                parse(row(2, 11, "movie", ""","editionId":40""")),
            )
        val run = PlaylistRun(5, rows, 0)
        assertEquals(10L, run.itemId)
        assertEquals(32L, run.editionId)
        assertEquals("Extended", run.editionName)
        assertEquals(11L, run.next()?.itemId)
        assertEquals(40L, run.next()?.editionId)
        assertNull(run.next()?.editionName)
        assertEquals(5L, run.next()?.playlistId)
        assertNull(run.next()?.next())
    }
}

class YouTubeTest {
    private val server = FakeServer()
    private val model = YouTubeModel(server.api(), 1)

    private fun video(id: Long) =
        """{"id":$id,"libraryId":2,"channelId":30,"channelTitle":"Workbench Weekly",""" +
            """"title":"Video $id","watched":false,"positionMs":0,"addedAt":"${Json.T}",""" +
            """"durationMs":2520000,"publishedOn":"2026-11-05"}"""

    private val channel =
        """{"id":30,"libraryId":2,"title":"Workbench Weekly","videoCount":214,""" +
            """"unwatchedCount":12,"addedAt":"${Json.T}","lastVideoOn":"2026-11-14"}"""

    @Test
    fun unwatchedNewestAsksTheServerForBoth() = runBlocking {
        server.on("GET /youtube/videos", body = Json.page(video(1)))
        assertEquals(1, model.videos(VideoSort.Newest, unwatched = true).size)
        val query = server.calls.single().query!!
        assertTrue(query.contains("unwatched=true") && query.contains("sort=newest"))
    }

    @Test
    fun newFromChannelsIsTheTwentyNewestUnwatched() = runBlocking {
        server.on("GET /youtube/videos", body = Json.page(video(1)))
        assertEquals(1, model.newFromChannels().size)
        val query = server.calls.single().query!!
        assertTrue(
            query.contains("unwatched=true") &&
                query.contains("sort=newest") &&
                query.contains("limit=20"),
        )
    }

    @Test
    fun inProgressAsksTheServerForLatestPlayed() = runBlocking {
        server.on("GET /youtube/videos", body = Json.page(video(1)))
        assertEquals(1, model.inProgress().size)
        val query = server.calls.single().query!!
        assertTrue(query.contains("inProgress=true") && query.contains("sort=played"))
    }

    @Test
    fun aChannelReadsItsVideos() = runBlocking {
        server.on("GET /youtube/channels/30", body = channel)
        server.on("GET /youtube/videos", body = Json.page(video(1), video(2)))
        val detail = model.channel(30)!!
        assertEquals(2, detail.videos.size)
        assertEquals(
            listOf("214 videos", "12 unwatched", "Last video today"),
            Format.channelParts(detail.channel, LocalDate.of(2026, 11, 14)),
        )
    }

    @Test
    fun markAllWatchedWritesTheChannelsState() = runBlocking {
        server.on("PUT /profiles/1/youtube/channels/30/watch-state", 204)
        assertTrue(model.setWatched(30, true))
        assertTrue(server.calls.single().body.contains(""""watched":true"""))
    }

    @Test
    fun forKidsIsSetOnTheChannel() = runBlocking {
        server.on(
            "PUT /youtube/channels/30/for-kids",
            body = channel.dropLast(1) + ""","forKids":true}""",
        )
        assertTrue(model.setForKids(30, true))
        assertTrue(server.calls.single().body.contains(""""forKids":true"""))
        server.error("PUT /youtube/channels/30/for-kids", 403, "forbidden")
        assertFalse(model.setForKids(30, false))
        assertEquals("forbidden", model.error)
    }

    @Test
    fun publishedReadsAsTheBoardsSayIt() {
        val today = LocalDate.of(2026, 11, 14)
        assertEquals("today", Format.published(today, today))
        assertEquals("yesterday", Format.published(today.minusDays(1), today))
        assertEquals("2 weeks ago", Format.published(today.minusDays(14), today))
        assertEquals("last month", Format.published(today.minusDays(40), today))
        assertEquals("42m", Format.minutes(2_520_000))
    }
}
