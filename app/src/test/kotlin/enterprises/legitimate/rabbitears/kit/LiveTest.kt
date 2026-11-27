package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Event
import enterprises.legitimate.rabbitears.api.models.EventKind
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTest {
    private val server = FakeServer()
    private val feed = LiveFeed()
    private val api = server.api()
    private val waits = mutableListOf<Long>()

    private fun page(cursor: String, vararg events: String, resync: Boolean = false) =
        200 to """{"cursor":"$cursor","resync":$resync,"events":[${events.joinToString(",")}]}"""

    private val down = 500 to """{"code":"internal","message":"x"}"""

    /** Answers each poll with the next of [answers]; once they run out the stream is gone. */
    private suspend fun follow(vararg answers: Pair<Int, String>) {
        val left = answers.toMutableList()
        server.before = {
            val (status, body) =
                left.removeFirstOrNull() ?: (404 to """{"code":"not_found","message":"x"}""")
            server.on("GET /events", status, body)
        }
        feed.follow(api) { waits += it }
    }

    private fun counts() = listOf(feed.watched, feed.stations, feed.playlists, feed.together)

    private fun asked() =
        server.called("GET /events").map { call ->
            call.query?.split("&")?.firstOrNull { it.startsWith("after=") }
        }

    @Test
    fun asksForACursorThenHoldsAPollFromEachAnswersCursor() = runBlocking {
        val heard =
            launch(start = CoroutineStart.UNDISPATCHED) {
                val batches = feed.events.take(2).toList()
                assertEquals(
                    listOf(
                        listOf(EventKind.watch_state),
                        listOf(EventKind.station, EventKind.playlist),
                    ),
                    batches.map { batch -> batch.map { it.kind } },
                )
                assertEquals(20L, batches[0][0].itemId)
            }
        follow(
            page("c0"),
            page("c1", """{"kind":"watch_state","itemId":20}"""),
            page("c2"),
            page("c3", """{"kind":"station","stationId":4}""", """{"kind":"playlist"}"""),
        )
        heard.join()
        assertEquals(listOf(null, "after=c0", "after=c1", "after=c2", "after=c3"), asked())
        assertEquals(listOf(1, 1, 1, 0), counts())
    }

    @Test
    fun aResyncLoadsEverythingAgain() = runBlocking {
        follow(page("c0"), page("c1", resync = true))
        assertEquals(listOf(1, 1, 1, 1), counts())
    }

    @Test
    fun aFailedPollIsAskedAgainFromTheSameCursorAfterAGrowingWait() = runBlocking {
        follow(page("c0"), down, down, down, page("c1", """{"kind":"playlist"}"""), down)
        assertEquals(listOf(1000L, 2000L, 4000L, 1000L), waits)
        assertEquals(4, asked().count { it == "after=c0" })
        assertEquals(1, feed.playlists)
    }

    @Test
    fun onlyTheServersOwnUnauthorizedSaysTheDeviceIsSignedOut() = runBlocking {
        server.on("GET /events", 401, "<html>401 Authorization Required</html>")
        assertFalse(feed.follow(api) { waits += it })
        server.error("GET /events", 401, "unauthorized")
        assertTrue(feed.follow(api) { waits += it })
    }

    @Test
    fun aPollRefusedForNowIsAskedAgainAfterAWait() = runBlocking {
        follow(
            page("c0"),
            429 to """{"code":"too_many_requests","message":"x"}""",
            408 to """{"code":"timeout","message":"x"}""",
            page("c1", """{"kind":"playlist"}"""),
        )
        assertEquals(listOf(1000L, 2000L), waits)
        assertEquals(1, feed.playlists)
    }

    @Test
    fun aLockedDeviceIsNotAskedAgain() = runBlocking {
        follow(403 to """{"code":"device_locked","message":"x"}""", page("c0"))
        assertEquals(1, asked().size)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun followingAgainOnReturnResumesFromTheLastCursorAndLoadsNothingUnlessTheServerSays() =
        runBlocking {
            follow(page("c0"))
            follow(page("c5"), page("c6", """{"kind":"playlist"}"""))
            assertEquals(listOf(0, 0, 1, 0), counts())
            assertEquals(listOf(null, "after=c0", "after=c0", "after=c5", "after=c6"), asked())

            follow(page("c9", resync = true))
            assertEquals(listOf(1, 1, 2, 1), counts())
        }

    @Test
    fun anotherSessionStartsItsOwnStreamAndLoadsNothingOnTheWay() = runBlocking {
        follow(page("c0"))
        val answers = mutableListOf(page("d0"))
        server.before = {
            val (status, body) =
                answers.removeFirstOrNull() ?: (404 to """{"code":"not_found","message":"x"}""")
            server.on("GET /events", status, body)
        }
        feed.follow(server.api()) { waits += it }
        assertEquals(listOf(null, "after=c0", null, "after=d0"), asked())
        assertEquals(listOf(0, 0, 0, 0), counts())
    }

    @Test
    fun aLibraryChangeIsAskedForAndHeard() = runBlocking {
        follow(page("c0"))
        assertTrue(server.called("GET /events").all { it.query!!.contains("kinds=library") })
        val model = CatalogModel(server.api(), 2)
        server.on("GET /items", body = Json.page(Json.item(20, "movie", "Dune")))
        server.on("GET /profiles/2/watch-state", body = Json.page())
        model.heard(listOf(event("""{"kind":"library","libraryId":1}""")))
        assertEquals(20L, model.catalog.item(20)?.id)
    }

    @Test
    fun aHouseholdChangeIsAskedForAndHeard() = runBlocking {
        follow(page("c0"), page("c1", """{"kind":"household"}"""), page("c2", resync = true))
        assertTrue(server.called("GET /events").all { it.query!!.contains("kinds=household") })
        assertEquals(2, feed.household)
        assertEquals(listOf(1, 1, 1, 1), counts())
    }

    private fun playing(
        id: Long = 9,
        thisDevice: Boolean = false,
        position: Long = 2_472_000,
        movedTo: String? = null,
    ) =
        """{"id":$id,"itemId":20,"deviceId":4,"deviceName":"Living Room","platform":"roku",""" +
            """"thisDevice":$thisDevice,"positionMs":$position,"updatedAt":"${Json.T}"""" +
            (movedTo?.let { ""","endedAt":"${Json.T}","movedToDeviceName":"$it"""" } ?: "") +
            "}"

    @Test
    fun aTitlePlayingOnAnotherDeviceIsAskedAbout() = runBlocking {
        server.on(
            "GET /playback/sessions",
            body = Json.page(playing(3, thisDevice = true), playing()),
        )
        val there = Handoff.elsewhere(server.api(), 20)!!
        assertTrue(server.calls.single().query!!.contains("itemId=20"))
        assertEquals(9L, there.id)
        assertEquals("Playing on Living Room at 41:12", Handoff.title(there))
        server.on("GET /playback/sessions", body = Json.page(playing(3, thisDevice = true)))
        assertNull(Handoff.elsewhere(server.api(), 20))
        server.error("GET /playback/sessions", 404, "not_found")
        assertNull(Handoff.elsewhere(server.api(), 20))
    }

    @Test
    fun aCheckThatNeverAnswersLetsPlaybackStartAfterTheLimit() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val api = Api(ServerEndpoint("http://127.0.0.1:${silent.localPort}"), "tok")
            val started = System.nanoTime()
            assertNull(Handoff.elsewhere(api, 20, limitMs = 50))
            assertTrue("well before the read timeout", System.nanoTime() - started < 2_000_000_000)
        }
    }

    @Test
    fun anAnswerInsideTheLimitIsStillAskedAboutAndOneAfterItIgnored() = runBlocking {
        server.on("GET /playback/sessions", body = Json.page(playing()))
        server.before = { Thread.sleep(10) }
        assertEquals(9L, Handoff.elsewhere(server.api(), 20, limitMs = 30_000)?.id)
        server.before = { Thread.sleep(300) }
        assertNull(Handoff.elsewhere(server.api(), 20, limitMs = 50))
    }

    @Test
    fun moveHereEndsItThereAndSaysWhereItWas() = runBlocking {
        server.on("GET /playback/sessions", body = Json.page(playing()))
        val there = Handoff.elsewhere(server.api(), 20)!!
        server.on("POST /playback/sessions/9/move", body = playing(position = 2_480_000))
        assertEquals(2_480_000L, Handoff.move(server.api(), there))
    }

    private fun event(json: String): Event =
        Serializer.kotlinxSerializationJson.decodeFromString(Event.serializer(), json)

    @Test
    fun aPlayerHearsOnlyOfItsOwnSession() = runBlocking {
        val moved = event("""{"kind":"playback","change":"moved","sessionId":7,"itemId":20}""")
        val ended = event("""{"kind":"playback","change":"ended","sessionId":7,"itemId":20}""")
        val opened =
            event(
                """{"kind":"watch_together","change":"opened","watchTogetherId":5,"sessionId":7}""",
            )
        assertTrue(Handoff.moved(listOf(ended, moved), 7))
        assertFalse(Handoff.moved(listOf(moved), 8))
        assertFalse(Handoff.moved(listOf(ended, opened), 7))
        assertEquals(5L, Handoff.asked(listOf(moved, opened), 7))
        assertNull(Handoff.asked(listOf(opened), 8))
        server.on("GET /playback/sessions/7", body = playing(7, movedTo = "Kitchen iPad"))
        assertEquals("Kitchen iPad", Handoff.movedTo(server.api(), 7))
    }

    private fun member(device: String, role: String, thisDevice: Boolean) =
        """{"accountId":2,"name":"Mary","role":"$role","deviceName":"$device","platform":"roku",""" +
            """"present":true,"joinedAt":"${Json.T}","thisDevice":$thisDevice}"""

    private fun together(status: String, vararg members: String, playing: Boolean = false) =
        """{"id":5,"itemId":20,"status":"$status","members":[${members.joinToString(",")}],""" +
            """"joinBy":"${Json.T}","joinMsLeft":252000,"playing":$playing,"positionMs":0,"version":1,""" +
            """"serverTime":"${Json.T}","openedAt":"${Json.T}","serverTimeMs":1000000,""" +
            """"effectiveAtMs":1000000,"effectivePositionMs":0}"""

    @Test
    fun oneProfileOnTwoDevicesWatchesTogether() = runBlocking {
        val there = member("Living Room", "host", false)
        val here = member("Pixel", "guest", true)
        val model = WatchTogetherModel(server.api(), 2, 20) { 1_000_000L }
        // Both players are open already; the start that follows must open no other.
        var opened = 0
        model.onStart = { opened++ }
        server.on("GET /watch-together", body = Json.page(together("waiting", there)))
        model.refresh()
        assertNull(model.current)
        assertEquals("Join Living Room · 4:12", model.joinLabel())

        server.on("POST /watch-together/5/join", body = together("joined", there, here))
        server.on(
            "PUT /watch-together/5/state",
            body = together("joined", there, here, playing = true),
        )
        assertTrue(model.join(5, 61_000))
        assertTrue(model.inSync)
        assertEquals("Living Room", model.partnerName)
        assertTrue(server.called("PUT /watch-together/5/state").single().body.contains("61000"))

        val host = WatchTogetherModel(server.api(), 2, 20) { 1_000_000L }
        host.onStart = { opened++ }
        server.on("POST /watch-together", 201, together("waiting", member("Pixel", "host", true)))
        assertTrue(host.open(9))
        assertTrue(
            server.called("POST /watch-together").single().body.contains(""""fromSessionId":9"""),
        )
        assertEquals(5L, host.current?.id)
        server.on("GET /watch-together/5", body = together("joined", there, here, playing = true))
        host.poll()
        assertTrue(host.inSync)
        assertEquals(0, opened)
    }

    @Test
    fun theCatalogTakesTheWatchStateAnEventNamesAndForgetsOneTheServerDropped() = runBlocking {
        val model = CatalogModel(server.api(), 2)
        server.on("GET /items", body = Json.page(Json.item(20, "movie", "Dune")))
        server.on("GET /profiles/2/watch-state", body = Json.page())
        model.load()
        server.on(
            "GET /profiles/2/watch-state/20",
            body =
                """{"itemId":20,"positionMs":61000,"watched":false,"playCount":0,"updatedAt":"${Json.T}"}""",
        )
        model.heard(listOf(event("""{"kind":"watch_state","itemId":20}""")))
        assertEquals(61_000L, model.catalog.watchState(20)?.positionMs)
        server.error("GET /profiles/2/watch-state/20", 404, "not_found")
        model.heard(listOf(event("""{"kind":"playback","change":"ended","itemId":20}""")))
        assertNull(model.catalog.watchState(20))
    }
}
