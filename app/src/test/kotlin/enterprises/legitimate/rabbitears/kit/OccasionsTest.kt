package enterprises.legitimate.rabbitears.kit

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OccasionsTest {
    private val server = FakeServer()
    private var today = LocalDate.of(2026, 12, 3)
    private val model = OccasionsModel(server.api(), profileId = "7") { today }

    private fun occasion(id: String, name: String, extra: String = "") =
        """{"id":$id,"name":"$name",$extra"anchorDate":"2026-12-25","firstDate":"2026-12-01",""" +
            """"lastDate":"2026-12-26"}"""

    private fun active(vararg occasions: String) =
        server.on(
            "GET /profiles/7/occasions",
            body = """{"items":[${occasions.joinToString(",")}]}""",
        )

    /** Answers each occasion's own titles; an occasion not named has none. */
    private fun titles(vararg byOccasion: Pair<Long, List<String>>) {
        server.respond = { request ->
            if (request.url.encodedPath != "/api/v2/items") null
            else
                200 to
                    Json.page(
                        *byOccasion
                            .firstOrNull {
                                request.url.queryParameter("occasionId") == "${it.first}"
                            }
                            ?.second
                            .orEmpty()
                            .toTypedArray(),
                    )
        }
    }

    @Test
    fun keepsTheServersOrderAndNamesVerbatim() = runBlocking {
        active(occasion("2", "Halloween"), occasion("1", "Christmas"))
        titles(
            2L to listOf(Json.item("1", "movie", "Hocus Pocus")),
            1L to listOf(Json.item("2", "movie", "Elf")),
        )
        model.load()
        assertEquals(listOf("Halloween", "Christmas"), model.shelves.map { it.occasion.name })
        assertEquals(listOf("Hocus Pocus"), model.shelves[0].items.map { Catalog.title(it) })
    }

    @Test
    fun sendsTheDevicesLocalDate() = runBlocking {
        active()
        model.load()
        assertEquals("date=2026-12-03", server.called("GET /profiles/7/occasions").single().query)
    }

    @Test
    fun anOccasionWithoutTitlesShowsNothing() = runBlocking {
        active(occasion("1", "Christmas"), occasion("2", "Halloween"))
        titles(2L to listOf(Json.item("1", "movie", "Hocus Pocus")))
        model.load()
        assertEquals(listOf("Halloween"), model.shelves.map { it.occasion.name })
    }

    @Test
    fun asksForAsManyTitlesAsRecentlyAdded() = runBlocking {
        active(occasion("1", "Christmas"))
        titles(1L to listOf(Json.item("1", "movie", "Elf")))
        model.load()
        assertTrue(server.called("GET /items").single().query!!.contains("limit=20"))
    }

    @Test
    fun loadsAgainForANewDate() = runBlocking {
        active(occasion("1", "Christmas"))
        titles(1L to listOf(Json.item("1", "movie", "Elf")))
        model.load()
        assertEquals(1, model.shelves.size)
        active()
        today = LocalDate.of(2027, 1, 20)
        model.load()
        assertTrue(model.shelves.isEmpty())
        assertEquals("date=2027-01-20", server.called("GET /profiles/7/occasions").last().query)
    }

    @Test
    fun aFailureLeavesNoShelf() = runBlocking {
        server.error("GET /profiles/7/occasions", 500, "internal")
        model.load()
        assertTrue(model.shelves.isEmpty())
    }

    @Test
    fun aHeadlineTitlesTheRowAndTheNameTitlesWhenThereIsNone() = runBlocking {
        active(
            occasion("1", "Christmas", """"headline":"Ho, Ho, Ho!","""),
            occasion("2", "Halloween"),
            occasion("3", "Easter", """"headline":"  ","""),
        )
        titles(
            1L to listOf(Json.item("1", "movie", "Elf")),
            2L to listOf(Json.item("2", "movie", "Hocus Pocus")),
            3L to listOf(Json.item("3", "movie", "Hop")),
        )
        model.load()
        assertEquals(listOf("Ho, Ho, Ho!", "Halloween", "Easter"), model.shelves.map { it.title })
        assertEquals(
            listOf("Christmas", "Halloween", "Easter"),
            model.shelves.map { it.occasion.name },
        )
    }

    @Test
    fun theOccasionScreenReadsEveryPageOfMembers() = runBlocking {
        server.respond = { request ->
            if (request.url.encodedPath != "/api/v2/items") null
            else if (request.url.queryParameter("offset") == "200")
                200 to Json.page(Json.item("201", "movie", "Elf 201"))
            else
                200 to
                    """{"items":[${Json.item("1", "movie", "Elf 1")}],"total":201,"nextOffset":200}"""
        }
        val all = model.members("1")
        assertEquals(listOf("Elf 1", "Elf 201"), all?.map { Catalog.title(it) })
        assertTrue(server.called("GET /items").all { it.query!!.contains("occasionId=1") })
    }

    @Test
    fun aDetailsRowHoldsTheOtherMembersOfEachOccasionTheTitleIsIn() = runBlocking {
        active(occasion("1", "Christmas"), occasion("2", "Halloween"))
        server.respond = { request ->
            if (request.url.encodedPath != "/api/v2/items") null
            else if (request.url.queryParameter("occasionId") == "2")
                200 to Json.page(Json.item("31", "movie", "Elf"))
            else
                200 to
                    Json.page(
                        Json.item("31", "movie", "Elf"),
                        Json.item("32", "movie", "Home Alone"),
                    )
        }
        val rows = model.containing("31")
        assertEquals(listOf("Christmas"), rows.map { it.occasion.name })
        assertEquals(listOf("32"), rows.single().items.map { it.id })
        assertEquals(
            "date=2026-12-03&itemId=31",
            server.called("GET /profiles/7/occasions").single().query,
        )
    }

    @Test
    fun theWashNeedsAllThreeValidColoursToShow() {
        assertEquals(
            listOf(0xFFC8102E.toInt(), 0xFF00843D.toInt(), 0xFFFFD100.toInt()),
            OccasionWash.palette(listOf("#C8102E", "#00843d", "#FFD100")),
        )
        assertNull(OccasionWash.palette(listOf("#C8102E", "#00843D")))
        assertNull(OccasionWash.palette(emptyList()))
        assertNull(OccasionWash.palette(null))
        assertNull(OccasionWash.palette(listOf("#C8102E", "#00843D", "nope")))
        assertNull(OccasionWash.palette(listOf("C8102E", "#00843D", "#FFD100")))
        assertNull(OccasionWash.palette(listOf("#C8102E0", "#00843D", "#FFD100")))
    }
}
