package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ThroughputTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerModelTest {
    class Output : VideoOutput {
        val loads = mutableListOf<Triple<String, Long, Boolean>>()
        var stopped = false
        override var positionMs = 0L
        override var isPaused = true
        override var isBuffering = false
        var sample =
            ThroughputTelemetry(5_000_000, 12_000, 1, rungHeight = 720, rungBitrate = 4_000_000)
        override var onFailure: ((String) -> Unit)? = null
        override var onStall: (() -> Unit)? = null

        val goals = mutableListOf<Long>()

        override fun load(url: String, startMs: Long, hls: Boolean, bufferGoalMs: Long) {
            loads += Triple(url, startMs, hls)
            goals += bufferGoalMs
        }

        override fun play() {
            isPaused = false
        }

        override fun pause() {
            isPaused = true
        }

        override fun stop() {
            stopped = true
        }

        override fun seek(ms: Long) = Unit

        override fun schedule(playing: Boolean, positionMs: Long, afterMs: Long) = Unit

        override fun telemetry() = sample
    }

    class Capabilities(var facts: DeviceFacts) : CapabilitySource {
        override fun facts() = facts

        override var onChange: ((CapabilityGroup) -> Unit)? = null
    }

    private val server = FakeServer()
    private val output = Output()
    private val facts =
        DeviceFacts(
            listOf(CodecFacts("video/avc", true, listOf(8 to 0x8000), 1920, 1080, 60.0)),
            ScreenFacts(2400, 1080, 120.0),
            emptyList(),
            PathFacts(),
        )
    private val capabilities = Capabilities(facts)
    private val scope = CoroutineScope(Job())
    private val model = PlayerModel(server.api(), 1, 101, output, capabilities, scope)

    @After fun cancel() = scope.cancel()

    private fun stream(
        session: Long = 7,
        verdict: String = "direct_play",
        kind: String = "file",
        position: Long = 0,
        file: Long = 1,
        away: Boolean = false,
        auto: Boolean = false,
        rungs: String = RUNGS,
        sidecars: String = "[]",
        previews: String? = null,
    ) =
        """{"sessionId":$session,"entry":{"position":0,"verdict":"$verdict","videoAction":"copy",""" +
            """"audioAction":"copy","subtitleAction":"none","bad":false},"streamUrl":"/api/v1/streams/k$session/index",""" +
            """"mediaFileId":$file,"streamKind":"$kind","positionMs":$position,"negotiated":{"verdict":"$verdict","width":3840,""" +
            """"height":2160,"bitrate":38000000,"hdrFormat":"hdr10"},"rungs":$rungs,"sidecars":$sidecars,""" +
            """"away":$away,"auto":$auto""" +
            (previews?.let { ""","previews":$it""" } ?: "") +
            "}"

    private fun update(action: String, stream: String) = """{"action":"$action","stream":$stream}"""

    private suspend fun started(stream: String = stream()) {
        server.on("POST /playback/sessions", 201, stream)
        model.start(startMs = 60_000, audioTrackId = 3)
    }

    @Test
    fun startsWithTheWholeSnapshotAndPlaysTheStream() = runBlocking {
        started()
        val body = server.called("POST /playback/sessions").single().body
        assertTrue(body.contains(""""itemId":101"""))
        assertTrue(body.contains(""""positionMs":60000"""))
        assertTrue(body.contains(""""audioTrackId":3"""))
        assertTrue(body.contains(""""codec":"h264""""))
        assertTrue(body.contains(""""width":2400"""))
        assertTrue(body.contains(""""auto":true"""))
        assertEquals(
            Triple("http://media.example.com:8477/api/v1/streams/k7/index", 0L, false),
            output.loads.single(),
        )
        assertEquals(PillText(listOf("4K", "HDR"), "Direct play", "38 Mb/s"), model.pill)
    }

    @Test
    fun theStreamIsLoadedToHoldTheForwardBufferItNames() = runBlocking {
        started(stream().dropLast(1) + ""","bufferGoalSeconds":120}""")
        started()
        assertEquals(listOf(120_000L, 0L), output.goals)
    }

    @Test
    fun aPlaylistStartSendsItsId() = runBlocking {
        val inList = PlayerModel(server.api(), 1, 101, output, capabilities, scope, playlistId = 5)
        server.on("POST /playback/sessions", 201, stream())
        inList.start(startMs = 0)
        assertTrue(
            server.called("POST /playback/sessions").single().body.contains(""""playlistId":5"""),
        )
    }

    @Test
    fun aChosenEditionIsSentAndNoneLeavesItToTheServer() = runBlocking {
        val cut = PlayerModel(server.api(), 1, 101, output, capabilities, scope, editionId = 32)
        server.on("POST /playback/sessions", 201, stream())
        cut.start(startMs = 0)
        model.start(startMs = 0)
        val (named, plain) = server.called("POST /playback/sessions").map { it.body }
        assertTrue(named.contains(""""editionId":32"""))
        // The generated client writes an unset optional as null, which the server reads as absent.
        assertTrue(plain.contains(""""editionId":null"""))
    }

    @Test
    fun aConflictSaysTheTitleCannotPlayHere() = runBlocking {
        server.error("POST /playback/sessions", 409, "conflict")
        model.start(0)
        assertEquals("This title can't play on this device.", model.error)
        assertTrue(output.loads.isEmpty())
    }

    @Test
    fun aClientFailureMovesToTheNextEntryAtThePosition() = runBlocking {
        started()
        output.positionMs = 90_000
        server.on("PUT /playback/sessions/7/position", 204)
        server.on(
            "POST /playback/sessions/7/events",
            body = update("restart", stream(verdict = "remux", kind = "hls", position = 90_000)),
        )
        model.playerFailed("decoder init failed")
        val event = server.called("POST /playback/sessions/7/events").single().body
        assertTrue(event.contains(""""kind":"client_failure""""))
        assertTrue(event.contains(""""positionMs":90000"""))
        assertTrue(server.called("PUT /playback/sessions/7/position").isNotEmpty())
        assertEquals(
            Triple("http://media.example.com:8477/api/v1/streams/k7/index", 90_000L, true),
            output.loads.last(),
        )
        assertFalse(model.adjusting)
    }

    @Test
    fun aRestartLeavesAPausedPlayerPaused() = runBlocking {
        started()
        assertFalse(output.isPaused)
        output.pause()
        server.on("PUT /playback/sessions/7/position", 204)
        server.on(
            "PUT /playback/sessions/7/network",
            body = update("restart", stream(verdict = "remux", kind = "hls", position = 90_000)),
        )
        model.capabilitiesChanged(CapabilityGroup.Network)
        assertEquals(2, output.loads.size)
        assertTrue(output.isPaused)
    }

    @Test
    fun aRouteChangeSendsOnlyThatGroup() = runBlocking {
        started()
        server.on("PUT /playback/sessions/7/position", 204)
        server.on("PUT /playback/sessions/7/audio-route", body = update("continue", stream()))
        capabilities.facts = facts.copy(outputs = listOf(OutputDevice(9, listOf(2, 6))))
        model.capabilitiesChanged(CapabilityGroup.AudioRoute)
        val body = server.called("PUT /playback/sessions/7/audio-route").single().body
        assertTrue(body.contains(""""channels":6"""))
        assertEquals(1, output.loads.size)
    }

    @Test
    fun awayFromHomeAutoComesFirstAndCappedRowsAreBlocked() = runBlocking {
        val rungs =
            """[{"width":3840,"height":2160,"bitrate":38000000,"verdict":"direct_play","blocked":true},""" +
                """{"width":1920,"height":1080,"bitrate":8000000,"verdict":"video_transcode","blocked":true},""" +
                """{"width":1280,"height":720,"bitrate":4000000,"verdict":"video_transcode"}]"""
        started(stream(verdict = "video_transcode", away = true, auto = true, rungs = rungs))
        val options = model.qualityOptions
        assertEquals(listOf("Auto", "Original", "1080p", "720p"), options.map { it.name })
        assertTrue(options[0].selected)
        assertEquals("Now 4K · adjusts to your connection", options[0].detail)
        assertEquals("Wi-Fi only", options[1].trailing)
        assertEquals("H.264 · 4 Mb/s", options[3].detail)
        assertEquals("Transcode", options[3].trailing)

        // A blocked row does nothing.
        model.selectQuality(options[1])
        assertTrue(server.called("PUT /playback/sessions/7/network").isEmpty())

        server.on(
            "PUT /playback/sessions/7/throughput",
            body = update("continue", stream(away = true, auto = true, rungs = rungs)),
        )
        output.positionMs = 30_000
        model.tick()
        assertEquals(PillText(listOf("720p"), "Auto", "4 Mb/s"), model.pill)
        val telemetry = server.called("PUT /playback/sessions/7/throughput").single().body
        assertTrue(telemetry.contains(""""positionMs":30000"""))
        assertTrue(telemetry.contains(""""rungHeight":720"""))

        server.on("PUT /playback/sessions/7/position", 204)
        server.on(
            "PUT /playback/sessions/7/network",
            body = update("restart", stream(session = 7, away = true, rungs = rungs)),
        )
        model.selectQuality(options[3])
        val network = server.called("PUT /playback/sessions/7/network").single().body
        assertTrue(network.contains(""""auto":false"""))
        assertTrue(network.contains(""""maxBitrate":4000000"""))
        assertTrue(model.qualityOptions.single { it.name == "720p" }.selected)
    }

    @Test
    fun atHomeThereIsNoAutoAndOriginalIsChosen() = runBlocking {
        started()
        val options = model.qualityOptions
        assertEquals(listOf("Original", "1080p"), options.map { it.name })
        assertTrue(options[0].selected)
        assertEquals("Direct play", options[0].trailing)
    }

    /** As the server answers: only the track drawn now is a sidecar. */
    private fun sidecar(id: Long, forced: Boolean = false) =
        """[{"trackId":$id,"format":"webvtt","forced":$forced,""" +
            """"url":"/api/v1/streams/k7/subtitles/$id.vtt","language":"en"}]"""

    private fun subtitle(id: Long, language: String, forced: Boolean = false, more: String = "") =
        """{"id":$id,"streamIndex":$id,"kind":"subtitle","codec":"subrip","isDefault":false,""" +
            """"isForced":$forced,"language":"$language"$more}"""

    @Test
    fun theSubtitleMenuListsTheTracksOfTheFileInPlay() = runBlocking {
        server.on(
            "GET /media-files/2",
            body =
                """{"id":2,"itemId":101,"path":"/media/Extended.mkv","sizeBytes":1000,""" +
                    """"modifiedAt":"${Json.T}","chapters":[],"tracks":[""" +
                    subtitle(4, "en", forced = true) +
                    "," +
                    subtitle(5, "en", more = ""","title":"SDH"""") +
                    "," +
                    subtitle(6, "es", more = ""","subtitleIsText":false""") +
                    "]}",
        )
        server.on(
            "GET /streams/k7/subtitles/4.vtt",
            body = "WEBVTT\n\n00:01.000 --> 00:02.500\n<i>Hola</i>\n",
        )
        started(stream(file = 2, sidecars = sidecar(4, forced = true)))
        assertEquals("Hola", model.cue(1_500))
        assertNull(model.cue(3_000))
        assertEquals(
            listOf("Off", "Only for non-English", "English (forced)", "English SDH", "Spanish"),
            model.subtitleOptions.map { it.name },
        )
        assertEquals("Includes sound cues", model.subtitleOptions[3].detail)

        server.on("PUT /playback/sessions/7/position", 204)
        server.on(
            "PUT /playback/sessions/7/tracks",
            body = update("continue", stream(file = 2, sidecars = sidecar(5))),
        )
        server.on(
            "GET /streams/k7/subtitles/5.vtt",
            body = "WEBVTT\n\n00:05.000 --> 00:06.000\n[door creaks]\n",
        )
        model.selectSubtitles(model.subtitleOptions[3].choice)
        val tracks = server.called("PUT /playback/sessions/7/tracks").single().body
        assertTrue(tracks.contains(""""audioTrackId":3"""))
        assertTrue(tracks.contains(""""subtitleTrackId":5"""))
        assertEquals("[door creaks]", model.cue(5_500))

        // A bitmap track is burned in, so the answer carries no sidecar.
        server.on(
            "PUT /playback/sessions/7/tracks",
            body = update("restart", stream(file = 2, verdict = "video_transcode", kind = "hls")),
        )
        model.selectSubtitles(model.subtitleOptions[4].choice)
        val burned = server.called("PUT /playback/sessions/7/tracks").last().body
        assertTrue(burned.contains(""""audioTrackId":3"""))
        assertTrue(burned.contains(""""subtitleTrackId":6"""))
        assertNull(model.cue(5_500))
        assertEquals(1, server.called("GET /media-files/2").size)

        model.selectSubtitles(SubtitleChoice.Off)
        assertNull(model.cue(5_500))
    }

    @Test
    fun aCancelledPickIsNoFailure() = runBlocking {
        started()
        launch {
            coroutineContext.cancel()
            model.selectSubtitles(SubtitleChoice.Off)
        }
            .join()
        assertNull(model.error)
        assertFalse(model.adjusting)
    }

    @Test
    fun previewsAreTheNearestFrame() = runBlocking {
        started(
            stream(
                previews =
                    """{"intervalMs":10000,"count":3,"width":320,"height":180,""" +
                        """"frameUrl":"/api/v1/streams/k7/previews/{n}.jpg","bifUrl":"/b"}""",
            ),
        )
        assertEquals("/api/v1/streams/k7/previews/1.jpg", model.previewPath(14_000))
        assertEquals("/api/v1/streams/k7/previews/2.jpg", model.previewPath(99_000))
        assertEquals("/api/v1/streams/k7/previews/0.jpg", model.previewPath(0))
    }

    @Test
    fun stopEndsTheSessionWithThePosition() = runBlocking {
        started()
        output.positionMs = 1_234_000
        server.on("DELETE /playback/sessions/7", 204)
        model.stop()
        assertTrue(output.stopped)
        assertEquals(
            "positionMs=1234000",
            server.called("DELETE /playback/sessions/7").single().query,
        )
        assertNull(model.stream)
        model.stop()
        assertEquals(1, server.called("DELETE /playback/sessions/7").size)
    }

    @Test
    fun telemetryNamesARungOnlyWhenBothAreKnown() {
        val plain = Telemetry.sample(-1, 2_000, 0, 1080, -1)
        assertNull(plain.rungHeight)
        assertNull(plain.rungBitrate)
        assertEquals(0L, plain.measuredBitrate)
        val variant = Telemetry.sample(6_000_000, 8_000, 2, 720, 4_000_000)
        assertEquals(720, variant.rungHeight)
        assertEquals(4_000_000L, variant.rungBitrate)
        assertEquals(2, variant.stallCount)
    }

    @Test
    fun parsesSidecars() {
        assertEquals(3_723_500L, SubtitleCue.millis("01:02:03.500"))
        assertEquals(65_250L, SubtitleCue.millis("01:05,250"))
        assertNull(SubtitleCue.millis("nope"))
        val cues =
            SubtitleCue.parse(
                "WEBVTT\r\n\r\nNOTE x\r\n\r\n00:00.000 --> 00:01.000 align:start\r\nOne\r\ntwo\r\n",
            )
        assertEquals(listOf(SubtitleCue(0, 1_000, "One\ntwo")), cues)
    }

    @Test
    fun nextEpisodeCrossesSeasons() {
        val items =
            listOf(
                    Json.item(1, "movie"),
                    Json.item(10, "show"),
                    Json.item(11, "season", parent = 10, season = 1),
                    Json.item(12, "season", parent = 10, season = 2),
                    Json.item(101, "episode", parent = 11, season = 1, episode = 1),
                    Json.item(102, "episode", parent = 11, season = 1, episode = 2),
                    Json.item(201, "episode", parent = 12, season = 2, episode = 1),
                )
                .map { Serializer.kotlinxSerializationJson.decodeFromString(Item.serializer(), it) }
        val catalog = Catalog(items)
        assertEquals(102L, catalog.nextEpisode(101)?.id)
        assertEquals(201L, catalog.nextEpisode(102)?.id)
        assertNull(catalog.nextEpisode(201))
        assertNull(catalog.nextEpisode(1))
    }

    @Test
    fun headingNamesWhatCapsTheStream() {
        assertEquals("On your home network", Format.qualityHeading(false, PathFacts()))
        assertEquals("Away from home", Format.qualityHeading(true, PathFacts()))
        assertEquals(
            "On cellular, up to 4 Mb/s",
            Format.qualityHeading(true, PathFacts(metered = true, cellular = true)),
        )
        assertEquals(
            "Data Saver, up to 1.5 Mb/s",
            Format.qualityHeading(true, PathFacts(lowData = true)),
        )
    }

    private companion object {
        const val RUNGS =
            """[{"width":3840,"height":2160,"bitrate":38000000,"verdict":"direct_play"},""" +
                """{"width":1920,"height":1080,"bitrate":8000000,"verdict":"video_transcode"}]"""
    }
}
