package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.AudioDelivered
import enterprises.legitimate.rabbitears.api.models.AudioDetails
import enterprises.legitimate.rabbitears.api.models.AudioSource
import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.api.models.Verdict
import java.io.IOException
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

object MusicJson {
    fun song(id: String, album: Long = 50, title: String = "Song $id", track: Int = id.toInt()) =
        """{"id":$id,"libraryId":"1","albumId":$album,"albumTitle":"In Rainbows","albumArtistId":"40",""" +
            """"artistId":"40","artistName":"Radiohead","title":"$title","lossless":true,""" +
            """"trackNumber":$track,"durationMs":200000,"codec":"flac"}"""

    fun album(id: String, title: String = "Album $id", year: Int = 2007) =
        """{"id":$id,"libraryId":"1","artistId":"40","artistName":"Radiohead","title":"$title",""" +
            """"songCount":10,"durationMs":2520000,"lossless":true,"genres":[],"addedAt":"${Json.T}",""" +
            """"year":$year}"""

    fun artist(id: String) =
        """{"id":$id,"libraryId":"1","name":"Radiohead","sortName":"Radiohead","albumCount":9,""" +
            """"songCount":112,"genres":[],"addedAt":"${Json.T}"}"""

    fun stream(session: Long, verdict: String = "direct_play") =
        """{"sessionId":$session,"entry":{"position":0,"verdict":"$verdict","videoAction":"copy",""" +
            """"audioAction":"copy","subtitleAction":"none","bad":false},""" +
            """"streamUrl":"/api/v2/streams/a$session/file","mediaFileId":"1","streamKind":"file","positionMs":0,""" +
            """"negotiated":{"verdict":"$verdict","width":0,"height":0,"bitrate":900000,""" +
            """"audioCodec":"flac","lossless":true},"rungs":[],"sidecars":[],""" +
            """"audio":{"source":{"codec":"flac","bitrate":2116000,"lossless":true},""" +
            """"delivered":{"verdict":"$verdict","codec":"flac","lossless":true}}}"""
}

class MusicPlayerTest {
    class Output : AudioOutput {
        val loads = mutableListOf<Pair<String, String>>()
        var stopped = 0
        override var positionMs = 0L
        override var isPaused = true
        override var onFailure: ((String) -> Unit)? = null
        override var onEnd: (() -> Unit)? = null

        override fun load(url: String, hls: Boolean, song: MusicSong, artUrl: String) {
            loads += url to song.id
        }

        override fun play() {
            isPaused = false
        }

        override fun pause() {
            isPaused = true
        }

        override fun seek(ms: Long) {
            positionMs = ms
        }

        override fun stop() {
            stopped++
            isPaused = true
        }
    }

    private val server = FakeServer()
    private val output = Output()
    private val scope = CoroutineScope(Job())
    private val capabilities =
        PlayerModelTest.Capabilities(
            DeviceFacts(emptyList(), ScreenFacts(2400, 1080, 60.0), emptyList(), PathFacts()),
        )
    private val player = MusicPlayer(server.api(), "1", output, capabilities, scope, retryMs = 0)
    private val songs = (1..4).map { song("$it") }

    @After fun cancel() = scope.cancel()

    private fun answers(session: Long) {
        server.on("POST /playback/sessions", 201, MusicJson.stream(session))
        server.on("DELETE /playback/sessions/$session", 204)
        server.on("PUT /playback/sessions/$session/position", 204)
    }

    /** The details panel's Playback row for the stream now playing. */
    private fun MusicPlayer.playback() =
        stream?.audio?.let { Format.audioDetails(it) }?.last()?.second?.first()?.value

