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
}
