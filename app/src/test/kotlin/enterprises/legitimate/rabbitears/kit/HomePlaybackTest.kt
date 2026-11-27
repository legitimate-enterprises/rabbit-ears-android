package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.MusicSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The places a playback starts or hears of a new path, on an endpoint signed in to publicly. */
class HomePlaybackTest {
    private val server = FakeServer()
    private val api = server.api()
    private val scope = CoroutineScope(Job())
    private val capabilities =
        PlayerModelTest.Capabilities(
            DeviceFacts(
                listOf(CodecFacts("video/avc", true, listOf(8 to 0x8000), 1920, 1080, 60.0)),
                ScreenFacts(2400, 1080, 120.0),
                emptyList(),
                PathFacts(),
            ),
        )
    private val output = PlayerModelTest.Output()
    private val model = PlayerModel(api, 2, 20, output, capabilities, scope)

    @After fun cancel() = scope.cancel()

    private fun stream(url: String = "/api/v1/streams/k1/0/file", position: Long = 0) =
        """{"sessionId":7,"entry":{"position":0,"verdict":"direct_play","videoAction":"copy",""" +
            """"audioAction":"copy","subtitleAction":"none","bad":false},"streamUrl":"$url",""" +
            """"mediaFileId":1,"streamKind":"file","positionMs":$position,"negotiated":""" +
            """{"verdict":"direct_play","width":1920,"height":1080,"bitrate":8000000},""" +
            """"rungs":[],"sidecars":[],"auto":true}"""

    private fun update(action: String, stream: String) = """{"action":"$action","stream":$stream}"""

    init {
        server.on("POST /playback/sessions", 201, stream())
        server.on("PUT /playback/sessions/7/position", 204)
        server.on("DELETE /playback/sessions/7", 204)
        server.on(
            "PUT /playback/sessions/7/network",
            body = update("restart", stream("/api/v1/streams/k2/0/master.m3u8")),
        )
    }

    /** Confirms through the faked server, which then answers no further check. */
    private suspend fun arriveHome() {
        LocalChecks.serve(server)
        api.endpoint.confirmHome(api, PathFacts(), force = true)
        server.respond = { null }
        assertTrue(api.endpoint.atHome)
    }

    private fun discoveredLocally(route: String) =
        server.called(route).last().body.let {
            when {
                it.contains("\"discoveredLocally\":true") -> true
                it.contains("\"discoveredLocally\":false") -> false
                else -> null
            }
        }

    private fun index(route: String) = server.calls.indexOfLast { it.route == route }

    private val checks
        get() = server.called("POST /local-checks").size

    @Test
    fun aMovieStartChecksFirst() = runBlocking {
        model.start(0)
        assertEquals(
            listOf("POST /local-checks", "POST /playback/sessions"),
            server.calls.map { it.route }.filter { it.startsWith("POST") },
        )
        assertEquals(false, discoveredLocally("POST /playback/sessions"))
    }

    @Test
    fun atHomeAMoviePlaysFromTheLocalAddressUntilAPathChangeLeavesIt() = runBlocking {
        arriveHome()
        model.start(0)
        assertEquals(true, discoveredLocally("POST /playback/sessions"))
        assertEquals("${LocalChecks.LAN}/api/v1/streams/k1/0/file", output.loads.last().first)
        assertEquals("a fresh check stands", 1, checks)
        model.capabilitiesChanged(CapabilityGroup.Network)
        assertEquals("a quality pick is no new path", 1, checks)
        assertTrue(output.loads.last().first.startsWith(LocalChecks.LAN))

        capabilities.facts = capabilities.facts.copy(path = PathFacts(metered = true))
        model.capabilitiesChanged(CapabilityGroup.Network)
        assertFalse(api.endpoint.atHome)
        assertEquals(false, discoveredLocally("PUT /playback/sessions/7/network"))
        assertTrue(index("POST /local-checks") < index("PUT /playback/sessions/7/network"))
        assertEquals(
            "http://media.example.com:8477/api/v1/streams/k2/0/master.m3u8",
            output.loads.last().first,
        )
    }

    @Test
    fun aStreamTheServerKeepsIsLoadedAgainOnceItsAddressIsGone() = runBlocking {
        arriveHome()
        val kept = update("continue", stream(position = 61_000))
        server.on("PUT /playback/sessions/7/network", body = kept)
        model.start(0)
        capabilities.facts = capabilities.facts.copy(path = PathFacts(metered = true))
        model.capabilitiesChanged(CapabilityGroup.Network)
        assertEquals(2, output.loads.size)
        assertEquals(
            Triple("http://media.example.com:8477/api/v1/streams/k1/0/file", 61_000L, false),
            output.loads.last(),
        )
    }

    @Test
    fun aPlayerThatFailsAtHomeChecksBeforeTheNextEntryLoads() = runBlocking {
        arriveHome()
        server.on(
            "POST /playback/sessions/7/events",
            body = update("restart", stream("/api/v1/streams/k1/1/master.m3u8")),
        )
        model.start(0)
        model.playerFailed("The network connection was lost.")
        assertEquals(2, checks)
        assertTrue(index("POST /local-checks") < index("POST /playback/sessions/7/events"))
        assertEquals(
            "http://media.example.com:8477/api/v1/streams/k1/1/master.m3u8",
            output.loads.last().first,
        )
        model.playerFailed("Cannot decode")
        assertEquals("away, a failure waits on no check", 2, checks)
    }

    @Test
    fun aSongChecksFirstAndPlaysFromTheLocalAddressAtHome() = runBlocking {
        val audio = MusicPlayerTest.Output()
        val player = MusicPlayer(api, 1, audio, capabilities, scope, retryMs = 0)
        server.on("POST /playback/sessions", 201, MusicJson.stream(1))
        server.on("PUT /playback/sessions/1/position", 204)
        server.on("DELETE /playback/sessions/1", 204)
        val songs = listOf(StationJson.decode<MusicSong>(MusicJson.song(1)))
        player.play(songs, source = "In Rainbows")
        assertEquals(
            listOf("POST /local-checks", "POST /playback/sessions"),
            server.calls.map { it.route }.filter { it.startsWith("POST") },
        )
        assertEquals(
            "http://media.example.com:8477/api/v1/streams/a1/file",
            audio.loads.last().first,
        )
        arriveHome()
        player.play(songs, source = "In Rainbows")
        assertEquals(true, discoveredLocally("POST /playback/sessions"))
        assertEquals("${LocalChecks.LAN}/api/v1/streams/a1/file", audio.loads.last().first)
    }
}
