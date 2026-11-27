package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.Verdict
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogModelTest {
    private val server = FakeServer()
    private val model = CatalogModel(server.api(), profileId = "1")

    private fun library() {
        server.on(
            "GET /items",
            body =
                Json.page(
                    Json.item("1", "movie", "Dune"),
                    Json.item("10", "show", "Severance"),
                    Json.item("11", "season", "Season 1", parent = 10, season = 1),
                    Json.item("101", "episode", "Good News", parent = 11, season = 1, episode = 1),
                    Json.item("102", "episode", "Half Loop", parent = 11, season = 1, episode = 2),
                ),
        )
        server.on("GET /profiles/1/watch-state", body = Json.page(Json.watch(101, watched = true)))
    }

    @Test
    fun loadsEveryKindAndTheWatchState() = runBlocking {
        library()
        model.load()
        assertTrue(model.loaded)
        assertNull(model.error)
        val kinds =
            server.called("GET /items").map {
                Regex("kind=(\\w+)").find(it.query!!)!!.groupValues[1]
            }
        assertEquals(listOf("episode", "movie", "season", "show"), kinds.sorted())
        assertTrue(model.catalog.isWatched("101"))
        assertEquals("102", model.catalog.resumeTarget("10")?.id)
    }

    @Test
    fun aLoadUnderWayGivesWayToALaterOneThatAnswersFirst() = runBlocking {
        library()
        val answer = CountDownLatch(1)
        val held = AtomicInteger()
        server.before = { if (it == "GET /items" && held.incrementAndGet() <= 4) answer.await() }
        val first = launch { model.load() }
        try {
            eventually { server.called("GET /items").size == 4 }
            server.on("GET /items", body = Json.page(Json.item("2", "movie", "Flow")))
            model.load()
            assertEquals(8, server.called("GET /items").size)
            assertEquals("2", model.catalog.item("2")?.id)
            library()
        } finally {
            answer.countDown()
        }
        first.join()
        assertEquals("2", model.catalog.item("2")?.id)
        assertNull(model.catalog.item("1"))
    }

    @Test
    fun aRefreshWaitsOutALoadUnderWayThenReadsAgain() = runBlocking {
        library()
        val answer = CountDownLatch(1)
        server.before = { if (it == "GET /items") answer.await() }
        val first = launch { model.load() }
        eventually { server.called("GET /items").size == 4 }
        server.on("GET /items", body = Json.page(Json.item("2", "movie", "Flow")))
        val refresh = launch { model.reload() }
        delay(50)
        assertEquals(4, server.called("GET /items").size)
        answer.countDown()
        first.join()
        refresh.join()
        assertEquals(8, server.called("GET /items").size)
        assertEquals("2", model.catalog.item("2")?.id)
    }

    /** Answers kidsContent=true as the server does: only kids' titles, whatever Hide says. */
    private fun kidsLibrary() {
        library()
        server.respond = { request ->
            if (request.url.queryParameter("kidsContent") != "true") null
            else
                200 to
                    when (request.url.queryParameter("kind")) {
                        "movie" -> Json.page(Json.item("5", "movie", "Cars", kids = true))
                        "show" -> Json.page(Json.item("30", "show", "Bing", kids = true))
                        "season" ->
                            Json.page(
                                Json.item("31", "season", "Season 1", parent = 30, season = 1),
                            )
                        else ->
                            Json.page(
                                Json.item(
                                    "501",
                                    "episode",
                                    "Shoes",
                                    parent = 31,
                                    season = 1,
                                    episode = 1,
                                ),
                            )
                    }
        }
    }

    private fun kidsCalls() =
        server.called("GET /items").filter { "kidsContent=true" in it.query!! }

    private fun hiding() = CatalogModel(server.api(), profileId = "1", hideKidsContent = true)

    @Test
    fun withHideOffThePillFiltersWhatIsLoadedAndAsksForNothingMore() = runBlocking {
        server.on(
            "GET /items",
            body =
                Json.page(
                    Json.item("1", "movie", "Dune"),
                    Json.item("2", "movie", "Flow", kids = true),
                ),
        )
        server.on("GET /profiles/1/watch-state", body = Json.page())
        model.load()
        model.loadKids(ItemKind.movie)
        assertEquals(listOf("2"), model.catalog.grid(ItemKind.movie, GridFilter.Kids).map { it.id })
        assertTrue(kidsCalls().isEmpty())
    }

    @Test
    fun withHideOnThePillReadsTheKidsTitlesApartFromTheCatalog() = runBlocking {
        kidsLibrary()
        val model = hiding()
        model.load()
        assertTrue(kidsCalls().isEmpty())
        assertTrue(model.catalog.grid(ItemKind.show, GridFilter.Kids).isEmpty())

        model.loadKids(ItemKind.show)
        assertEquals(
            listOf("episode", "season", "show"),
            kidsCalls().map { Regex("kind=(\\w+)").find(it.query!!)!!.groupValues[1] }.sorted(),
        )
        val c = model.catalog
        assertEquals(listOf("30"), c.grid(ItemKind.show, GridFilter.Kids).map { it.id })
        assertEquals(listOf("501"), c.episodes("30").map { it.id })
        assertEquals(listOf("10"), c.grid(ItemKind.show).map { it.id })
        assertTrue(c.recentlyAdded.none { it.id == "30" })
        assertTrue(c.grid(ItemKind.movie, GridFilter.Kids).isEmpty())
    }

    @Test
    fun heldKidsTitlesSurviveAReloadAndAreDroppedWhenHideTurnsOff() = runBlocking {
        kidsLibrary()
        val model = hiding()
        model.load()
        model.loadKids(ItemKind.movie)
        model.reload()
        assertEquals(listOf("5"), model.catalog.grid(ItemKind.movie, GridFilter.Kids).map { it.id })

        model.hideKids(false)
        assertFalse(model.hideKidsContent)
        assertNull(model.catalog.item("5"))
        assertTrue(model.catalog.grid(ItemKind.movie, GridFilter.Kids).isEmpty())
    }

    @Test
    fun turningHideOnReloadsTheCatalogOnceAndNotAgainForTheSameAnswer() = runBlocking {
        library()
        model.load()
        assertEquals(4, server.called("GET /items").size)
        model.hideKids(true)
        assertTrue(model.hideKidsContent)
        assertEquals(8, server.called("GET /items").size)
        model.hideKids(true)
        assertEquals(8, server.called("GET /items").size)
    }

    @Test
    fun asksForEveryKindAndTheWatchStateAtOnce() = runBlocking {
        library()
        val arrived = CountDownLatch(5)
        val together = mutableListOf<Boolean>()
        server.before = {
            arrived.countDown()
            val all = arrived.await(1, TimeUnit.SECONDS)
            synchronized(together) { together += all }
        }
        model.load()
        assertEquals(listOf(true, true, true, true, true), together)
    }

    @Test
    fun followsNextOffsetToTheEnd() = runBlocking {
        server.on("GET /items", body = """{"items":[],"nextOffset":200}""")
        server.on("GET /profiles/1/watch-state", body = Json.page())
        val calls = mutableListOf<Int?>()
        val all = pages { offset ->
            calls += offset
            if (offset == null) listOf(1) to 200 else listOf(2) to null
        }
        assertEquals(listOf(1, 2), all)
        assertEquals(listOf(null, 200), calls)
    }

    @Test
    fun aFailedLoadSaysWhy() = runBlocking {
        server.on("GET /items", 500, "")
        model.load()
        assertTrue(model.loaded)
        assertEquals("The server answered 500.", model.error)
    }

    @Test
    fun markingASeasonWatchedMarksEachEpisode() = runBlocking {
        library()
        model.load()
        server.on("PUT /profiles/1/watch-state/101", body = Json.watch(101, watched = true))
        server.on("PUT /profiles/1/watch-state/102", body = Json.watch(102, watched = true))
        model.setWatched(true, model.catalog.item("11")!!)
        assertEquals(1, server.called("PUT /profiles/1/watch-state/102").size)
        assertTrue(
            server
                .called("PUT /profiles/1/watch-state/102")
                .single()
                .body
                .contains(""""watched":true"""),
        )
        assertTrue(model.catalog.isWatched("102"))
        assertEquals(0, model.catalog.unwatchedCount("10"))
    }

    @Test
    fun searchIsOneUniversalQueryWithNineLists() = runBlocking {
        server.on(
            "GET /search",
            body =
                """{"movies":[${Json.item("1", "movie", "Dune")}],"shows":[${Json.item("2", "show", "Dune Show")}],""" +
                    """"episodes":[${Json.item("3", "episode", "Dune Day")}],""" +
                    """"people":[],"artists":[],"albums":[],"songs":[],"channels":[],"videos":[]}""",
        )
        val found = model.search(" dune ")!!
        assertEquals(listOf("1"), found.movies.map { it.id })
        assertEquals(listOf("3", "2"), found.tv.map { it.id })
        assertTrue(server.calls.single().query!!.contains("q=dune"))
        assertFalse(found.isEmpty)
        assertNull(model.search("  "))
        assertEquals(1, server.calls.size)
    }

    @Test
    fun aSearchANewerQueryCancelledShowsNothingThoughItsAnswerCameAfter() = runBlocking {
        server.on(
            "GET /search",
            body =
                """{"movies":[${Json.item("1", "movie", "Dune")}],"shows":[],"episodes":[],""" +
                    """"people":[],"artists":[],"albums":[],"songs":[],"channels":[],"videos":[]}""",
        )
        val answer = CountDownLatch(1)
        server.before = { if (it == "GET /search") answer.await() }
        val shown = mutableListOf<Any?>()
        val older = launch { model.searchTyped("dun") { shown += it } }
        try {
            eventually { server.called("GET /search").size == 1 }
            older.cancel()
        } finally {
            answer.countDown()
        }
        older.join()
        assertEquals(emptyList<Any?>(), shown)
    }

    private fun flagged(id: String, on: Boolean, override: Boolean?) =
        Json.item(id, "movie", "Dune").dropLast(1) +
            ""","kidsContent":$on""" +
            (override?.let { ""","kidsContentOverride":$it""" } ?: "") +
            "}"

    @Test
    fun kidsContentIsOverriddenAndGivenBackToTheRating() = runBlocking {
        library()
        model.load()
        server.on("PUT /items/1/kids-content", body = flagged("1", true, true))
        model.setKidsContent(model.catalog.item("1")!!, true)
        assertTrue(server.called("PUT /items/1/kids-content").single().body.contains("true"))
        assertEquals(true, model.catalog.item("1")?.kidsContent)
        assertEquals(true, model.catalog.item("1")?.kidsContentOverride)

        server.on("DELETE /items/1/kids-content", 204)
        server.on("GET /items/1", body = flagged("1", false, null))
        model.useRating("1")
        assertEquals(false, model.catalog.item("1")?.kidsContent)
        assertNull(model.catalog.item("1")?.kidsContentOverride)
        assertNull(model.error)
    }

    @Test
    fun relatedTakesARowPerCollectionAndNeighbors() = runBlocking {
        server.on(
            "GET /items/2/related",
            body =
                """{"collections":[""" +
                    """{"collection":${Json.collection("7", "The Lord of the Rings", "tmdb")},""" +
                    """"items":[${Json.item("1", "movie", "Fellowship")},${Json.item("3", "movie", "Return")}]},""" +
                    """{"collection":${Json.collection("9", "Empty")},"items":[]},""" +
                    """{"collection":${Json.collection("8", "Epics")},"items":[${Json.item("4", "movie", "Ben-Hur")}]}],""" +
                    """"similar":[${Json.item("5", "movie", "Willow")}],""" +
                    """"previous":${Json.item("1", "movie", "Fellowship")},"next":${Json.item("3", "movie", "Return")}}""",
        )
        val related = model.related("2")!!
        assertEquals("1", related.previous?.id)
        assertEquals("3", related.next?.id)
        val rows = Catalog.related(related)
        assertEquals(listOf("The Lord of the Rings", "Epics"), rows.map { it.collection.name })
        assertEquals(
            listOf(listOf("1", "3"), listOf("4")),
            rows.map { row -> row.items.map { it.id } },
        )
    }

    @Test
    fun anEmptyRelatedAnswerHasNoRowAndNoNeighbors() = runBlocking {
        server.on("GET /items/2/related", body = """{"collections":[],"similar":[]}""")
        val related = model.related("2")!!
        assertNull(related.previous)
        assertNull(related.next)
        assertTrue(Catalog.related(related).isEmpty())
    }

    /** Loading and a failed read, offline included, both leave the answer null. */
    @Test
    fun aFailedRelatedReadHasNoRow() = runBlocking {
        server.on("GET /items/2/related", 500, "")
        assertNull(model.related("2"))
        server.error("GET /items/2/related", 404, "not_found")
        assertNull(model.related("2"))
        assertTrue(Catalog.related(null).isEmpty())
    }

    @Test
    fun aPersonComesWithEveryTitleCreditingThem() = runBlocking {
        server.on("GET /people/7", body = """{"id":"7","name":"Rebecca Ferguson"}""")
        server.on(
            "GET /items",
            body = Json.page(Json.item("1", "movie", "Dune"), Json.item("10", "show", "Silo")),
        )
        val (person, titles) = model.person("7")!!
        assertEquals("Rebecca Ferguson", person.name)
        assertEquals(listOf("1", "10"), titles.map { it.id })
        assertTrue(server.called("GET /items").single().query!!.contains("personId=7"))

        server.on("GET /people/7", 404, "")
        assertNull(model.person("7"))
    }

    @Test
    fun theVerdictIsAskedWithThisDevicesSnapshotAndNullWhenUnanswered() = runBlocking {
        server.on(
            "POST /items/1/playability",
            body =
                """{"verdict":"video_transcode","negotiated":{"verdict":"video_transcode",""" +
                    """"width":1920,"height":1080,"bitrate":8000000}}""",
        )
        val facts = DeviceFacts(emptyList(), null, emptyList(), PathFacts())
        assertEquals(Verdict.video_transcode, model.verdict("1", facts))
        assertTrue(server.calls.single().body.contains(""""auto":true"""))

        server.on("POST /items/1/playability", 500, "")
        assertNull(model.verdict("1", facts))
    }

    private fun file(id: String, name: String) =
        """{"id":$id,"itemId":"1","path":"/media/$name.mkv","sizeBytes":1000,""" +
            """"fileModifiedAt":"${Json.T}","createdAt":"${Json.T}","modifiedAt":"${Json.T}","chapters":[],"tracks":[]}"""

    @Test
    fun aNamedFileIsReadItselfAndNoneReadsTheItemsFirst() = runBlocking {
        server.on("GET /media-files/2", body = file("2", "Extended"))
        server.on("GET /media-files", body = Json.page(file("1", "Standard")))
        assertEquals("2", model.media("1", "2")?.file?.id)
        assertEquals("1", model.media("1")?.file?.id)
    }
}
