package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private val server = FakeServer()

    @Test
    fun aTapTogglesInPickOrderAndDoneClears() {
        val selection = Selection()
        selection.begin(3)
        selection.toggle(1)
        selection.toggle(2)
        selection.toggle(3)
        assertTrue(selection.active)
        assertEquals(listOf(1L, 2L), selection.picked)
        selection.end()
        assertFalse(selection.active)
        assertEquals(emptyList<Long>(), selection.picked)
    }

    @Test
    fun selectAllPicksTheShownSetKeepingEarlierPicksFirstAndDeselectAllEmptiesIt() {
        val selection = Selection()
        selection.begin(3)
        selection.toggle(9)
        val shown = listOf(1L, 2L, 3L, 4L)
        assertFalse(selection.allPicked(shown))
        selection.selectAll(shown)
        assertEquals(listOf(3L, 1L, 2L, 4L), selection.picked)
        assertTrue(selection.allPicked(shown))

        selection.deselectAll()
        assertTrue(selection.active)
        assertEquals(emptyList<Long>(), selection.picked)
        assertFalse(selection.allPicked(shown))
        assertFalse(selection.allPicked(emptyList()))
    }

    @Test
    fun nothingFromYouTubeGoesInAPlaylistAndMusicIsNeverWatched() {
        assertNull(SelectKind.Channel.playlistKind)
        assertNull(SelectKind.Video.playlistKind)
        assertEquals(PlaylistKind.tv, SelectKind.Show.playlistKind)
        assertEquals(PlaylistKind.music, SelectKind.Song.playlistKind)
        assertFalse(SelectKind.Album.watches)
        assertFalse(SelectKind.Video.kidsContent)
    }

    @Test
    fun markingASelectionIsOneRequestAndMarksAShowsEpisodes() = runBlocking {
        server.on(
            "GET /items",
            body =
                Json.page(
                    Json.item(1, "movie", "Dune"),
                    Json.item(10, "show", "Severance"),
                    Json.item(11, "season", "Season 1", parent = 10, season = 1),
                    Json.item(101, "episode", "Good News", parent = 11, season = 1, episode = 1),
                ),
        )
        server.on("GET /profiles/1/watch-state", body = Json.page())
        server.on("PUT /profiles/1/watch-state/batch", 204)
        server.on("PUT /items/kids-content/batch", 204)
        val model = CatalogModel(server.api(), profileId = 1)
        model.load()
        val picked = listOf(model.catalog.item(10)!!, model.catalog.item(1)!!)
        model.setWatched(true, picked)
        val body = server.called("PUT /profiles/1/watch-state/batch").single().body
        assertTrue(
            body,
            body.contains(""""itemIds":[10,1]""") && body.contains(""""watched":true"""),
        )
        assertTrue(model.catalog.isWatched(101))
        assertTrue(model.catalog.isWatched(1))

        model.setKidsContent(picked, false)
        val kids = server.called("PUT /items/kids-content/batch").single().body
        assertTrue(
            kids,
            kids.contains(""""itemIds":[10,1]""") && kids.contains(""""kidsContent":false"""),
        )
        assertEquals(false, model.catalog.item(10)?.kidsContentOverride)
        assertNull(model.error)
    }

    @Test
    fun aRefusedMarkChangesNothing() = runBlocking {
        server.on("GET /items", body = Json.page(Json.item(1, "movie", "Dune")))
        server.on("GET /profiles/1/watch-state", body = Json.page())
        server.error("PUT /profiles/1/watch-state/batch", 404, "not_found")
        val model = CatalogModel(server.api(), profileId = 1)
        model.load()
        model.setWatched(true, listOf(model.catalog.item(1)!!))
        assertNotNull(model.error)
        assertFalse(model.catalog.isWatched(1))
    }

    @Test
    fun addingASelectionToAPlaylistIsOneRequest() = runBlocking {
        server.on("POST /profiles/1/playlists/9/entries/batch", 204)
        server.on(
            "GET /profiles/1/playlists/9",
            body =
                """{"id":9,"profileId":1,"name":"Mix","kind":"music","access":"owner",""" +
                    """"itemCount":2,"durationMs":0,""" +
                    """"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}""",
        )
        val model = PlaylistsModel(server.api(), profileId = 1)
        assertTrue(model.add(listOf(101L, 100L), 9))
        val body = server.called("POST /profiles/1/playlists/9/entries/batch").single().body
        assertTrue(
            body,
            body.contains(""""itemId":101""") && body.indexOf("101") < body.indexOf("100"),
        )
    }

    @Test
    fun youTubeSelectionsMarkAndSetForKidsInOneRequestEach() = runBlocking {
        server.on("PUT /profiles/1/watch-state/batch", 204)
        server.on("PUT /items/kids-content/batch", 204)
        val model = YouTubeModel(server.api(), profileId = 1)
        assertTrue(model.setWatched(listOf(60L, 61L), false))
        assertTrue(model.setForKids(listOf(60L), true))
        assertTrue(
            server.called("PUT /profiles/1/watch-state/batch").single().body.contains("[60,61]"),
        )
        assertTrue(server.called("PUT /items/kids-content/batch").single().body.contains("true"))
    }
}
