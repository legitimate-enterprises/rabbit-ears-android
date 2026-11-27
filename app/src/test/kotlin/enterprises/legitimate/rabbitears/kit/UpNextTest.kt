package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.PlaylistPlayOrderItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpNextTest {
    private val server = FakeServer()
    private val api = server.api()

    private fun row(id: Long) =
        Serializer.kotlinxSerializationJson.decodeFromString<PlaylistPlayOrderItem>(
            """{"entryId":$id,"itemId":$id,"kind":"movie"}""",
        )

    @Test
    fun theServerNamesTheNextEpisode() = runBlocking {
        server.on(
            "GET /profiles/1/up-next/21",
            body = """{"itemId":22,"kind":"episode","title":"Alpha · S1 E2 · Second"}""",
        )
        assertEquals(
            UpNextPlay("Alpha · S1 E2 · Second", 22, null, null),
            api.upNext(1, 21, null),
        )
        assertNull(server.called("GET /profiles/1/up-next/21").single().query)
    }

    @Test
    fun aPlaylistAsksFromItsRowAndPlaysTheRowAnswered() = runBlocking {
        val run = PlaylistRun(3, listOf(row(20), row(21), row(22)), 0)
        server.on(
            "GET /profiles/1/up-next/20",
            body = """{"itemId":22,"kind":"movie","title":"T22","index":2,"editionId":6}""",
        )
        val next = api.upNext(1, 20, run)
        assertEquals(
            "playlistId=3&index=0",
            server.called("GET /profiles/1/up-next/20").single().query,
        )
        assertEquals(2, next?.run?.index)
        assertEquals(22L, next?.run?.itemId)
        assertEquals(6L, next?.editionId)
    }

    @Test
    fun nothingNextIsNull() = runBlocking {
        server.on("GET /profiles/1/up-next/21", 204)
        assertNull(api.upNext(1, 21, null))
    }
}
