package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.api.models.Station
import enterprises.legitimate.rabbitears.api.models.StationStatus
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A tuner whose clock stands at 20:20 UTC and whose waits are held until the test lets them pass,
 * so each wait's length can be read and its end chosen.
 */
class HeldTuner(server: FakeServer, station: Station, scope: CoroutineScope) {
    val waits = Channel<Long>(Channel.UNLIMITED)
    val wake = Channel<Unit>()
    val tuner =
        StationTuner(server.api(), station, scope, { Instant.parse("2025-01-03T20:20:00Z") }) {
            waits.send(it)
            wake.receive()
        }
}

/** Polls until [done], for what the players do on their own scope. */
suspend fun eventually(done: () -> Boolean) = withTimeout(2_000) { while (!done()) delay(5) }

class StationPlayerTest {
    private val server = FakeServer()
    private val output = PlayerModelTest.Output()
    private val capabilities =
        PlayerModelTest.Capabilities(
            DeviceFacts(
                listOf(CodecFacts("video/avc", true, listOf(8 to 0x8000), 1920, 1080, 60.0)),
                ScreenFacts(2400, 1080, 120.0),
                emptyList(),
                PathFacts(),
            ),
        )
    private val scope = CoroutineScope(Job())
    private val onAir =
        StationJson.onAir("on_air", StationJson.lantern, StationJson.salt, 4_800_000)
    private val held = HeldTuner(server, StationJson.decode(StationJson.station("3", onAir)), scope)
    private val model =
        PlayerModel(server.api(), "1", "0", output, capabilities, scope, station = held.tuner)

    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    @After fun cancel() = scope.cancel()

    private fun answers(session: Long, position: Long = 4_800_000) {
        server.on("POST /stations/3/playback", 201, StationJson.playback(session, position, onAir))
        server.on("DELETE /playback/sessions/$session", 204)
    }

    @Test
    fun tuningInPlaysTheLiveStreamAndWaitsForTheProgramsEnd() = runBlocking {
        answers(7)
        model.start(0)
        assertTrue(server.called("POST /playback/sessions").isEmpty())
        assertTrue(server.called("POST /stations/3/playback").single().body.contains("\"h264\""))
        assertEquals(
            Triple("http://media.example.com:8477/api/v2/streams/s7/file", 4_800_000L, false),
            output.loads.single(),
        )
        // 21:00 is 40 minutes after the clock's 20:20.
        assertEquals(2_400_000L, held.waits.receive())
        assertEquals(StationStatus.on_air, held.tuner.onAir.status)
    }

    private fun live(onAir: String) =
        """{"stream":${StationJson.stream(7, 0).dropLast(1)},"live":true},"onAir":$onAir}"""

    @Test
    fun aVideoStationPlaysItsOneStreamAndOnlyReadsWhatIsOnNext() = runBlocking {
        server.on("POST /stations/3/playback", 201, live(onAir))
        val later =
            StationJson.program("9", "Later", "2025-01-03T21:30:00Z", "2025-01-03T23:00:00Z")
        val between = StationJson.onAir("between_programs", next = later)
        server.on("GET /stations/3", 200, StationJson.station("3", between))
        model.start(0)
        assertTrue(
            server.called("POST /stations/3/playback").single().body.contains("\"live\":true"),
        )
        // A negative start joins the stream at its live edge.
        assertEquals(-1L, output.loads.single().second)
        assertEquals(2_400_000L, held.waits.receive())
        held.wake.send(Unit)
        // The ident plays until 21:30, which is 70 minutes after the clock's 20:20.
        assertEquals(4_200_000L, held.waits.receive())
        assertEquals(StationStatus.between_programs, held.tuner.onAir.status)
        assertEquals(1, server.called("POST /stations/3/playback").size)
        assertEquals("7", model.stream?.sessionId)
        assertEquals(1, output.loads.size)
    }

