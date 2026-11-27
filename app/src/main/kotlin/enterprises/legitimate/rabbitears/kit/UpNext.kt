package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Success
import enterprises.legitimate.rabbitears.api.models.UpNext

/** The seconds Autoplay waits at a title's end before playing what is up next. */
const val UP_NEXT_SECONDS = 10

/** What Up next plays, as the player opens it: [run] is the playlist row, from a playlist. */
data class UpNextPlay(
    val title: String,
    val itemId: String,
    val run: PlaylistRun?,
    val editionId: String?,
)

/**
 * What the server plays after [itemId] for the profile, from [run]'s row when it was played from a
 * playlist; null for nothing.
 */
suspend fun Api.upNext(profileId: String, itemId: String, run: PlaylistRun?): UpNextPlay? {
    val answer = call {
        profiles.getUpNextWithHttpInfo(profileId, itemId, run?.playlistId, run?.index)
    }
    val next = (answer as? Success<*>)?.data as? UpNext ?: return null
    val row = next.index?.let { i -> run?.takeIf { i < it.rows.size }?.copy(index = i) }
    return UpNextPlay(next.title, next.itemId, row, next.editionId)
}
