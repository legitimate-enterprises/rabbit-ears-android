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

/** The placeholder an address alone names: a collection's or a person's. Items need their kind. */
fun placeholderOfPath(path: String): PlaceholderKind? =
    when {
        path.startsWith("/api/v2/collections/") -> PlaceholderKind.Collection
        path.startsWith("/api/v2/people/") -> PlaceholderKind.Person
        else -> null
    }

/**
 * The address to fetch an image at: under the artwork generation the server last named, which lets
 * the server and the disk cache keep it until the generation moves. Before any response has named
 * one, the plain address, which the server revalidates daily. A custom image's address already
 * names its version and is kept as it is.
 */
fun artworkAddress(path: String, generation: Long?): String =
    if (generation == null || '?' in path) path else "$path?v=$generation"
