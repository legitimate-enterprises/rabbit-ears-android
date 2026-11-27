package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.WatchState
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shuffle over a show, season or collection: one flat random order of everything that can play. */
class ShuffleTest {
    private fun item(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(Item.serializer(), json)

    private fun watch(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(WatchState.serializer(), json)

    private fun missing(json: String) = json.dropLast(1) + ""","unavailable":true}"""

    private val catalog =
        Catalog(
            listOf(
                    Json.item("10", "show", "Severance"),
                    Json.item("11", "season", "Season 1", parent = 10, season = 1),
                    Json.item("12", "season", "Season 2", parent = 10, season = 2),
                    Json.item("13", "season", "Specials", parent = 10, season = 0),
                    Json.item("101", "episode", "Good News", parent = 11, season = 1, episode = 1),
                    Json.item("102", "episode", "Half Loop", parent = 11, season = 1, episode = 2),
                    Json.item("201", "episode", "Hello", parent = 12, season = 2, episode = 1),
                    missing(
                        Json.item("202", "episode", "Gone", parent = 12, season = 2, episode = 2),
                    ),
                    Json.item("301", "episode", "Extra", parent = 13, season = 0, episode = 1),
                )
                .map(::item),
            listOf(Json.watch(101, watched = true), Json.watch(102, position = 60_000))
                .map(::watch),
        )

    private fun ids(run: PlaylistRun?) = run?.rows?.map { it.itemId }

    @Test
    fun aShowShufflesEveryEpisodeAcrossSeasonsInOneOrder() {
        val scope = null.playable(catalog.episodes("10"))
        val run = PlaylistRun.shuffled(scope, Random(7))
        assertEquals(scope.shuffled(Random(7)).map { it.id }, ids(run))
        // Watched, in progress and specials alike; only the title with no file is left out.
        assertEquals(setOf("101", "102", "201", "301"), ids(run)?.toSet())
        assertNull(run?.playlistId)
        assertEquals(0, run?.index)
        assertTrue(run!!.rows.all { it.editionId == null })
    }

    @Test
    fun theOrderIsAnyOrderNotSeasonBySeason() {
        val scope = null.playable(catalog.episodes("10"))
        val orders = (1..40).mapNotNull { ids(PlaylistRun.shuffled(scope, Random(it))) }.toSet()
        assertTrue(orders.size > 10)
        assertTrue(orders.any { it.indexOf("201") < it.indexOf("101") })
    }

    @Test
    fun aSeasonShufflesItsOwnEpisodes() {
        val run = PlaylistRun.shuffled(null.playable(catalog.episodes("11")), Random(1))
        assertEquals(setOf("101", "102"), ids(run)?.toSet())
    }

    @Test
    fun oneTitleIsARunOfOne() {
        val run = PlaylistRun.shuffled(null.playable(catalog.episodes("13")), Random(1))
        assertEquals(listOf("301"), ids(run))
        assertNull(run?.next())
    }

    @Test
    fun nothingThatCanPlayIsNoShuffle() {
        assertTrue(null.playable(listOf(catalog.item("202")!!)).isEmpty())
        assertNull(PlaylistRun.shuffled(emptyList(), Random(1)))
    }
}
