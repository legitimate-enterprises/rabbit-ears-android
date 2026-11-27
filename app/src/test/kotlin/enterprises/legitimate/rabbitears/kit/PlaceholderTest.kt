package enterprises.legitimate.rabbitears.kit

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderTest {
    @Test
    fun everyKindOfItemHasItsPlaceholderAndSeasonsAndEpisodesShowTheirShows() {
        val kinds =
            listOf(
                "movie",
                "show",
                "season",
                "episode",
                "album",
                "artist",
                "song",
                "channel",
                "video",
            )
        assertEquals(
            listOf(
                PlaceholderKind.Movie,
                PlaceholderKind.Show,
                PlaceholderKind.Show,
                PlaceholderKind.Show,
                PlaceholderKind.Album,
                PlaceholderKind.Artist,
                PlaceholderKind.Song,
                PlaceholderKind.Channel,
                PlaceholderKind.Video,
            ),
            kinds.map(::placeholderKind),
        )
    }

    @Test
    fun collectionsAndPeopleAreKnownByTheirAddress() {
        assertEquals(
            PlaceholderKind.Collection,
            placeholderOfPath("/api/v2/collections/3/artwork/poster"),
        )
        assertEquals(PlaceholderKind.Person, placeholderOfPath("/api/v2/people/3/artwork"))
        assertEquals(null, placeholderOfPath("/api/v2/items/3/artwork/poster"))
    }

    @Test
    fun anImageIsAskedForUnderTheGenerationTheServerLastNamed() {
        val path = "/api/v2/items/1/artwork/poster"
        assertEquals(path, artworkAddress(path, null))
        assertEquals("$path?v=7", artworkAddress(path, 7))
        val custom = "/api/v2/stations/5/image?v=9"
        assertEquals(custom, artworkAddress(custom, 7))
    }
}
