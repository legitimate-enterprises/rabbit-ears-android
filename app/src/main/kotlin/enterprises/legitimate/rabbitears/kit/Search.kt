package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.SearchResult

val SearchResult.isEmpty: Boolean
    get() =
        movies.isEmpty() &&
            shows.isEmpty() &&
            episodes.isEmpty() &&
            people.isEmpty() &&
            artists.isEmpty() &&
            albums.isEmpty() &&
            songs.isEmpty() &&
            channels.isEmpty() &&
            videos.isEmpty()

/** The TV section: episodes, then shows, each as the server ranks them. */
val SearchResult.tv: List<Item>
    get() = episodes + shows