    @Test
    fun aLiveStreamsEndWaitsForItsProgramsEndThenAsksAgain() = runBlocking {
        server.on("POST /stations/3/playback", 201, live(onAir))
        server.on("GET /stations/3", 200, StationJson.station("3", onAir))
        server.on("DELETE /playback/sessions/7", 204)
        model.start(0)
        held.waits.receive()
        val ended = launch { model.programEnded() }
        // The player ran out ahead of the schedule: 21:00 is still 40 minutes off.
        assertEquals(2_400_000L, held.waits.receive())
        assertEquals(1, server.called("POST /stations/3/playback").size)
        server.on("POST /stations/3/playback", 409, StationJson.offAir(null))
        // The tuner's own wait, which reads the station, then the program's end.
        held.wake.send(Unit)
        held.waits.receive()
        held.wake.send(Unit)
        ended.join()
        assertNull(model.stream)
        assertEquals(2, server.called("POST /stations/3/playback").size)
    }

    @Test
    fun aProgramThisClockSaysIsOverIsAskedAboutASecondLater() = runBlocking {
        // The server still plays it: this device's clock runs ahead.
        val over =
            StationJson.program(
                "7",
                "The Lantern Keeper",
                "2025-01-03T19:00:00Z",
                "2025-01-03T20:19:00Z",
            )
        server.on(
            "POST /stations/3/playback",
            201,
            StationJson.playback(7, 4_800_000, StationJson.onAir("on_air", over)),
        )
        model.start(0)
        assertEquals(1_000L, held.waits.receive())
    }

    @Test
    fun theProgramsEndEndsItsSessionAndAsksAgainOnce() = runBlocking {
        answers(7)
        model.start(0)
        held.waits.receive()
        output.positionMs = 7_200_000
        answers(8, position = 0)
        held.wake.send(Unit)
        eventually { model.stream?.sessionId == "8" }
        assertTrue(
            server.called("DELETE /playback/sessions/7").single().query!!.contains("7200000"),
        )
        assertEquals(0L, output.loads.last().second)
        assertEquals(2, server.called("POST /stations/3/playback").size)
    }

    @Test
    fun resumingArmsTheSameWaitAgain() = runBlocking {
        answers(7)
        model.start(0)
        assertEquals(2_400_000L, held.waits.receive())
        held.tuner.resume()
        assertEquals(2_400_000L, held.waits.receive())
        answers(8, position = 0)
        held.wake.send(Unit)
        eventually { model.stream?.sessionId == "8" }
        assertEquals(2, server.called("POST /stations/3/playback").size)
    }

    @Test
    fun aSubtitlePickEndsWithItsProgram() = runBlocking {
        answers(7)
        model.start(0)
        held.waits.receive()
        server.on("PUT /playback/sessions/7/position", 204)
        server.on(
            "PUT /playback/sessions/7/tracks",
            body = """{"action":"continue","stream":${StationJson.stream(7, 4_800_000)}}""",
        )
        model.selectSubtitles(SubtitleChoice.Track("5"))
        answers(8, position = 0)
        held.wake.send(Unit)
        eventually { model.stream?.sessionId == "8" }
        // The next program is another file: there the track names nothing, which the server
        // reads as no subtitles at all.
        val next = server.called("POST /stations/3/playback").last().body
        assertTrue(next.contains(""""subtitleTrackId":null"""))
        assertEquals(SubtitleChoice.ForcedOnly, model.subtitleChoice)
    }

    @Test
    fun theStreamRunningOutAndTheEndTimeTogetherAskOnce() = runBlocking {
        answers(7)
        model.start(0)
        answers(8, position = 0)
        coroutineScope {
            launch { model.programEnded() }
            launch { model.programEnded() }
        }
        assertEquals(1, server.called("DELETE /playback/sessions/7").size)
        assertEquals("8", model.stream?.sessionId)
        assertEquals(2, server.called("POST /stations/3/playback").size)
    }

    @Test
    fun offTheAirShowsWhyAndAsksAgainAtResumesAt() = runBlocking {
        server.on("POST /stations/3/playback", 409, StationJson.offAir(StationJson.NOW_ENDS))
        server.on(
            "GET /stations/3",
            body =
                StationJson.station(
                    "3",
                    StationJson.onAir("between_programs", next = StationJson.salt),
                ),
        )
        model.start(0)
        assertNull(model.error)
        assertTrue(output.loads.isEmpty())
        assertEquals(StationStatus.between_programs, held.tuner.onAir.status)
        assertEquals(2_400_000L, held.waits.receive())
        answers(8, position = 0)
        held.wake.send(Unit)
        eventually { output.loads.size == 1 }
        assertEquals("8", model.stream?.sessionId)
        assertEquals(StationStatus.on_air, held.tuner.onAir.status)
    }

