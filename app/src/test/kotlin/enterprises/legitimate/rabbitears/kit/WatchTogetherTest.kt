package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.ThroughputTelemetry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTogetherTest {
    class Output : VideoOutput {
        val seeks = mutableListOf<Long>()
        override var positionMs = 0L
        override var isPaused = true
        override var isBuffering = false
        override var onFailure: ((String) -> Unit)? = null
        override var onStall: (() -> Unit)? = null

        override fun load(url: String, startMs: Long, hls: Boolean, bufferGoalMs: Long) = Unit

        override fun play() {
            isPaused = false
        }

        override fun pause() {
            isPaused = true
        }

        override fun stop() = Unit

        override fun seek(ms: Long) {
            seeks += ms
            positionMs = ms
        }

        val scheduled = mutableListOf<Triple<Boolean, Long, Long>>()

        override fun schedule(playing: Boolean, positionMs: Long, afterMs: Long) {
            scheduled += Triple(playing, positionMs, afterMs)
        }

        override fun telemetry() = ThroughputTelemetry(0, 0, 0)
    }

    private val server = FakeServer()
    private var clock = 1_000_000L
    // Profile 2 is Mary; Helga (1) hosts.
    private val model = WatchTogetherModel(server.api(), "2", "101") { clock }

    private fun member(id: String, name: String, role: String) =
        """{"accountId":$id,"name":"$name","role":"$role","deviceName":"Apple TV",""" +
            """"platform":"tvos","present":true,"joinedAt":"${Json.T}"}"""

    private fun together(
        status: String = "waiting",
        playing: Boolean = false,
        position: Long = 0,
        version: Int = 1,
        guest: Boolean = false,
        pausedBy: Long? = null,
        effectiveAt: Long = 1_000_000,
        serverTime: Long = 1_000_000,
    ): String {
        val members =
            listOfNotNull(
                member("1", "Helga", "host"),
                member("2", "Mary", "guest").takeIf { guest },
            )
        return """{"id":"5","itemId":"101","status":"$status","members":[${members.joinToString(",")}],""" +
            """"joinBy":"${Json.T}","joinMsLeft":252000,"playing":$playing,"positionMs":$position,""" +
            """"version":$version,"serverTime":"${Json.T}","openedAt":"${Json.T}",""" +
            """"serverTimeMs":$serverTime,"effectiveAtMs":$effectiveAt,"effectivePositionMs":$position""" +
            (pausedBy?.let { ""","pausedById":$it,"pausedByName":"Helga"""" } ?: "") +
            "}"
    }

    @Test
    fun anotherAdultsWaitingOneOffersJoinWithItsCountdown() = runBlocking {
        server.on("GET /watch-together", body = Json.page(together()))
        model.refresh()
        assertEquals("Join Helga · 4:12", model.joinLabel())
        clock += 12_000
        assertEquals("Join Helga · 4:00", model.joinLabel())
        assertTrue(server.calls.single().query!!.contains("itemId=101"))
    }

    @Test
    fun joiningPutsThisAdultInSyncAndPlayingOpensThePlayerOnce() = runBlocking {
        server.on("GET /watch-together", body = Json.page(together()))
        model.refresh()
        server.on("POST /watch-together/5/join", body = together("joined", guest = true))
        model.join()
        assertTrue(model.inSync)
        assertNull(model.joinLabel())
        assertEquals("Helga", model.partner?.name)
        val starts = mutableListOf<Long>()
        model.onStart = { starts += it }
        server.on(
            "GET /watch-together/5",
            body = together("joined", playing = true, position = 30_000, version = 2, guest = true),
        )
        model.poll()
        model.poll()
        assertEquals(listOf(30_000L), starts)
    }

    @Test
    fun theAttachedPlayerFollowsPlayingAndSeeksOnlyPastHalfASecond() = runBlocking {
        server.on(
            "GET /watch-together",
            body = Json.page(together("joined", playing = true, position = 60_000, guest = true)),
        )
        model.refresh()
        val output = Output().apply { positionMs = 59_700 }
        model.attach(output)
        assertFalse(output.isPaused)
        assertTrue(output.seeks.isEmpty())
        server.on(
            "GET /watch-together/5",
            body = together("joined", position = 90_000, version = 3, guest = true, pausedBy = 1),
        )
        model.poll()
        assertTrue(output.isPaused)
        assertEquals(listOf(90_000L), output.seeks)
        assertEquals("Helga paused", model.pausedNotice)
    }

    @Test
    fun aBufferingPlayerIsLeftToCatchUp() = runBlocking {
        server.on(
            "GET /watch-together",
            body = Json.page(together("joined", playing = true, position = 60_000, guest = true)),
        )
        model.refresh()
        val output = Output().apply { isBuffering = true }
        model.attach(output)
        server.on(
            "GET /watch-together/5",
            body = together("joined", playing = true, position = 63_000, version = 2, guest = true),
        )
        model.poll()
        assertTrue(output.seeks.isEmpty())
        output.isBuffering = false
        model.poll()
        assertEquals(listOf(63_000L), output.seeks)
    }

    @Test
    fun anOlderAnswerIsDroppedAndPlaySendsThePosition() = runBlocking {
        server.on(
            "GET /watch-together",
            body = Json.page(together("joined", position = 11_000, version = 4, guest = true)),
        )
        model.refresh()
        val output = Output().apply { positionMs = 11_200 }
        model.attach(output)
        server.on(
            "PUT /watch-together/5/state",
            body = together("joined", playing = true, position = 11_200, version = 5, guest = true),
        )
        model.play()
        assertTrue(server.called("PUT /watch-together/5/state").single().body.contains("11200"))
        assertFalse(output.isPaused)
        server.on("GET /watch-together/5", body = together("joined", version = 3, guest = true))
        model.poll()
        assertTrue(model.current!!.playing)
    }

    @Test
    fun openingWaitsAndLeavingOrEndingClearsIt() = runBlocking {
        val mine =
            together()
                .replace(""""accountId":"1","name":"Helga"""", """"accountId":"2","name":"Mary"""")
        server.on("POST /watch-together", 201, mine)
        model.open()
        assertEquals("Waiting · 4:12", model.waitingLabel())
        server.on("DELETE /watch-together/5", 204)
        model.leave()
        assertNull(model.current)
        assertEquals(1, server.called("DELETE /watch-together/5").size)
        server.on("POST /watch-together", 201, mine)
        model.open()
        server.error("GET /watch-together/5", 404, "not_found")
        model.poll()
        assertNull(model.current)
    }

    private suspend fun joined(): Output {
        server.on(
            "GET /watch-together",
            body = Json.page(together("joined", version = 2, guest = true)),
        )
        model.refresh()
        return Output().also(model::attach)
    }

    @Test
    fun aChangeAheadIsScheduledOnThePlayerForItsInstant() = runBlocking {
        val output = joined()
        server.on(
            "GET /watch-together/5",
            body =
                together(
                    "joined",
                    playing = true,
                    position = 30_000,
                    version = 3,
                    guest = true,
                    effectiveAt = 1_001_500,
                ),
        )
        model.poll()
        assertTrue(output.isPaused)
        assertEquals(listOf(Triple(true, 30_000L, 1_500L)), output.scheduled)
        assertEquals(30_000L, model.sharedPositionMs())
    }

    @Test
    fun theClockComesFromAQuickPollAndAHeldPollWaitsForTheNextVersion() = runBlocking {
        joined()
        server.on(
            "GET /watch-together/5",
            body = together("joined", version = 2, guest = true, serverTime = 1_060_000),
        )
        model.poll()
        assertEquals(60_000L, model.offsetMs)
        model.poll(held = true)
        assertEquals(
            listOf(null, "after=2&rttMs=0"),
            server.called("GET /watch-together/5").map { it.query },
        )
    }

    @Test
    fun theLatestQuickRoundTripIsReportedOnHeldPollsAndActions() = runBlocking {
        joined()
        val state = together("joined", version = 2, guest = true)
        server.on("GET /watch-together/5", body = state)
        server.on("PUT /watch-together/5/state", body = state)
        model.play()
        model.poll(held = true)
        model.poll()
        model.poll(held = true)
        model.play()
        assertEquals(
            listOf(null, "rttMs=0"),
            server.called("PUT /watch-together/5/state").map { it.query },
        )
        assertEquals(
            listOf("after=2&rttMs=0", "rttMs=0", "after=2&rttMs=0"),
            server.called("GET /watch-together/5").map { it.query },
        )
    }

    @Test
    fun aHoldThatRanOutIsAskedAgainAtOnce() = runBlocking {
        var ticks = 0L
        val ticking =
            WatchTogetherModel(server.api(), "2", "101") {
                ticks += 30_000
                1_000_000 + ticks
            }
        server.on("POST /watch-together", status = 201, body = together(guest = true))
        server.on("GET /watch-together/5", body = together("joined", version = 2, guest = true))
        ticking.open()
        withTimeoutOrNull(400) { ticking.run() }
        val held =
            server.called("GET /watch-together/5").count { it.query?.contains("after=") == true }
        assertTrue("an unchanged answer that took 30 s waits for nothing: $held", held > 2)
    }

    @Test
    fun aPauseStopsThisPlayerAtOnceAndIsSentWithItsPosition() = runBlocking {
        val output =
            joined().apply {
                isPaused = false
                positionMs = 8_000
            }
        server.on(
            "PUT /watch-together/5/state",
            body =
                together(
                    "joined",
                    position = 8_000,
                    version = 3,
                    guest = true,
                    effectiveAt = 1_001_000,
                ),
        )
        model.pause()
        assertTrue(output.isPaused)
        assertTrue(server.called("PUT /watch-together/5/state").single().body.contains("8000"))
        assertEquals(listOf(Triple(false, 8_000L, 1_000L)), output.scheduled)
    }
}
