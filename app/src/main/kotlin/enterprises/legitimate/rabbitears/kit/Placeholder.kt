package enterprises.legitimate.rabbitears.kit

/** What a tile shows over its color while its image loads, and for good when it has none. */
enum class PlaceholderKind {
    Movie,
    Show,
    Album,
    Artist,
    Song,
    Channel,
    Video,
    Playlist,
    Station,
    Collection,
    Person,
}

/** The placeholder of an item of this kind; a season and an episode show their show's. */
fun placeholderKind(itemKind: String): PlaceholderKind =
    when (itemKind) {
        "show",
        "season",
        "episode" -> PlaceholderKind.Show
        "album" -> PlaceholderKind.Album
        "artist" -> PlaceholderKind.Artist
        "song" -> PlaceholderKind.Song
        "channel" -> PlaceholderKind.Channel
        "video" -> PlaceholderKind.Video
        else -> PlaceholderKind.Movie
    }