    @Test
    fun aTuneInTheServerCouldNotAnswerIsAskedAgainSoon() = runBlocking {
        server.on("POST /stations/3/playback", 502, "<html>502 Bad Gateway</html>")
        model.start(0)
        assertEquals(5_000L, held.waits.receive())
        answers(7)
        held.wake.send(Unit)
        eventually { model.stream?.sessionId == "7" }
        assertNull(model.error)
        assertEquals(2, server.called("POST /stations/3/playback").size)
    }

    @Test
    fun aTuneInTheServerRefusedIsNotAskedAgain() = runBlocking {
        server.error("POST /stations/3/playback", 403, "forbidden")
        model.start(0)
        assertEquals("forbidden", model.error)
        assertTrue(held.waits.tryReceive().isFailure)
    }

    @Test
    fun offScheduleWithNothingAheadDoesNotAskAgain() = runBlocking {
        server.on("POST /stations/3/playback", 409, StationJson.offAir(null))
        server.on(
            "GET /stations/3",
            body = StationJson.station("3", StationJson.onAir("off_schedule")),
        )
        model.start(0)
        delay(50)
        assertTrue(held.waits.isEmpty)
        assertEquals(StationStatus.off_schedule, held.tuner.onAir.status)
    }

    @Test
    fun aDeviceChangeAsksForPlaybackAgain() = runBlocking {
        answers(7)
        model.start(0)
        answers(8)
        model.capabilitiesChanged(CapabilityGroup.Display)
        assertEquals(2, server.called("POST /stations/3/playback").size)
        assertTrue(server.called("PUT /playback/sessions/7/display").isEmpty())
        assertEquals("8", model.stream?.sessionId)
    }

    @Test
    fun standingByEndsTheSessionAndAsksNothingUntilTunedInAgain() = runBlocking {
        answers(7)
        model.start(0)
        held.waits.receive()
        model.standBy()
        assertTrue(output.isPaused)
        assertNull(model.stream)
        assertEquals(1, server.called("DELETE /playback/sessions/7").size)
        assertFalse(held.wake.trySend(Unit).isSuccess)
        // A device change has no session to re-decide.
        model.capabilitiesChanged(CapabilityGroup.Network)
        assertEquals(1, server.called("POST /stations/3/playback").size)

        answers(8)
        model.start(0)
        assertEquals("8", model.stream?.sessionId)
        assertFalse(output.isPaused)
    }

    @Test
    fun anAnswerThatLandsWhileStandingByAsksNothingMore() = runBlocking {
        server.on("POST /stations/3/playback", 409, StationJson.offAir(StationJson.NOW_ENDS))
        server.on(
            "GET /stations/3",
            body =
                StationJson.station(
                    "3",
                    StationJson.onAir("between_programs", next = StationJson.salt),
                ),
        )
        val answer = CountDownLatch(1)
        server.before = { if (it == "POST /stations/3/playback") answer.await() }
        val tuning = launch { model.start(0) }
        eventually { server.called("POST /stations/3/playback").size == 1 }
        model.standBy()
        answer.countDown()
        tuning.join()
        // The answer set its wait for the next program, which now cues nothing.
        held.waits.receive()
        answers(8, position = 0)
        held.wake.send(Unit)
        delay(50)
        assertEquals(1, server.called("POST /stations/3/playback").size)
        assertTrue(output.loads.isEmpty())
    }

    @Test
    fun leavingEndsTheSessionAndAsksNothingMore() = runBlocking {
        answers(7)
        model.start(0)
        held.waits.receive()
        model.stop()
        assertEquals(1, server.called("DELETE /playback/sessions/7").size)
        assertFalse(held.wake.trySend(Unit).isSuccess)
        delay(50)
        assertEquals(1, server.called("POST /stations/3/playback").size)
    }
}

class MusicStationTest {
    private val server = FakeServer()
    private val output = MusicPlayerTest.Output()
    private val scope = CoroutineScope(Job())
    private val capabilities =
        PlayerModelTest.Capabilities(
            DeviceFacts(emptyList(), ScreenFacts(2400, 1080, 60.0), emptyList(), PathFacts()),
        )
    private val player = MusicPlayer(server.api(), "1", output, capabilities, scope)
    private val songs = (1..4).map { StationJson.decode<MusicSong>(MusicJson.song("$it")) }

