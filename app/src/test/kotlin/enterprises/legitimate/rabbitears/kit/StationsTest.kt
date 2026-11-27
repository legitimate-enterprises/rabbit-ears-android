package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.Station
import enterprises.legitimate.rabbitears.api.models.StationInput
import enterprises.legitimate.rabbitears.api.models.StationOnAir
import enterprises.legitimate.rabbitears.api.models.StationOrder
import enterprises.legitimate.rabbitears.api.models.StationProgram
import enterprises.legitimate.rabbitears.api.models.StationTimeRange
import enterprises.legitimate.rabbitears.api.models.StationTiming
import enterprises.legitimate.rabbitears.api.models.StationVisibility
import enterprises.legitimate.rabbitears.api.models.Weekday
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON for stations, with every required field. */
object StationJson {
    fun program(
        id: String,
        title: String,
        starts: String,
        ends: String,
        kind: String = "movie",
        show: String? = null,
    ): String {
        val described =
            if (kind == "song") """"song":${MusicJson.song(id, title = title)}"""
            else
                """"item":${Json.item(id, kind, title)}""" +
                    (show?.let { ""","show":${Json.item("90", "show", it)}""" } ?: "")
        return """{"itemId":$id,"editionId":"${id}0","kind":"$kind","startsAt":"$starts",""" +
            """"endsAt":"$ends","durationMs":6000000,$described}"""
    }

    /** [upNext] defaults to [next] alone; the server sends [next] as its first. */
    fun onAir(
        status: String,
        now: String? = null,
        next: String? = null,
        position: Long? = null,
        upNext: List<String> = listOfNotNull(next),
    ) =
        """{"status":"$status","upNext":[${upNext.joinToString(",")}]""" +
            (now?.let { ""","now":$it""" } ?: "") +
            (position?.let { ""","positionMs":$it""" } ?: "") +
            (next?.let { ""","next":$it""" } ?: "") +
            "}"