    @Test
    fun playStartsASessionForTheSongWithItsPlaylist() = runBlocking {
        answers(7)
        player.play(songs, start = 1, source = "In Rainbows", playlistId = "12")
        val body = server.called("POST /playback/sessions").single().body
        assertTrue(body.contains(""""itemId":"2""""))
        assertTrue(body.contains(""""playlistId":"12""""))
        assertEquals("http://media.example.com:8477/api/v2/streams/a7/file" to "2", output.loads[0])
        assertTrue(player.playing)
        assertEquals("Direct play", player.playback())
        assertEquals("In Rainbows", player.queue.source)
    }

    @Test
    fun theEndOfASongEndsItsSessionAndPlaysTheNext() = runBlocking {
        answers(7)
        player.play(songs, source = "x")
        output.positionMs = 200_000
        answers(8)
        player.songEnded()
        assertTrue(server.called("DELETE /playback/sessions/7").single().query!!.contains("200000"))
        assertEquals("2", player.current?.id)
        assertEquals("8", player.stream?.sessionId)
    }

    @Test
    fun theLastSongEndingStopsTheOutputAndClearsTheQueue() = runBlocking {
        answers(7)
        player.play(songs, start = 3, source = "x")
        player.songEnded()
        assertNull(player.stream)
        assertFalse(player.playing)
        assertEquals(1, output.stopped)
        assertNull(player.current)
        assertFalse(player.showing)
    }

    @Test
    fun stopEndsTheSessionClearsTheQueueAndHidesThePlayer() = runBlocking {
        answers(7)
        player.play(songs, start = 1, source = "x")
        assertTrue(player.showing)
        player.stop()
        assertEquals(1, server.called("DELETE /playback/sessions/7").size)
        assertEquals(1, output.stopped)
        assertTrue(player.queue.upNext.isEmpty())
        assertNull(player.current)
        assertFalse(player.showing)
    }

    @Test
    fun aChosenSongThatCannotStartAlertsAndLeavesTheCurrentOnePlaying() = runBlocking {
        answers(7)
        player.play(songs, source = "x")
        server.error("POST /playback/sessions", 404, "not_found")
        player.next()
        assertEquals("“Song 2” can’t be played", player.alert)
        assertEquals("1", player.current?.id)
        assertTrue(player.playing)
    }

    @Test
    fun aCancelledStartIsNoFailure() = runBlocking {
        answers(7)
        launch {
            coroutineContext.cancel()
            player.play(songs, source = "x")
        }
            .join()
        assertNull(player.alert)
    }

    @Test
    fun threeQueuedFailuresInARowStopWithOneAlert() = runBlocking {
        answers(7)
        player.play((1..6).map { song("$it") }, source = "x")
        server.error("POST /playback/sessions", 500, "internal")
        player.songEnded()
        assertEquals("These songs can’t be played", player.alert)
        assertEquals(4, server.called("POST /playback/sessions").size)
        assertNull(player.stream)
    }

    @Test
    fun aLostConnectionAtASongBoundaryIsWaitedOutNotCountedAgainstTheSong() = runBlocking {
        answers(7)
        player.play(songs, source = "x")
        answers(8)
        var refusals = 3
        server.before = { route ->
            if (route == "POST /playback/sessions" && refusals-- > 0) throw IOException("refused")
        }
        player.songEnded()
        assertEquals("2", player.current?.id)
        assertEquals("8", player.stream?.sessionId)
        assertEquals(5, server.called("POST /playback/sessions").size)
        assertNull(player.alert)
    }

    @Test
    fun aServerThatStaysDownStillStopsTheMusicAfterThreeSongs() = runBlocking {
        answers(7)
        player.play((1..6).map { song("$it") }, source = "x")
        server.before = { route ->
            if (route == "POST /playback/sessions") throw IOException("refused")
        }
        player.songEnded()
        assertEquals("These songs can’t be played", player.alert)
        assertEquals(1 + 3 * 4, server.called("POST /playback/sessions").size)
        assertNull(player.stream)
    }

    @Test
    fun previousRestartsASongPastThreeSeconds() = runBlocking {
        answers(7)
        player.play(songs, start = 2, source = "x")
        output.positionMs = 10_000
        player.previous()
        assertEquals(0L, output.positionMs)
        assertEquals(1, server.called("POST /playback/sessions").size)
        answers(8)
        player.previous()
        assertEquals("2", player.current?.id)
    }

    @Test
    fun pauseReportsThePositionAndStopEmptiesTheQueue() = runBlocking {
        answers(7)
        player.play(songs, source = "x")
        output.positionMs = 5_000
        player.pause()
        assertFalse(player.playing)
        assertTrue(
            server.called("PUT /playback/sessions/7/position").single().body.contains("5000"),
        )
        player.stop()
        assertNull(player.current)
        assertEquals(1, server.called("DELETE /playback/sessions/7").size)
    }

    @Test
    fun aVideoEndsTheSongsSessionAndPlayStartsItAgainWhereItStopped() = runBlocking {
        answers(7)
        player.play(songs, start = 1, source = "x")
        output.positionMs = 42_000
        player.yieldToVideo()
        assertTrue(server.called("DELETE /playback/sessions/7").single().query!!.contains("42000"))
        assertNull(player.stream)
        assertEquals(1, output.stopped)
        assertEquals("2", player.current?.id)
        answers(8)
        player.resume()
        val body = server.called("POST /playback/sessions").last().body
        assertTrue(body.contains(""""itemId":"2"""") && body.contains(""""positionMs":42000"""))
        assertEquals("8", player.stream?.sessionId)
    }

    @Test
    fun playFindingItsSessionGoneStartsTheSongAgainWhereItStopped() = runBlocking {
        answers(7)
        player.play(songs, start = 1, source = "x")
        player.pause()
        player.resume()
        assertTrue(player.playing)
        assertEquals(1, server.called("POST /playback/sessions").size)

        output.positionMs = 42_000
        player.pause()
        // The server ends a session that reports nothing for 30 minutes.
        server.error("PUT /playback/sessions/7/position", 404, "not_found")
        server.on("POST /playback/sessions", 201, MusicJson.stream(8))
        player.resume()
        val body = server.called("POST /playback/sessions").last().body
        assertTrue(body.contains(""""itemId":"2"""") && body.contains(""""positionMs":42000"""))
        assertEquals("8", player.stream?.sessionId)
        assertTrue(player.playing)
        assertNull(player.alert)
    }

    @Test
    fun aRefusedStreamMovesToTheNextEntry() = runBlocking {
        answers(7)
        player.play(songs, source = "x")
        server.on(
            "POST /playback/sessions/7/events",
            body = """{"action":"restart","stream":${MusicJson.stream(7, "audio_transcode")}}""",
        )
        player.outputFailed("unsupported")
        assertEquals(2, output.loads.size)
        assertEquals("Audio transcode", player.playback())
    }
}

class MusicModelTest {
    private val server = FakeServer()
    private val model = MusicModel(server.api())

    @Test
    fun anAlbumReadsItsSongsInTrackOrder() = runBlocking {
        server.on("GET /music/albums/50", body = MusicJson.album("50", "In Rainbows"))
        server.on("GET /music/songs", body = Json.page(MusicJson.song("1"), MusicJson.song("2")))
        val detail = model.album("50")!!
        assertEquals("In Rainbows", detail.album.title)
        assertEquals(listOf("1", "2"), detail.songs.map { it.id })
        assertTrue(server.called("GET /music/songs").single().query!!.contains("albumId=50"))
    }

    @Test
    fun anArtistHasFivePopularSongsAndAlbumsNewestFirst() = runBlocking {
        server.on("GET /music/artists/40", body = MusicJson.artist("40"))
        server.on("GET /music/songs", body = Json.page(MusicJson.song("1")))
        server.on(
            "GET /music/albums",
            body = Json.page(MusicJson.album("1", year = 1997), MusicJson.album("2", year = 2007)),
        )
        val detail = model.artist("40")!!
        assertEquals(listOf("2", "1"), detail.albums.map { it.id })
        val query = server.called("GET /music/songs").single().query!!
        assertTrue(query.contains("sort=popular") && query.contains("limit=5"))
    }

    @Test
    fun albumsLoadOnceAndAnErrorIsNamed() = runBlocking {
        server.on("GET /music/albums", body = Json.page(MusicJson.album("1")))
        model.loadAlbums()
        model.loadAlbums()
        assertEquals(1, server.called("GET /music/albums").size)
        server.error("GET /music/artists", 500, "internal", "Database is locked.")
        model.loadArtists()
        assertEquals("Database is locked.", model.error)
        assertNull(model.artists)
    }

    private fun library(id: String, kind: String) =
        """{"id":$id,"name":"Library $id","kind":"$kind","scanHour":3,"watchFiles":false,""" +
            """"folders":[],"stats":{"movies":0,"shows":0,"seasons":0,"episodes":0,"files":0,""" +
            """"bytes":0,"unmatched":0},"createdAt":"${Json.T}","modifiedAt":"${Json.T}"}"""

    @Test
    fun theGenresAreThoseOfMusicLibrariesOnceEachByName() = runBlocking {
        server.on("GET /libraries", body = Json.page(library("1", "movies"), library("2", "music")))
        server.on(
            "GET /genres",
            body = Json.page("""{"id":"8","name":"Rock"}""", """{"id":"3","name":"Jazz"}"""),
        )
        assertEquals(listOf("Jazz", "Rock"), model.genres().map { it.name })
        assertTrue(server.called("GET /genres").single().query!!.contains("libraryId=2"))
    }

    @Test
    fun aListIsReadAgainOnlyForAnotherGenreOrSort() = runBlocking {
        server.on("GET /music/albums", body = Json.page(MusicJson.album("1")))
        model.loadAlbums(genreId = "8")
        model.loadAlbums(genreId = "8")
        assertEquals(1, server.called("GET /music/albums").size)
        assertTrue(server.called("GET /music/albums").single().query!!.contains("genreId=8"))
        model.loadAlbums()
        assertEquals(2, server.called("GET /music/albums").size)

        server.on("GET /music/songs", body = Json.page(MusicJson.song("1")))
        model.loadSongs(SongSort.Popular, genreId = "8")
        model.loadSongs(SongSort.Popular, genreId = "8")
        val query = server.called("GET /music/songs").single().query!!
        assertTrue(query.contains("sort=popular") && query.contains("genreId=8"))
        model.loadSongs(SongSort.Added, genreId = "8")
        assertEquals(2, server.called("GET /music/songs").size)
    }

    @Test
    fun aWatchChangeElsewhereReadsEachListAgain() = runBlocking {
        server.on("GET /music/albums", body = Json.page(MusicJson.album("1")))
        server.on("GET /music/artists", body = Json.page(MusicJson.artist("40")))
        server.on("GET /music/songs", body = Json.page(MusicJson.song("1")))
        for (watched in listOf(0, 0, 1)) {
            model.loadAlbums(watched = watched)
            model.loadArtists(watched = watched)
            model.loadSongs(watched = watched)
        }
        assertEquals(2, server.called("GET /music/albums").size)
        assertEquals(2, server.called("GET /music/artists").size)
        assertEquals(2, server.called("GET /music/songs").size)
    }

    private val flac =
        AudioDetails(
            AudioSource(
                lossless = true,
                codec = "flac",
                bitrate = 2_116_000,
                sampleRateHz = 96_000,
                bitDepth = 24,
                channels = 2,
                sizeBytes = 67_400_000,
            ),
            AudioDelivered(Verdict.direct_play, "flac", lossless = true, bitrate = 2_116_000),
        )

    @Test
    fun theDetailsPanelReadsTheSourceThenWhatIsDelivered() {
        assertEquals(
            listOf(
                "Source" to
                    listOf(
                        DetailRow("Format", "FLAC", lossless = true),
                        DetailRow("Bitrate", "2,116 kb/s"),
                        DetailRow("Sample rate", "96 kHz"),
                        DetailRow("Bit depth", "24-bit"),
                        DetailRow("Channels", "Stereo"),
                        DetailRow("Size", "67.4 MB"),
                    ),
                "Delivered" to
                    listOf(
                        DetailRow("Playback", "Direct play", success = true),
                        DetailRow("Codec", "FLAC"),
                        DetailRow("Bitrate", "2,116 kb/s"),
                    ),
            ),
            Format.audioDetails(flac),
        )
        assertEquals("PCM", Format.codec("pcm_s16le"))
        assertEquals("3:48", Format.length(228_000))
    }

    @Test
    fun aRowWithNoValueIsLeftOut() {
        val bare =
            AudioDetails(
                AudioSource(lossless = false, codec = "mp3", sampleRateHz = 44_100, channels = 1),
                AudioDelivered(Verdict.remux, "mp3", lossless = false),
            )
        assertEquals(
            listOf(
                "Source" to
                    listOf(
                        DetailRow("Format", "MP3"),
                        DetailRow("Sample rate", "44.1 kHz"),
                        DetailRow("Channels", "Mono"),
                    ),
                "Delivered" to
                    listOf(
                        DetailRow("Playback", "Remux", success = true),
                        DetailRow("Codec", "MP3"),
                    ),
            ),
            Format.audioDetails(bare),
        )
    }

    @Test
    fun aTranscodeDeliversItsOwnCodecAndBitrateAsACaution() {
        val transcode =
            flac.copy(
                source = flac.source.copy(channels = 6),
                delivered = AudioDelivered(Verdict.audio_transcode, "aac", false, 256_000),
            )
        val (source, delivered) = Format.audioDetails(transcode).map { it.second }
        assertEquals(DetailRow("Channels", "6 channels"), source[4])
        assertEquals(
            listOf(
                DetailRow("Playback", "Audio transcode", success = false),
                DetailRow("Codec", "AAC"),
                DetailRow("Bitrate", "256 kb/s"),
            ),
            delivered,
        )
    }
}
