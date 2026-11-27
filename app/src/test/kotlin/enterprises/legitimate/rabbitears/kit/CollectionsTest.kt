package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsTest {
    private val server = FakeServer()
    private val model = CollectionsModel(server.api())

    private val mine = Json.collection("1", "Rainy day picks")
    private val theirs = Json.collection("2", "Dark Harbor saga", "tmdb", 3)

    private val collectionEntry =
        """{"id":"4","playlistId":"1","position":0,"collectionId":"2","kind":"collection",""" +
            """"itemCount":3,"durationMs":0,"addedAt":"${Json.T}","createdAt":"${Json.T}","modifiedAt":"${Json.T}","collection":$theirs}"""

    private fun item(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(Item.serializer(), json)

    private fun loaded() = runBlocking {
        server.on("GET /collections", body = Json.page(mine, theirs))
        model.load()
    }

    @Test
    fun loadsEveryPageAndOffersOnlyTheCustomOnesToAddTo() {
        loaded()
        assertTrue(model.loaded)
        assertEquals(listOf("1", "2"), model.collections.map { it.id })
        assertEquals(listOf("1"), model.custom.map { it.id })
        assertEquals("3 movies", model.collections[1].caption)
    }

    @Test
    fun aCollectionsMoviesComeInTheServersOrder() = runBlocking {
        server.on(
            "GET /collections/1/movies",
            body = Json.page(Json.item("7", "movie", "Second"), Json.item("5", "movie", "First")),
        )
        assertEquals(listOf("7", "5"), model.movies("1")!!.map { it.id })
    }

    @Test
    fun aMoviesCollectionsComeFromItsDetails() = runBlocking {
        val movie = Json.item("5", "movie").dropLast(1) + ""","collections":[$mine]}"""
        server.on("GET /items/5", body = movie)
        assertEquals(listOf("1"), model.ofMovie("5").map { it.id })
    }

    @Test
    fun createTrimsTheNameAndSkipsABlankOne() = runBlocking {
        server.on("POST /collections", 201, mine)
        assertNull(model.create("  "))
        assertEquals("1", model.create("  Rainy day picks ")?.id)
        assertTrue(server.calls.single().body.contains(""""name":"Rainy day picks""""))
        assertEquals(listOf("1"), model.collections.map { it.id })
    }

    @Test
    fun renameReplacesTheCollectionInPlace() = runBlocking {
        loaded()
        server.on("PUT /collections/1", body = Json.collection("1", "Cozy picks"))
        assertTrue(model.rename(model.collections[0], "Cozy picks"))
        assertEquals(listOf("Cozy picks", "Dark Harbor saga"), model.collections.map { it.name })
    }

    @Test
    fun gettingOneNotYetListedAddsIt() = runBlocking {
        server.on("GET /collections/2", body = theirs)
        assertEquals("2", model.get("2")?.id)
        assertEquals(listOf("2"), model.collections.map { it.id })
    }

    @Test
    fun deleteDropsItFromTheList() = runBlocking {
        loaded()
        server.on("DELETE /collections/1", 204)
        assertTrue(model.delete(model.collections[0]))
        assertEquals(listOf("2"), model.collections.map { it.id })
    }

    @Test
    fun addingAndRemovingAMovieRefreshesItsCount() = runBlocking {
        loaded()
        server.on("PUT /collections/1/movies/5", 204)
        server.on("DELETE /collections/1/movies/5", 204)
        server.on("GET /collections/1", body = Json.collection("1", "Rainy day picks", movies = 3))
        assertTrue(model.add("1", "5"))
        assertEquals(3, model.collections[0].movieCount)
        assertTrue(model.remove("1", "5"))
        assertEquals(1, server.called("PUT /collections/1/movies/5").size)
        assertEquals(1, server.called("DELETE /collections/1/movies/5").size)
    }

    @Test
    fun aDraggedMovieShowsAtItsNewPlaceAtOnce() {
        val shown = listOf("5", "6", "7").map { Json.item(it, "movie") }.map(::item)
        assertEquals(listOf("6", "7", "5"), shown.moved("5", 2).map { it.id })
        assertEquals(listOf("7", "5", "6"), shown.moved("7", 0).map { it.id })
        assertEquals(shown, shown.moved("9", 1))
    }

    @Test
    fun movingSendsTheIndexAndTheListShowsTheFlagTheServerSet() = runBlocking {
        loaded()
        server.on(
            "PUT /collections/1/movies/5/position",
            body = Json.collection("1", "Rainy day picks", customOrder = true),
        )
        assertTrue(model.move("1", "5", 2))
        assertEquals(
            """{"position":2}""",
            server.called("PUT /collections/1/movies/5/position").single().body,
        )
        assertEquals(true, model.collections[0].customOrder)
    }

    @Test
    fun aRefusedMoveIsNamedAndLeavesTheFlagAlone() = runBlocking {
        loaded()
        server.error("PUT /collections/2/movies/5/position", 403, "forbidden", "Not yours to edit.")
        assertFalse(model.move("2", "5", 0))
        assertEquals("Not yours to edit.", model.error)
        assertEquals(false, model.collections[1].customOrder)
    }

    @Test
    fun resettingClearsTheFlag() = runBlocking {
        server.on(
            "GET /collections",
            body = Json.page(Json.collection("1", "Rainy day picks", customOrder = true)),
        )
        model.load()
        assertEquals(true, model.collections[0].customOrder)
        server.on("DELETE /collections/1/order", body = mine)
        assertTrue(model.resetOrder("1"))
        assertEquals(false, model.collections[0].customOrder)
    }

    @Test
    fun aRefusalIsNamedAndReportedAsFailure() = runBlocking {
        server.error("PUT /collections/2/movies/5", 403, "forbidden", "Not yours to edit.")
        assertFalse(model.add("2", "5"))
        assertEquals("Not yours to edit.", model.error)
    }

    @Test
    fun aCollectionEntryReadsAsOneRow() {
        val entry =
            Serializer.kotlinxSerializationJson.decodeFromString(
                PlaylistEntry.serializer(),
                collectionEntry,
            )
        assertEquals("Dark Harbor saga", entry.title)
        assertEquals("3 movies", entry.caption)
        assertNull(entry.artId)
    }

    @Test
    fun addingACollectionToAPlaylistSendsOnlyItsId() = runBlocking {
        val playlists = PlaylistsModel(server.api(), "1")
        val playlist =
            """{"id":"1","profileId":"1","name":"Movie night","kind":"movies","access":"owner","itemCount":3,""" +
                """"durationMs":0,"createdAt":"${Json.T}","modifiedAt":"${Json.T}"}"""
        server.on("POST /profiles/1/playlists/1/entries", 201, collectionEntry)
        server.on("GET /profiles/1/playlists/1", body = playlist)
        assertTrue(playlists.addCollection("2", "1"))
        val body = server.called("POST /profiles/1/playlists/1/entries").single().body
        assertTrue(body.contains(""""collectionId":"2""""))
        assertFalse(Regex(""""itemId":\d""").containsMatchIn(body))
    }
}