    private fun song(id: String, title: String) =
        StationJson.program(id, title, "2025-01-03T20:18:00Z", "2025-01-03T20:22:00Z", "song")

    private val onAir =
        StationJson.onAir(
            "on_air",
            song("21", "Paper Lanterns"),
            song("22", "Slow Tide"),
            86_000,
            listOf(song("22", "Slow Tide"), song("23", "Low Light"), song("24", "Paper Moon")),
        )
    private val station =
        StationJson.decode<Station>(
            StationJson.station("5", onAir, name = "Kitchen Radio", kind = "music"),
        )
    private val held = HeldTuner(server, station, scope)

    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    @After fun cancel() = scope.cancel()

    private fun answers(session: Long) {
        server.on("POST /stations/5/playback", 201, StationJson.playback(session, 86_000, onAir))
        server.on("DELETE /playback/sessions/$session", 204)
    }

    @Test
    fun tuningInPlaysTheProgramAndLeavesTheQueueAsItWas() = runBlocking {
        server.on("POST /playback/sessions", 201, MusicJson.stream(7))
        server.on("DELETE /playback/sessions/7", 204)
        player.play(songs, start = 1, source = "In Rainbows")
        val queue = player.queue
        answers(9)
        player.tune(station, held.tuner)
        assertEquals(1, server.called("DELETE /playback/sessions/7").size)
        assertEquals("21", output.loads.last().second)
        assertEquals(86_000L, output.positionMs)
        assertEquals("21", player.current?.id)
        assertEquals(
            listOf("Slow Tide", "Low Light", "Paper Moon"),
            player.stationUpNext.map { it.title },
        )
        assertEquals(queue, player.queue)
    }

    @Test
    fun stopIsTheOnlyControlAndPlayJoinsAgain() = runBlocking {
        answers(9)
        player.tune(station, held.tuner)
        player.next()
        player.previous()
        assertEquals(1, server.called("POST /stations/5/playback").size)
        assertTrue(server.called("POST /playback/sessions").isEmpty())
        player.pause()
        assertEquals(1, server.called("DELETE /playback/sessions/9").size)
        assertEquals(1, output.stopped)
        assertFalse(player.playing)
        answers(10)
        player.resume()
        assertEquals(2, server.called("POST /stations/5/playback").size)
        assertTrue(player.playing)
    }

    @Test
    fun stopLeavesTheStationAndHidesThePlayer() = runBlocking {
        answers(9)
        player.tune(station, held.tuner)
        assertTrue(player.showing)
        player.stop()
        assertEquals(1, server.called("DELETE /playback/sessions/9").size)
        assertEquals(1, output.stopped)
        assertNull(player.station)
        assertFalse(player.showing)
    }

    @Test
    fun aCancelledTuneInIsNoFailure() = runBlocking {
        answers(9)
        launch {
            coroutineContext.cancel()
            player.tune(station, held.tuner)
        }
            .join()
        assertNull(player.alert)
    }

    @Test
    fun aTuneInThatCouldNotReachTheServerIsAskedAgainSoon() = runBlocking {
        server.before = { if (it == "POST /stations/5/playback") throw IOException("gone") }
        player.tune(station, held.tuner)
        assertEquals(5_000L, held.waits.receive())
        server.before = {}
        answers(9)
        held.wake.send(Unit)
        eventually { player.stream?.sessionId == "9" }
        assertEquals(2, server.called("POST /stations/5/playback").size)
    }

    @Test
    fun theSongEndingAsksTheStationForTheNext() = runBlocking {
        answers(9)
        player.tune(station, held.tuner)
        answers(10)
        player.songEnded()
        assertEquals(1, server.called("DELETE /playback/sessions/9").size)
        assertEquals("10", player.stream?.sessionId)
    }

    @Test
    fun playingFromTheLibraryLeavesTheStation() = runBlocking {
        answers(9)
        player.tune(station, held.tuner)
        server.on("POST /playback/sessions", 201, MusicJson.stream(7))
        player.play(songs, source = "In Rainbows")
        assertNull(player.station)
        assertEquals("1", player.current?.id)
    }
}
