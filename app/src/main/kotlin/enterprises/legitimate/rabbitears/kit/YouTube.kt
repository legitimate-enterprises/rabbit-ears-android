package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.KidsContentBatchInput
import enterprises.legitimate.rabbitears.api.models.WatchStateBatchInput
import enterprises.legitimate.rabbitears.api.models.WatchStateInput
import enterprises.legitimate.rabbitears.api.models.YouTubeChannel
import enterprises.legitimate.rabbitears.api.models.YouTubeChannelForKidsInput
import enterprises.legitimate.rabbitears.api.models.YouTubeChannelSort
import enterprises.legitimate.rabbitears.api.models.YouTubeChannelWatchInput
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import enterprises.legitimate.rabbitears.api.models.YouTubeVideoSort
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Channel: the header and every video newest first. */
data class ChannelDetail(val channel: YouTubeChannel, val videos: List<YouTubeVideo>)

enum class VideoSort(val label: String, val api: YouTubeVideoSort) {
    Newest("Newest", YouTubeVideoSort.newest),
    Oldest("Oldest", YouTubeVideoSort.oldest),
    Title("Title", YouTubeVideoSort.title),
    Channel("Channel", YouTubeVideoSort.channel),
    Length("Length", YouTubeVideoSort.length),
}

/**
 * The active profile's YouTube. The server answers a kid with empty pages and 404s, so nothing here
 * checks the profile kind. Watch state is the profile's, written per video or per channel.
 */
class YouTubeModel(
    val api: Api,
    val profileId: String,
    private val offline: OfflineLibrary? = null,
) {
    var channels by mutableStateOf<List<YouTubeChannel>?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** shortcut: one page of 200 videos per view; page on scroll once channels keep more. */
    suspend fun videos(
        sort: VideoSort,
        unwatched: Boolean = false,
        limit: Int = PAGE,
    ): List<YouTubeVideo> =
        read({ videos().filter { !unwatched || !it.watched } }) {
                api.call {
                        youtube.listYouTubeVideos(
                            unwatched = unwatched.takeIf { it },
                            sort = sort.api,
                            limit = limit,
                        )
                    }
                    .items
            }
            .orEmpty()

    /** Home's New from your channels. */
    suspend fun newFromChannels(): List<YouTubeVideo> =
        videos(VideoSort.Newest, unwatched = true, limit = NEW)

    /** Home's In progress: every video with a resume position, latest played first. */
    suspend fun inProgress(): List<YouTubeVideo> =
        read({ inProgress() }) {
                api.call {
                        youtube.listYouTubeVideos(
                            inProgress = true,
                            sort = YouTubeVideoSort.played,
                            limit = PAGE,
                        )
                    }
                    .items
            }
            .orEmpty()

    suspend fun loadChannels() {
        channels =
            read({ channels() }) {
                pages { o ->
                    api.call {
                            youtube.listYouTubeChannels(
                                sort = YouTubeChannelSort.title,
                                limit = PAGE,
                                offset = o ?: 0,
                            )
                        }
                        .let { it.items to it.nextOffset }
                }
            } ?: channels
    }

    suspend fun channel(id: String): ChannelDetail? =
        read({ channel(id) }) {
            ChannelDetail(
                api.call { youtube.getYouTubeChannel(id) },
                pages { o ->
                    api.call {
                            youtube.listYouTubeVideos(
                                channelId = id,
                                sort = YouTubeVideoSort.newest,
                                limit = PAGE,
                                offset = o ?: 0,
                            )
                        }
                        .let { it.items to it.nextOffset }
                },
            )
        }

    suspend fun video(id: String): YouTubeVideo? =
        read({ video(id) }) { api.call { youtube.getYouTubeVideo(id) } }

    /** Whether the channel is for kids, any adult's to set; false when it was refused. */
    suspend fun setForKids(channelId: String, forKids: Boolean): Boolean =
        read {
            api.call {
                youtube.setYouTubeChannelForKids(channelId, YouTubeChannelForKidsInput(forKids))
            }
        } != null

    /** Marks a selection of channels and videos in one request; a channel marks each video. */
    suspend fun setWatched(ids: List<String>, watched: Boolean): Boolean =
        read {
            api.call { profiles.putWatchStates(profileId, WatchStateBatchInput(ids, watched)) }
        } != null

    /** Marks a selection of channels for kids, or not, in one request. */
    suspend fun setForKids(ids: List<String>, forKids: Boolean): Boolean =
        read { api.call { items.setKidsContents(KidsContentBatchInput(ids, forKids)) } } != null

    /** Mark all watched, or unwatched, on Channel. */
    suspend fun setWatched(channelId: String, watched: Boolean): Boolean =
        read {
            api.call {
                youtube.putYouTubeChannelWatchState(
                    profileId,
                    channelId,
                    YouTubeChannelWatchInput(watched),
                )
            }
        } != null

    /** Watched on Video; either way the resume position is forgotten. */
    suspend fun setWatched(video: YouTubeVideo, watched: Boolean): Boolean =
        read({ if (has(video.id)) record(video.id, 0, watched) else throw IOException() }) {
            api.call { profiles.putWatchState(profileId, video.id, WatchStateInput(watched)) }
        } != null

    /** Null after an error, which [error] names; [local] answers when the server can't. */
    private suspend fun <T> read(
        local: (OfflineLibrary.() -> T?)? = null,
        block: suspend () -> T?,
    ): T? =
        try {
            (if (local == null) block() else offline.answer(local, block)).also { error = null }
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
            null
        }

    companion object {
        const val PAGE = 200
        const val NEW = 20
    }
}

/** The watched fraction Play resumes the video at; null when Play starts it from the top. */
fun YouTubeVideo.resumeProgress(): Double? =
    durationMs?.takeIf { it > 0 && positionMs > 0 }?.let { minOf(1.0, positionMs.toDouble() / it) }

/** When a video came out, as the boards say it: `today`, `3 days ago`, `last month`. */
fun Format.published(day: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(day, today)
    fun ago(count: Long, unit: String) = if (count == 1L) "last $unit" else "$count ${unit}s ago"
    return when {
        days < 1 -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> ago(days / 7, "week")
        days < 365 -> ago(days / 30, "month")
        else -> ago(days / 365, "year")
    }
}

/** A channel's caption: `214 videos 12 unwatched Last video today`, as separate parts. */
fun Format.channelParts(channel: YouTubeChannel, today: LocalDate = LocalDate.now()) =
    listOfNotNull(
        plural(channel.videoCount, "video"),
        "${channel.unwatchedCount} unwatched".takeIf { channel.unwatchedCount > 0 },
        channel.lastVideoOn?.let { "Last video ${published(it, today)}" },
    )

/** Where the video's channel avatar is asked for. */
val YouTubeVideo.channelArt: String
    get() = ArtworkPath.item(channelId, channelPosterUrl)
