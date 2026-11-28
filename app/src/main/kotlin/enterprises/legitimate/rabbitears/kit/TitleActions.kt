package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.FavoriteKind
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.PlaylistKind

/** What a title's ⋮ menu and long-press menu may offer besides Download, which downloads decide. */
enum class TitleAction {
    Watched,
    Favorite,
    KidsContent,
    Playlist,
    Station,
    Collection,
}

/** The titles a menu names; a video is YouTube's. */
enum class TitleKind(val playlistKind: PlaylistKind?, val favoriteKind: FavoriteKind) {
    Movie(PlaylistKind.movies, FavoriteKind.movie),
    Show(PlaylistKind.tv, FavoriteKind.show),
    Season(PlaylistKind.tv, FavoriteKind.season),
    Episode(PlaylistKind.tv, FavoriteKind.episode),
    Video(null, FavoriteKind.video);

    companion object {
        fun of(kind: ItemKind) =
            when (kind) {
                ItemKind.movie -> Movie
                ItemKind.show -> Show
                ItemKind.season -> Season
                ItemKind.episode -> Episode
            }
    }
}

/**
 * What a profile may do to a title of [kind], in the order a menu lists it. A null [kind] is
 * something else a ⋮ menu files: a collection, artist, album or song, whose star is elsewhere. A
 * kid only stars a title, and marks watched on the page itself.
 */
fun titleActions(kind: TitleKind?, adult: Boolean): List<TitleAction> {
    if (!adult) return listOfNotNull(TitleAction.Favorite.takeIf { kind != null })
    return when (kind) {
        TitleKind.Movie ->
            listOf(
                TitleAction.Watched,
                TitleAction.Favorite,
                TitleAction.KidsContent,
                TitleAction.Playlist,
                TitleAction.Station,
                TitleAction.Collection,
            )
        TitleKind.Show ->
            listOf(
                TitleAction.Watched,
                TitleAction.Favorite,
                TitleAction.KidsContent,
                TitleAction.Playlist,
                TitleAction.Station,
            )
        TitleKind.Season,
        TitleKind.Episode ->
            listOf(
                TitleAction.Watched,
                TitleAction.Favorite,
                TitleAction.Playlist,
                TitleAction.Station,
            )
        TitleKind.Video -> listOf(TitleAction.Watched, TitleAction.Favorite)
        null -> listOf(TitleAction.Playlist, TitleAction.Station)
    }
}