    fun station(
        id: String,
        onAir: String,
        name: String = "The Big Screen",
        kind: String = "movies",
        visibility: String = "household",
        order: String = "playlist",
        days: String = "[]",
        times: String = "[]",
        rating: String? = null,
        timing: String = "hour",
    ) =
        """{"id":$id,"name":"$name","kind":"$kind","ownerId":"1","householdId":"1","playlistId":"12",""" +
            """"order":"$order","visibility":"$visibility","timing":"$timing","days":$days,""" +
            """"times":$times,""" +
            """"onAir":$onAir,"createdAt":"${Json.T}","modifiedAt":"${Json.T}"""" +
            (rating?.let { ""","rating":"$it"""" } ?: "") +
            "}"

    fun stream(session: Long, position: Long) =
        """{"sessionId":$session,"entry":{"position":0,"verdict":"direct_play","videoAction":"copy",""" +
            """"audioAction":"copy","subtitleAction":"none","bad":false},""" +
            """"streamUrl":"/api/v2/streams/s$session/file","mediaFileId":"1","streamKind":"file","positionMs":$position,""" +
            """"negotiated":{"verdict":"direct_play","width":1920,"height":1080,"bitrate":8000000},""" +
            """"rungs":[],"sidecars":[]}"""

    fun playback(session: Long, position: Long, onAir: String) =
        """{"stream":${stream(session, position)},"onAir":$onAir}"""

    fun offAir(resumesAt: String?) =
        """{"code":"off_air","message":"nothing is on this station now"""" +
            (resumesAt?.let { ""","resumesAt":"$it"""" } ?: "") +
            "}"

    inline fun <reified T> decode(json: String): T =
        Serializer.kotlinxSerializationJson.decodeFromString(json)

    /** A Friday: 20:00 to 21:00 UTC is 15:00 to 16:00 in New York. */
    const val NOW_ENDS = "2025-01-03T21:00:00Z"
    val lantern = program("7", "The Lantern Keeper", "2025-01-03T19:00:00Z", NOW_ENDS)
    val salt = program("8", "Salt and Ember", NOW_ENDS, "2025-01-03T23:18:00Z")
}

class StationCaptionTest {
    private val york = ZoneId.of("America/New_York")

    private fun lines(onAir: String, kind: PlaylistKind = PlaylistKind.movies, locale: Locale) =
        Stations.caption(StationJson.decode<StationOnAir>(onAir), kind, york, locale)

    @Test
    fun onAirNamesWhatIsOnAndWhatIsNextInTheDevicesZoneAndLocale() {
        val onAir = StationJson.onAir("on_air", StationJson.lantern, StationJson.salt, 4_320_000)
        assertEquals(
            listOf("On now: The Lantern Keeper", "Next: Salt and Ember at 16:00"),
            lines(onAir, locale = Locale.UK),
        )
        val us = lines(onAir, locale = Locale.US)[1]
        assertTrue(us, us.startsWith("Next: Salt and Ember at 4:00") && us.endsWith("PM"))
    }

    @Test
    fun betweenProgramsNamesTheNextAndOffScheduleTheDayItIsBack() {
        val moon =
            StationJson.program("9", "Moon Pie", StationJson.NOW_ENDS, "2025-01-03T22:30:00Z")
        assertEquals(
            listOf("Up next at 16:00: Moon Pie"),
            lines(StationJson.onAir("between_programs", next = moon), locale = Locale.UK),
        )
        // 03:00 UTC on Saturday is still Friday evening in New York.
        val late =
            StationJson.program("9", "Moon Pie", "2025-01-04T03:00:00Z", "2025-01-04T04:30:00Z")
        assertEquals(
            listOf("Back Friday at 22:00"),
            lines(StationJson.onAir("off_schedule", next = late), locale = Locale.UK),
        )
        assertEquals(
            listOf("Nothing scheduled"),
            lines(StationJson.onAir("off_schedule"), locale = Locale.UK),
        )
    }

    @Test
    fun aMusicStationsNextHasNoTimeAndAnEpisodeIsNamedWithItsShow() {
        val song = { id: String, title: String ->
            StationJson.program(id, title, StationJson.NOW_ENDS, StationJson.NOW_ENDS, "song")
        }
        val onAir =
            StationJson.onAir("on_air", song("21", "Paper Lanterns"), song("22", "Slow Tide"), 1000)
        assertEquals(
            listOf("On now: Paper Lanterns", "Next: Slow Tide"),
            lines(onAir, PlaylistKind.music, Locale.UK),
        )
        val episode =
            StationJson.program(
                "31",
                "Sleepytime",
                StationJson.NOW_ENDS,
                "2025-01-03T21:30:00Z",
                "episode",
                show = "Bluey",
            )
        assertEquals("Bluey · Sleepytime", StationJson.decode<StationProgram>(episode).title)
    }
}

class HomeOnNowTileTest {
    private fun station(kind: String, name: String) =
        StationJson.decode<Station>(
            StationJson.station("1", StationJson.onAir("off_schedule"), name = name, kind = kind),
        )

    @Test
    fun namesTheStationWithItsKindAndSaysItTunesIn() {
        assertEquals("Movies: Marvel", Stations.onNowName(station("movies", "Marvel")))
        assertEquals("TV: Cartoons", Stations.onNowName(station("tv", "Cartoons")))
        assertEquals("Music: Radio", Stations.onNowName(station("music", "Radio")))
        assertEquals("Watch Movies: Marvel", Stations.onNowLabel(station("movies", "Marvel")))
        assertEquals("Listen to Music: Radio", Stations.onNowLabel(station("music", "Radio")))
    }

    @Test
    fun theBarIsTheLivePositionOverTheProgramsLengthElseNone() {
        fun fraction(onAir: String) =
            Stations.onNowFraction(StationJson.decode<StationOnAir>(onAir))
        // The fixtures' programs run 6,000,000 ms.
        assertEquals(
            0.25,
            fraction(StationJson.onAir("on_air", StationJson.lantern, position = 1_500_000))!!,
            0.0001,
        )
        assertEquals(
            1.0,
            fraction(StationJson.onAir("on_air", StationJson.lantern, position = 9_000_000))!!,
            0.0,
        )
        assertNull(fraction(StationJson.onAir("on_air", StationJson.lantern)))
        assertNull(fraction(StationJson.onAir("between_programs", next = StationJson.salt)))
    }
}

class StationsModelTest {
    private val server = FakeServer()
    private val model = StationsModel(server.api())
    private val onAir = StationJson.onAir("on_air", StationJson.lantern, StationJson.salt, 1000)
    private val between = StationJson.onAir("between_programs", next = StationJson.salt)

    @Test
    fun listsOneKindsStationsAndOnNowMixesEveryKindOnTheAir() = runBlocking {
        server.on("GET /stations", body = Json.page(StationJson.station("1", onAir)))
        model.load(PlaylistKind.movies)
        assertTrue(server.calls.single().query!!.contains("kind=movies"))
        assertEquals(listOf("1"), model.of(PlaylistKind.movies).map { it.id })
        server.on(
            "GET /stations",
            body =
                Json.page(
                    StationJson.station("1", onAir),
                    StationJson.station("2", onAir, kind = "music"),
                    StationJson.station("3", between, kind = "tv"),
                ),
        )
        model.load()
        assertFalse(server.calls.last().query.orEmpty().contains("kind="))
        assertEquals(listOf("1", "2"), model.onNow.map { it.id })
        assertEquals(listOf("3"), model.of(PlaylistKind.tv).map { it.id })
    }

    @Test
    fun todaysScheduleAsksForTheDayGiven() = runBlocking {
        server.on("GET /stations/3/schedule", body = Json.page(StationJson.lantern))
        val programs = model.schedule("3", LocalDate.of(2025, 1, 3))
        assertTrue(server.calls.single().query!!.contains("date=2025-01-03"))
        assertEquals(listOf("7"), programs?.map { it.itemId })
    }

    @Test
    fun aRefusedSaveShowsTheServersFieldErrors() = runBlocking {
        server.on(
            "POST /stations",
            400,
            """{"code":"bad_request","message":"Check the station.",""" +
                """"fields":[{"field":"playlistId","message":"Pick one of your playlists."}]}""",
        )
        val input = StationInput("Creature Double", "12")
        assertNull(model.save(null, input))
        assertEquals(mapOf("playlistId" to "Pick one of your playlists."), model.fieldErrors)
        assertEquals("Check the station.", model.error)

        server.on(
            "PUT /stations/3",
            body = StationJson.station("3", onAir, name = "Creature Double"),
        )
        assertEquals("Creature Double", model.save("3", input)?.name)
        assertTrue(model.fieldErrors.isEmpty())
        assertNull(model.error)
    }

    @Test
    fun aStationIsMadeForAKindAndAddedToByItsOwnersOnly() = runBlocking {
        server.on("POST /stations", 201, StationJson.station("4", onAir, kind = "music"))
        assertEquals("4", model.create("  Kitchen Radio ", PlaylistKind.music)?.id)
        val body = server.called("POST /stations").single().body
        assertTrue(
            body,
            body.contains(""""kind":"music"""") && body.contains(""""name":"Kitchen Radio""""),
        )
        assertFalse(body, Regex(""""playlistId":\d""").containsMatchIn(body))
        assertEquals(listOf("4"), model.mine(PlaylistKind.music, "1").map { it.id })
        assertTrue(model.mine(PlaylistKind.music, "2").isEmpty())
        assertTrue(model.mine(PlaylistKind.movies, "1").isEmpty())
    }

    @Test
    fun deletingAPlaylistAStationPlaysSaysWhatToDoFirst() = runBlocking {
        val playlists = PlaylistsModel(server.api(), "1")
        server.error("DELETE /profiles/1/playlists/12", 409, "conflict", "in use")
        val playlist =
            StationJson.decode<enterprises.legitimate.rabbitears.api.models.Playlist>(
                """{"id":"12","profileId":"1","name":"Movie night picks","kind":"movies","access":"owner",""" +
                    """"itemCount":11,"durationMs":0,"createdAt":"${Json.T}","modifiedAt":"${Json.T}"}""",
            )
        assertFalse(playlists.delete(playlist))
        assertEquals(
            "A station plays this playlist. Delete the station or give it another playlist first.",
            playlists.error,
        )
    }
}

class StationFormTest {
    private val onAir = StationJson.onAir("off_schedule")

    @Test
    fun theEditorReadsAStationAndWritesItBack() {
        val station =
            StationJson.decode<Station>(
                StationJson.station(
                    "3",
                    onAir,
                    name = "Creature Double",
                    order = "random",
                    days = """["friday","saturday"]""",
                    times = """[{"from":"22:00","to":"02:00"}]""",
                    timing = "half_hour",
                ),
            )
        val form = StationForm(station)
        assertEquals(StationTiming.half_hour, form.timing)
        form.timing = StationTiming.continuous
        assertFalse(form.continuous)
        assertEquals(setOf(Weekday.friday, Weekday.saturday), form.days)
        form.name = "  Creature Double  "
        val input = form.input
        assertEquals("Creature Double", input.name)
        // A save never names a playlist: a station keeps its own, or the one it plays.
        assertNull(input.playlistId)
        assertNull(input.kind)
        assertEquals(StationOrder.random, input.order)
        assertEquals(StationInput.Visibility.household, input.visibility)
        assertEquals(StationTiming.continuous, input.timing)
        assertEquals(setOf(Weekday.friday, Weekday.saturday), input.days)
        assertEquals(listOf(StationTimeRange("22:00", "02:00")), input.times)
    }

    @Test
    fun continuousSendsNoDaysOrTimesAndAServerStationKeepsItsVisibility() {
        val station =
            StationJson.decode<Station>(
                StationJson.station(
                    "3",
                    onAir,
                    visibility = "server",
                    days = """["friday"]""",
                    times = """[{"from":"22:00","to":"02:00"}]""",
                ),
            )
        val form = StationForm(station)
        assertEquals(StationVisibility.server, form.visibility)
        form.continuous = true
        val input = form.input
        assertEquals(emptySet<Weekday>(), input.days)
        assertEquals(emptyList<StationTimeRange>(), input.times)
        assertNull(input.visibility)
    }

    @Test
    fun aNewStationNamesItsKindOnceAndNoPlaylist() {
        val form = StationForm(kind = PlaylistKind.tv)
        assertEquals(StationTiming.half_hour, form.input.timing)
        form.choose(PlaylistKind.movies)
        assertEquals(StationTiming.hour, form.input.timing)
        form.choose(PlaylistKind.music)
        assertEquals(StationTiming.continuous, form.input.timing)
        form.choose(PlaylistKind.tv)
        assertNull(form.input.playlistId)
        assertEquals(PlaylistKind.tv, form.input.kind)
        assertTrue(form.continuous)
        assertFalse(form.ready)
        form.name = "Movie night"
        assertTrue(form.ready)
        assertEquals(StationOrder.playlist, form.input.order)
        assertEquals(StationInput.Visibility.`private`, form.input.visibility)
        form.continuous = false
        form.addRange()
        assertEquals(listOf(StationTimeRange("18:00", "22:00")), form.input.times)
        form.removeRange(0)
        assertEquals(emptyList<StationTimeRange>(), form.input.times)
    }
}
