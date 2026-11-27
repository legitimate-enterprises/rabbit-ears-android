package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.kit.TitleAction.Collection
import enterprises.legitimate.rabbitears.kit.TitleAction.KidsContent
import enterprises.legitimate.rabbitears.kit.TitleAction.Playlist
import enterprises.legitimate.rabbitears.kit.TitleAction.Station
import enterprises.legitimate.rabbitears.kit.TitleAction.Watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleActionsTest {
    @Test
    fun anAdultFilesAMovieEverywhereAndASeasonOrEpisodeOnlyAsWatchedAndListed() {
        assertEquals(
            listOf(Watched, KidsContent, Playlist, Station, Collection),
            titleActions(TitleKind.Movie, adult = true),
        )
        assertEquals(
            listOf(Watched, KidsContent, Playlist, Station),
            titleActions(TitleKind.Show, adult = true),
        )
        listOf(TitleKind.Season, TitleKind.Episode).forEach {
            assertEquals(listOf(Watched, Playlist, Station), titleActions(it, adult = true))
        }
    }

    @Test
    fun aVideoIsOnlyWatchedAndNothingElseFromYouTubeIsListed() {
        assertEquals(listOf(Watched), titleActions(TitleKind.Video, adult = true))
    }

    @Test
    fun somethingThatIsNoTitleTakesPlaylistsAndStationsForAnAdult() {
        assertEquals(listOf(Playlist, Station), titleActions(null, adult = true))
    }

    @Test
    fun aKidFilesNothing() {
        (TitleKind.entries + null).forEach {
            assertEquals(emptyList<TitleAction>(), titleActions(it, adult = false))
        }
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
