package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.FavoriteKind
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.kit.TitleAction.Collection
import enterprises.legitimate.rabbitears.kit.TitleAction.Dismiss
import enterprises.legitimate.rabbitears.kit.TitleAction.Favorite
import enterprises.legitimate.rabbitears.kit.TitleAction.KidsContent
import enterprises.legitimate.rabbitears.kit.TitleAction.Playlist
import enterprises.legitimate.rabbitears.kit.TitleAction.Station
import enterprises.legitimate.rabbitears.kit.TitleAction.Watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TitleActionsTest {
    @Test
    fun anAdultFilesAMovieEverywhereAndASeasonOrEpisodeOnlyAsWatchedAndListed() {
        assertEquals(
            listOf(Watched, Favorite, KidsContent, Playlist, Station, Collection),
            titleActions(TitleKind.Movie, adult = true),
        )
        assertEquals(
            listOf(Watched, Favorite, KidsContent, Playlist, Station),
            titleActions(TitleKind.Show, adult = true),
        )
        listOf(TitleKind.Season, TitleKind.Episode).forEach {
            assertEquals(
                listOf(Watched, Favorite, Playlist, Station),
                titleActions(it, adult = true),
            )
        }
    }

    @Test
    fun aVideoIsOnlyWatchedAndStarredAndNothingElseFromYouTubeIsListed() {
        assertEquals(listOf(Watched, Favorite), titleActions(TitleKind.Video, adult = true))
    }

    @Test
    fun dismissFollowsWatchedOnlyWhileAMovieEpisodeOrVideoIsInProgress() {
        assertEquals(
            listOf(Watched, Dismiss, Favorite, KidsContent, Playlist, Station, Collection),
            titleActions(TitleKind.Movie, adult = true, inProgress = true),
        )
        assertEquals(
            listOf(Watched, Dismiss, Favorite, Playlist, Station),
            titleActions(TitleKind.Episode, adult = true, inProgress = true),
        )
        assertEquals(
            listOf(Watched, Dismiss, Favorite),
            titleActions(TitleKind.Video, adult = true, inProgress = true),
        )
        listOf(TitleKind.Show, TitleKind.Season).forEach {
            assertFalse(Dismiss in titleActions(it, adult = true, inProgress = true))
        }
        TitleKind.entries.forEach {
            assertFalse(Dismiss in titleActions(it, adult = true))
            assertFalse(Dismiss in titleActions(it, adult = false, inProgress = true))
        }
    }

    @Test
    fun somethingThatIsNoTitleTakesPlaylistsAndStationsForAnAdult() {
        assertEquals(listOf(Playlist, Station), titleActions(null, adult = true))
    }

    @Test
    fun aKidOnlyStarsATitle() {
        TitleKind.entries.forEach {
            assertEquals(listOf(Favorite), titleActions(it, adult = false))
        }
        assertEquals(emptyList<TitleAction>(), titleActions(null, adult = false))
    }

    @Test
    fun eachTitleStarsAsItsOwnKindAndAChannelIsNoKindThatStars() {
        assertEquals(
            listOf(
                FavoriteKind.movie,
                FavoriteKind.show,
                FavoriteKind.season,
                FavoriteKind.episode,
                FavoriteKind.video,
            ),
            TitleKind.entries.map { it.favoriteKind },
        )
        assertEquals(
            listOf("movie", "show", "season", "episode", "artist", "album", "song", "video"),
            FavoriteKind.entries.map { it.value },
        )
    }

    @Test
    fun playlistKindFollowsTheCatalogKind() {
        assertEquals(PlaylistKind.movies, TitleKind.of(ItemKind.movie).playlistKind)
        listOf(ItemKind.show, ItemKind.season, ItemKind.episode).forEach {
            assertEquals(PlaylistKind.tv, TitleKind.of(it).playlistKind)
        }
        assertNull(TitleKind.Video.playlistKind)
    }
}
