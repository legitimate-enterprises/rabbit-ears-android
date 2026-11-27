package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.WatchState
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun item(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(Item.serializer(), json)

    private fun watch(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(WatchState.serializer(), json)

    private val drama = """[{"id":1,"name":"Drama"}]"""
    private val items =
        listOf(
                Json.item(
                    1,
                    "movie",
                    "Dune",
                    added = "2025-03-01T00:00:00Z",
                    runtime = 166,
                    rating = "PG-13",
                    year = 2024,
                    genres = drama,
                ),
                Json.item(
                    2,
                    "movie",
                    "Flow",
                    added = "2025-02-01T00:00:00Z",
                    runtime = 85,
                    rating = "R",
                    year = 2024,
                    kids = true,
                ),
                Json.item(
                    3,
                    "movie",
                    "Alien",
                    added = "2024-12-01T00:00:00Z",
                    runtime = 117,
                    rating = "PG",
                    year = 2025,
                    kids = false,
                ),
                Json.item(10, "show", "Severance", runtime = 50, genres = drama),
                Json.item(11, "season", "Season 1", parent = 10, season = 1),
                Json.item(12, "season", "Season 2", parent = 10, season = 2),
                Json.item(13, "season", "Specials", parent = 10, season = 0),
                Json.item(
                    101,
                    "episode",
                    "Good News",
                    parent = 11,
                    season = 1,
                    episode = 1,
                    runtime = 57,
                ),
                Json.item(
                    102,
                    "episode",
                    "Half Loop",
                    parent = 11,
                    season = 1,
                    episode = 2,
                    runtime = 53,
                ),
                Json.item(
                    201,
                    "episode",
                    "Hello",
                    parent = 12,
                    season = 2,
                    episode = 1,
                    runtime = 50,
                ),
                Json.item(
                    202,
                    "episode",
                    "Goodbye",
                    parent = 12,
                    season = 2,
                    episode = 2,
                    runtime = 44,
                ),
                Json.item(301, "episode", "Extra", parent = 13, season = 0, episode = 1),
                Json.item(
                    20,
                    "show",
                    "Bluey",
                    added = "2025-04-01T00:00:00Z",
                    rating = "TV-Y",
                    kids = true,
                ),
                Json.item(21, "season", "Season 1", parent = 20, season = 1),
                Json.item(
                    401,
                    "episode",
                    "Magic",
                    parent = 21,
                    season = 1,
                    episode = 1,
                    runtime = 7,
                ),
            )
            .map(::item)

    private fun catalog(vararg watch: String) = Catalog(items, watch.map(::watch))

    @Test
    fun ordersSeasonsWithSpecialsLastAndEpisodesByNumber() {
        val c = catalog()
        assertEquals(listOf(11L, 12L, 13L), c.children(10).map { it.id })
        assertEquals(listOf(101L, 102L, 201L, 202L, 301L), c.episodes(10).map { it.id })
        assertEquals(10L, c.show(c.item(201)!!)?.id)
    }

    @Test
    fun upNextIsTheTwoEpisodesAfterAcrossSeasonsAndNoneAfterTheLast() {
        val c = catalog()
        assertEquals(listOf(201L, 202L), c.nextEpisodes(c.item(102)!!).map { it.id })
        assertEquals(listOf(301L), c.nextEpisodes(c.item(202)!!).map { it.id })
        assertTrue(c.nextEpisodes(c.item(301)!!).isEmpty())
    }

    @Test
    fun resumesTheEpisodeInProgressElseTheOneAfterTheLatestWatched() {
        assertEquals(101L, catalog().resumeTarget(10)?.id)
        assertEquals(
            201L,
            catalog(
                    Json.watch(101, watched = true),
                    Json.watch(102, watched = true, played = "2025-05-01T00:00:00Z"),
                )
                .resumeTarget(10)
                ?.id,
        )
        assertEquals(102L, catalog(Json.watch(102, position = 60_000)).resumeTarget(10)?.id)
        assertEquals(202L, catalog(Json.watch(201, watched = true)).resumeTarget(12)?.id)
    }

    @Test
    fun startPlaysAMovieOrEpisodeFromItsResumePointAndAShowOrSeasonFromItsTarget() {
        val c = catalog(Json.watch(1, position = 600_000), Json.watch(102, position = 60_000))
        assertEquals(Start(1, 600_000), c.start(c.item(1)!!))
        assertEquals(Start(3, 0), c.start(c.item(3)!!))
        assertEquals(Start(102, 60_000), c.start(c.item(10)!!))
        assertEquals(Start(102, 60_000), c.start(c.item(11)!!))
        assertEquals(Start(201, 0), c.start(c.item(12)!!))
        assertEquals(Start(101, 0), catalog().start(c.item(10)!!))
    }

    @Test
    fun startIsNothingForAShowWithoutEpisodes() {
        val empty = Catalog(listOf(item(Json.item(50, "show", "Pilotless"))), emptyList())
        assertNull(empty.start(empty.item(50)!!))
    }

    @Test
    fun aWatchedTitlePlayedAgainPartwayIsInProgress() {
        val c = catalog(Json.watch(1, position = 600_000, watched = true))
        assertTrue(c.isInProgress(1))
        assertEquals(listOf(1L), c.continueWatching.map { it.id })
    }

    @Test
    fun inProgressMergesVideosAndTitlesLatestPlayedFirst() {
        val c =
            catalog(
                Json.watch(1, position = 600_000, played = "2025-05-02T00:00:00Z"),
                Json.watch(102, position = 60_000, played = "2025-05-04T00:00:00Z"),
            )
        val video =
            Serializer.kotlinxSerializationJson.decodeFromString(
                YouTubeVideo.serializer(),
                """{"id":900,"libraryId":2,"channelId":30,"channelTitle":"Workbench",""" +
                    """"title":"Lathe","watched":true,"positionMs":5000,"addedAt":"${Json.T}",""" +
                    """"lastPlayedAt":"2025-05-03T00:00:00Z"}""",
            )
        assertEquals(
            listOf(
                Resumable.Title(c.item(102)!!),
                Resumable.Video(video),
                Resumable.Title(c.item(1)!!),
            ),
            c.continueWatching(listOf(video)),
        )
    }

    @Test
    fun homeRowsComeFromWatchState() {
        val c =
            catalog(
                Json.watch(1, position = 600_000, played = "2025-05-02T00:00:00Z"),
                Json.watch(102, position = 60_000, played = "2025-05-03T00:00:00Z"),
                Json.watch(401, watched = true, played = "2025-05-01T00:00:00Z"),
            )
        assertEquals(listOf(102L, 1L), c.continueWatching.map { it.id })
        // Severance has an episode in progress, so it is not Next up; Bluey has nothing after.
        assertEquals(emptyList<Long>(), c.nextUp.map { it.id })
        val next = catalog(Json.watch(101, watched = true)).nextUp
        assertEquals(listOf(102L), next.map { it.id })
        assertEquals(listOf(20L, 1L, 2L, 10L, 3L), c.recentlyAdded.map { it.id })
    }

    @Test
    fun theHeroIsTheMostRecentInProgressAndNextUpIsNeverItsFallback() {
        val none = catalog(Json.watch(101, watched = true))
        assertNull(none.homeLead(emptyList()).hero)
        assertTrue(none.homeLead(emptyList()).also.isEmpty())
        assertEquals(listOf(102L), none.nextUp.map { it.id })

        val one = catalog(Json.watch(1, position = 600_000))
        assertEquals(Resumable.Title(one.item(1)!!), one.homeLead(emptyList()).hero)
        assertTrue(one.homeLead(emptyList()).also.isEmpty())

        val several =
            catalog(
                Json.watch(1, position = 600_000, played = "2025-05-02T00:00:00Z"),
                Json.watch(102, position = 60_000, played = "2025-05-04T00:00:00Z"),
            )
        val lead = several.homeLead(emptyList())
        assertEquals(Resumable.Title(several.item(102)!!), lead.hero)
        assertEquals(listOf<Resumable>(Resumable.Title(several.item(1)!!)), lead.also)
    }

    @Test
    fun anItemReplacedOrAddedKeepsTheRestAndTheWatchState() {
        val c = catalog(Json.watch(1, position = 600_000))
        val flagged = item(Json.item(2, "movie", "Flow").dropLast(1) + ""","kidsContent":true}""")
        val fresh = c.with(flagged)
        assertEquals(true, fresh.item(2)?.kidsContent)
        assertEquals(c.recentlyAdded.size, fresh.recentlyAdded.size)
        assertTrue(fresh.isInProgress(1))
        val added = c.with(item(Json.item(99, "movie", "New")))
        assertEquals("New", Catalog.title(added.item(99)!!))
    }

    @Test
    fun progressAndTimeLeftComeFromRuntime() {
        val c = catalog(Json.watch(1, position = 66 * 60_000L))
        assertEquals(100 * 60_000L, c.remainingMs(1))
        assertEquals(66.0 / 166, c.progress(1)!!, 1e-9)
        assertNull(c.progress(2))
    }

    @Test
    fun gridFiltersSortsAndCounts() {
        val c = catalog(Json.watch(1, watched = true), Json.watch(101, position = 1000))
        assertEquals(listOf(1L, 2L, 3L), c.grid(ItemKind.movie).map { it.id })
        assertEquals(listOf(2L, 3L), c.grid(ItemKind.movie, GridFilter.Unwatched).map { it.id })
        assertEquals(listOf(2L), c.grid(ItemKind.movie, GridFilter.Kids).map { it.id })
        assertEquals(
            listOf(3L, 1L, 2L),
            c.grid(ItemKind.movie, sort = GridSort.Title).map { it.id },
        )
        assertEquals(listOf(3L, 1L, 2L), c.grid(ItemKind.movie, sort = GridSort.Year).map { it.id })
        assertEquals(listOf(1L), c.grid(ItemKind.movie, genre = 1).map { it.id })
        assertEquals(listOf(10L), c.grid(ItemKind.show, GridFilter.InProgress).map { it.id })
        assertEquals(listOf(20L), c.grid(ItemKind.show, GridFilter.Kids).map { it.id })
        val started = catalog(Json.watch(2, position = 1000))
        assertEquals(listOf(2L), started.grid(ItemKind.movie, GridFilter.InProgress).map { it.id })
        assertEquals(
            listOf("All", "Unwatched", "In progress", "Kids"),
            GridFilter.entries.map { it.label },
        )
        assertEquals(listOf("Drama"), c.genres(ItemKind.show).map { it.name })
        assertEquals(5, c.unwatchedCount(10))
    }

    @Test
    fun theKidsPillFollowsTheServersFlagNotTheRating() {
        // Flow is rated R with the override on; Alien is rated PG with it off.
        assertEquals(listOf(2L), catalog().grid(ItemKind.movie, GridFilter.Kids).map { it.id })
    }

    private val held =
        listOf(
                Json.item(5, "movie", "Cars", added = "2025-06-01T00:00:00Z", kids = true),
                Json.item(
                    30,
                    "show",
                    "Bing",
                    added = "2025-06-02T00:00:00Z",
                    genres = """[{"id":9,"name":"Animation"}]""",
                    kids = true,
                ),
                Json.item(31, "season", "Season 1", parent = 30, season = 1, kids = true),
                Json.item(
                    501,
                    "episode",
                    "Shoes",
                    parent = 31,
                    season = 1,
                    episode = 1,
                    kids = true,
                ),
            )
            .map(::item)

    private fun holding(vararg watch: String) = Catalog(items, watch.map(::watch), held)

    @Test
    fun heldKidsTitlesShowOnlyUnderThePillAndOpenLikeAnyTitle() {
        val c = holding(Json.watch(501, position = 1000))
        assertEquals(listOf(5L, 2L), c.grid(ItemKind.movie, GridFilter.Kids).map { it.id })
        assertEquals(listOf(30L, 20L), c.grid(ItemKind.show, GridFilter.Kids).map { it.id })
        assertTrue(5L !in c.grid(ItemKind.movie, GridFilter.Unwatched).map { it.id })
        assertTrue(30L !in c.grid(ItemKind.show, GridFilter.InProgress).map { it.id })

        assertEquals("Bing", Catalog.title(c.item(30)!!))
        assertEquals(listOf(31L), c.children(30).map { it.id })
        assertEquals(listOf(501L), c.episodes(30).map { it.id })
        assertEquals(30L, c.show(c.item(501)!!)?.id)
        assertEquals(1, c.unwatchedCount(30))
        assertTrue(c.isInProgress(501))
    }

    @Test
    fun heldKidsTitlesNeverReachHomeOrTheFilters() {
        val c = holding(Json.watch(501, position = 1000))
        assertTrue(c.recentlyAdded.none { it.id in setOf(5L, 30L) })
        assertTrue(c.continueWatching.none { it.id == 501L })
        assertTrue(c.nextUp.none { it.id == 501L })
        assertEquals(listOf("Drama"), c.genres(ItemKind.show).map { it.name })
        assertEquals(
            c.grid(ItemKind.show).map { it.id },
            catalog().grid(ItemKind.show).map { it.id },
        )
        assertEquals(
            catalog().grid(ItemKind.movie).map { it.id },
            c.grid(ItemKind.movie).map { it.id },
        )
    }

    @Test
    fun aHeldTitleChangedInPlaceKeepsTheHeldOnesAndLeavesThePillWhenNoLongerKids() {
        val c = holding()
        assertEquals(listOf(5L, 2L), c.grid(ItemKind.movie, GridFilter.Kids).map { it.id })
        val off = c.with(item(Json.item(5, "movie", "Cars", kids = false)))
        assertEquals(listOf(2L), off.grid(ItemKind.movie, GridFilter.Kids).map { it.id })
        assertEquals(listOf(30L, 20L), off.grid(ItemKind.show, GridFilter.Kids).map { it.id })
    }

    @Test
    fun aStoredChoiceIsReadBackByNameElseTheDefault() {
        assertEquals(GridSort.Year, enumNamed("Year", GridSort.RecentlyAdded))
        assertEquals(GridSort.RecentlyAdded, enumNamed("Gone", GridSort.RecentlyAdded))
        assertEquals(GridFilter.All, enumNamed(null, GridFilter.All))
    }

    @Test
    fun homeCaptionsNameTheShowAndTheTimeLeft() {
        val c =
            catalog(
                Json.watch(202, position = 21 * 60_000L),
                Json.watch(1, position = 94 * 60_000L),
            )
        val episode = c.item(202)!!
        assertEquals("Severance", c.heading(episode))
        assertEquals("S2 E2 · 23m left", c.leftCaption(episode))
        assertEquals("S2 E2 · Goodbye · 23m left", c.leftCaption(episode, named = true))
        assertEquals("1h 12m left", c.leftCaption(c.item(1)!!))
        assertEquals("S2 E2 · 44m", c.episodeCaption(episode))
    }

    @Test
    fun aTileThatPlaysAtOnceSaysSo() {
        val c = catalog()
        assertEquals("Play Severance, S2 E2", c.playLabel(c.item(202)!!))
        assertEquals("Play ${c.heading(c.item(1)!!)}", c.playLabel(c.item(1)!!))
    }

    @Test
    fun captionsReadRuntimeFirst() {
        val c = catalog()
        assertEquals("2h 46m · 2024", c.caption(c.item(1)!!))
        assertEquals("50m · 2 seasons", c.caption(c.item(10)!!))
        assertEquals(
            "7m · 1 season",
            c.caption(
                c.item(20)!!.copy(metadata = c.item(20)!!.metadata?.copy(runtimeMinutes = 7)),
            ),
        )
    }
}
