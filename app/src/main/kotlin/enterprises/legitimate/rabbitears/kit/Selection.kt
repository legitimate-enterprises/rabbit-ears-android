package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.PlaylistKind

/** Selection mode over a library grid or list: the ids picked, in the order they were picked. */
class Selection {
    var active by mutableStateOf(false)
        private set

    var picked by mutableStateOf(emptyList<String>())
        private set

    /** Starts selecting, with [first] picked when a long-press started it. */
    fun begin(first: String? = null) {
        active = true
        if (first != null && first !in picked) picked = picked + first
    }

    fun toggle(id: String) {
        picked = if (id in picked) picked - id else picked + id
    }

    /**
     * Picks exactly [shown]: what was picked of it stays first, in its order, and the rest follow.
     */
    fun selectAll(shown: List<String>) {
        picked = picked.filter { it in shown } + shown.filter { it !in picked }
    }

    /** Picks nothing but stays in selection mode. */
    fun deselectAll() {
        picked = emptyList()
    }

    fun allPicked(shown: List<String>) = shown.isNotEmpty() && shown.all { it in picked }

    fun end() {
        active = false
        picked = emptyList()
    }
}

/** What a library selection holds, which decides the actions it offers. */
enum class SelectKind(val playlistKind: PlaylistKind?) {
    Movie(PlaylistKind.movies),
    Show(PlaylistKind.tv),
    // Nothing from YouTube goes in a playlist.
    Channel(null),
    Video(null),
    Album(PlaylistKind.music),
    Artist(PlaylistKind.music),
    Song(PlaylistKind.music);

    val watches
        get() = this in setOf(Movie, Show, Channel, Video)

    /** Movies and shows take kids' content, channels for kids. */
    val kidsContent
        get() = this in setOf(Movie, Show, Channel)

    val youtube
        get() = this == Channel || this == Video
}
