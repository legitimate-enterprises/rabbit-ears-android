package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.FavoriteKind
import enterprises.legitimate.rabbitears.api.models.Item
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesTest {
    private val server = FakeServer()
    private val model = FavoritesModel(server.api(), "1")

    private fun favorite(id: String, kind: String, at: String) =
        """{"itemId":"$id","kind":"$kind","createdAt":"$at"}"""

    private fun item(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(Item.serializer(), json)

    private val video =
        """{"id":"60","libraryId":"2","channelId":"30","channelTitle":"Workbench Weekly",""" +
            """"title":"Video 60","watched":false,"positionMs":0,"addedAt":"${Json.T}"}"""

    @Test
    fun everyPageLoadsAndEachKindListsMostRecentlyStarredFirst() = runBlocking {
        server.respond = {
            if (it.url.encodedPath.endsWith("/favorites"))
                200 to
                    if (it.url.queryParameter("offset") == "2")
                        """{"items":[${favorite("2", "movie", "2026-01-01T00:00:00Z")}]}"""
                    else
                        """{"items":[${favorite("40", "artist", "2026-03-01T00:00:00Z")},""" +
                            """${favorite("1", "movie", "2026-02-01T00:00:00Z")}],"nextOffset":2}"""
            else null
        }
        model.load()
        assertNull(model.error)
        assertEquals(listOf("1", "2"), model.of(FavoriteKind.movie))
        assertEquals(listOf("40"), model.of(FavoriteKind.artist))
        assertTrue("2" in model)
        assertFalse("3" in model)
        assertEquals(setOf("1", "2", "40"), model.ids)
    }

    @Test
    fun theScreenListsEachKindInSectionOrderFromTheCatalogAndTheServer() = runBlocking {
        server.on(
            "GET /profiles/1/favorites",
            body =
                """{"items":[${favorite("60", "video", "2026-06-01T00:00:00Z")},""" +
                    """${favorite("2", "movie", "2026-05-01T00:00:00Z")},""" +
                    """${favorite("50", "album", "2026-04-01T00:00:00Z")},""" +
                    """${favorite("1", "movie", "2026-03-01T00:00:00Z")},""" +
                    """${favorite("99", "song", "2026-02-01T00:00:00Z")},""" +
                    """${favorite("7", "episode", "2026-01-01T00:00:00Z")}]}""",
        )
        server.on("GET /music/albums/50", body = MusicJson.album("50"))
        server.on("GET /youtube/videos/60", body = video)
        server.on("GET /items/7", body = Json.item("7", "episode", "Pilot"))
        // A song gone since it was starred is left out, not shown broken.
        server.error("GET /music/songs/99", 404, "not_found")
        val catalog =
            Catalog(
                listOf(Json.item("1", "movie", "Dune"), Json.item("2", "movie", "Flow"))
                    .map(::item),
            )
        model.load()
        val shelves = model.shelves(catalog)
        assertEquals(listOf("2", "1"), shelves.movies.map { it.id })
        assertEquals(listOf("7"), shelves.episodes.map { it.id })
        assertEquals(listOf("50"), shelves.albums.map { it.id })
        assertEquals(listOf("60"), shelves.videos.map { it.id })
        assertTrue(shelves.songs.isEmpty())
        assertTrue(
            shelves.shows.isEmpty() && shelves.seasons.isEmpty() && shelves.artists.isEmpty(),
        )
        assertFalse(shelves.isEmpty)
        // Movies, shows, seasons and episodes the catalog holds are not asked for again.
        assertTrue(server.called("GET /items/1").isEmpty())
        assertTrue(FavoriteShelves().isEmpty)
    }

    @Test
    fun aStarShowsAtOnceAndIsPutFirstInItsKind() = runBlocking {
        server.on(
            "GET /profiles/1/favorites",
            body = """{"items":[${favorite("1", "movie", "2026-01-01T00:00:00Z")}]}""",
        )
        server.on("PUT /profiles/1/favorites/2", 204)
        model.load()
        val answer = CountDownLatch(1)
        var seen: Boolean? = null
        server.before = {
            if (it.startsWith("PUT")) {
                seen = "2" in model
                answer.await()
            }
        }
        val star =
            launch(kotlinx.coroutines.Dispatchers.IO) { model.set("2", FavoriteKind.movie, true) }
        eventually { seen != null }
        answer.countDown()
        star.join()
        assertEquals(true, seen)
        assertEquals(listOf("2", "1"), model.of(FavoriteKind.movie))
        assertEquals(1, server.called("PUT /profiles/1/favorites/2").size)
        assertNull(model.error)
    }

    @Test
    fun anUnstarIsADeleteAndTheFavoriteGoesAtOnce() = runBlocking {
        server.on(
            "GET /profiles/1/favorites",
            body = """{"items":[${favorite("1", "movie", "2026-01-01T00:00:00Z")}]}""",
        )
        server.on("DELETE /profiles/1/favorites/1", 204)
        model.load()
        model.set("1", FavoriteKind.movie, false)
        assertFalse("1" in model)
        assertEquals(1, server.called("DELETE /profiles/1/favorites/1").size)
    }

    @Test
    fun aWriteTheServerRefusesPutsTheStarBackWhereItWas() = runBlocking {
        server.on(
            "GET /profiles/1/favorites",
            body =
                """{"items":[${favorite("3", "movie", "2026-03-01T00:00:00Z")},""" +
                    """${favorite("1", "movie", "2026-01-01T00:00:00Z")}]}""",
        )
        server.error("PUT /profiles/1/favorites/2", 500, "internal", "Database is locked.")
        server.error("DELETE /profiles/1/favorites/3", 500, "internal", "Database is locked.")
        model.load()
        model.set("2", FavoriteKind.movie, true)
        assertFalse("2" in model)
        assertNotNull(model.error)
        model.set("3", FavoriteKind.movie, false)
        assertEquals(listOf("3", "1"), model.of(FavoriteKind.movie))
        assertNotNull(model.error)
    }

    @Test
    fun aKidStarsAsThemself() = runBlocking {
        val kid = FavoritesModel(server.api(), "7")
        server.on("GET /profiles/7/favorites", body = """{"items":[]}""")
        server.on("PUT /profiles/7/favorites/1", 204)
        kid.load()
        kid.set("1", FavoriteKind.movie, true)
        assertTrue("1" in kid)
        assertEquals(1, server.called("PUT /profiles/7/favorites/1").size)
    }
}
