package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.apis.PlaybackApi
import enterprises.legitimate.rabbitears.api.models.Event
import enterprises.legitimate.rabbitears.api.models.EventKind
import enterprises.legitimate.rabbitears.api.models.PlaybackSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * What the server says has changed while this device is signed in and in view. An event names a
 * kind and ids; what changed is read again through the ordinary operations.
 */
class LiveFeed(cache: ResponseCache? = null) {
    init {
        // Answers of the cache's that the server changed are told as an empty batch.
        cache?.onChange { tell(emptyList()) }
    }

    /** Each counts the changes to what it names; a screen showing it loads again when it moves. */
    var watched by mutableIntStateOf(0)
        private set

    var stations by mutableIntStateOf(0)
        private set

    var playlists by mutableIntStateOf(0)
        private set

    var together by mutableIntStateOf(0)
        private set

    /** Offers to and memberships of the device's household. */
    var household by mutableIntStateOf(0)
        private set

    /** A line shown for a few seconds over whatever is up, as "Moved to Living Room". */
    var notice by mutableStateOf<String?>(null)

    private val batches = MutableSharedFlow<List<Event>>(extraBufferCapacity = 64)

    /** Every batch, for what acts on single events; an empty one says to load everything. */
    val events: SharedFlow<List<Event>> = batches

    /** Where the stream stood at its last answer, and for which session, so following resumes. */
    private var cursor: String? = null
    private var cursorFor: Api? = null

    fun tell(batch: List<Event>) {
        val kinds = batch.map { it.kind }.toSet()
        if (batch.isEmpty() || EventKind.watch_state in kinds || EventKind.playback in kinds)
            watched++
        if (batch.isEmpty() || EventKind.station in kinds) stations++
        if (batch.isEmpty() || EventKind.playlist in kinds) playlists++
        if (batch.isEmpty() || EventKind.watch_together in kinds) together++
        if (batch.isEmpty() || EventKind.household in kinds) household++
        batches.tryEmit(batch)
    }

    /**
     * Follows the stream until cancelled. A failed poll is tried again after a growing wait; a
     * server without the stream, or a device signed out or locked, is not asked again. Following
     * again resumes from the last answer, which the server says it can no longer continue with a
     * resync. True says the server no longer knows this device.
     */
    suspend fun follow(api: Api, sleep: suspend (Long) -> Unit = { delay(it) }): Boolean {
        if (cursorFor !== api) {
            cursor = null
            cursorFor = api
        }
        var wait = 1_000L
        while (true) {
            try {
                val page = api.call { events.listEvents(cursor, FOLLOWED) }
                if (page.resync) tell(emptyList())
                else if (page.events.isNotEmpty()) tell(page.events)
                cursor = page.cursor
                wait = 1_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = ApiError.from(e)
                // A timeout or a rate limit is refused for now, so it waits like a failure.
                if (error != null && error.status < 500 && error.status !in RETRIED)
                    return error.code == "unauthorized"
                sleep(wait)
                wait = minOf(wait * 2, LONGEST_WAIT)
            }
        }
    }

    private companion object {
        const val LONGEST_WAIT = 30_000L
        val RETRIED = setOf(408, 429)

        /** The kinds followed besides the first five, which the server sends only when named. */
        val FOLLOWED = listOf(EventKind.library, EventKind.household)
    }
}

/**
 * A title still playing on another device of the profile is asked about before it plays here: Move
 * here, Watch together or Cancel.
 */
object Handoff {
    /**
     * The profile's session on the title on another device, playing now. Null when there is none,
     * and when the server has not answered within [limitMs]: the check is called off, so the title
     * plays unasked.
     */
    suspend fun elsewhere(api: Api, itemId: String, limitMs: Long = 3_000): PlaybackSession? {
        val quick = api.http.newBuilder().callTimeout(limitMs, TimeUnit.MILLISECONDS).build()
        return runCatching {
            api.call { PlaybackApi(endpoint.api, quick).listPlaybackSessions(itemId) }
        }
            .getOrNull()
            ?.items
            ?.firstOrNull { !it.thisDevice }
    }

    fun title(there: PlaybackSession) =
        "Playing on ${there.deviceName} at ${Format.clock(there.positionMs / 1000)}"

    /** Ends the session there; the position to play from here, or null if the server would not. */
    suspend fun move(api: Api, there: PlaybackSession): Long? = runCatching {
        api.call { playback.movePlaybackSession(there.id) }
    }
        .getOrNull()
        ?.positionMs

    /** Whether another device took the player's session with Move here. */
    fun moved(batch: List<Event>, sessionId: String) = batch.any {
        it.kind == EventKind.playback && it.change == "moved" && it.sessionId == sessionId
    }

    /** The Watch together another device opened from the player's session, for it to join. */
    fun asked(batch: List<Event>, sessionId: String): String? =
        batch
            .firstOrNull {
                it.kind == EventKind.watch_together &&
                    it.change == "opened" &&
                    it.sessionId == sessionId
            }
            ?.watchTogetherId

    /** The device that took the session, as in "Moved to Living Room". */
    suspend fun movedTo(api: Api, sessionId: String): String =
        runCatching { api.call { playback.getPlaybackSession(sessionId) } }
            .getOrNull()
            ?.movedToDeviceName ?: "another device"
}
