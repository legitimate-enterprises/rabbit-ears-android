package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.WatchTogether
import enterprises.legitimate.rabbitears.api.models.WatchTogetherInput
import enterprises.legitimate.rabbitears.api.models.WatchTogetherMember
import enterprises.legitimate.rabbitears.api.models.WatchTogetherRole
import enterprises.legitimate.rabbitears.api.models.WatchTogetherStateInput
import enterprises.legitimate.rabbitears.api.models.WatchTogetherStatus
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watch together on one title, from its details screen through the player. The server holds the
 * shared state and answers each change at once to a held poll; its answer always wins. Every play,
 * pause and seek takes effect at one instant on the server's clock, which this model estimates, and
 * the attached player follows it there. [now] is the wall clock in milliseconds.
 */
class WatchTogetherModel(
    private val api: Api,
    private val profileId: String,
    val itemId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The one this profile is in, waiting or joined. */
    var current by mutableStateOf<WatchTogether?>(null)
        private set

    /** Someone else's waiting one on this title, for Join. */
    var invite by mutableStateOf<WatchTogether?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * Opens the player at a position. Called once when playing turns true while no player is
     * attached, as for a member still on details.
     */
    var onStart: ((Long) -> Unit)? = null

    /** The server's clock less this device's, in milliseconds. */
    var offsetMs = 0L
        private set

    private var receivedAt = now()
    private var output: VideoOutput? = null
    private var startRequested = false
    private var bestTrip = Long.MAX_VALUE
    // The latest round trip of a request that was not held, reported so the server leads no
    // further ahead than this device needs.
    private var latestTrip: Int? = null
    private var sampled = 0
    // Until this moment, the player is meeting a change or settling after a seek; no correction.
    private var dueAt = 0L

    val inSync: Boolean
        get() = current?.status == WatchTogetherStatus.joined

    /** The other member, as in "In sync with Helga". */
    val partner: WatchTogetherMember?
        get() = current?.members?.firstOrNull { it.accountId != profileId }

    /** The other member's name; the profile's own other device by that device's name. */
    val partnerName: String
        get() =
            partner?.name
                ?: current?.members?.firstOrNull { it.thisDevice == false }?.deviceName.orEmpty()

    /** "Helga paused", while the other member's pause holds. */
    val pausedNotice: String?
        get() {
            val c = current ?: return null
            if (!inSync || c.playing || c.pausedById == profileId) return null
            return c.pausedByName?.let { "$it paused" }
        }

    /** "Join Helga · 4:12" while someone else's window is open. */
    fun joinLabel(at: Long = now()): String? {
        if (current != null) return null
        val invite = invite ?: return null
        val host = invite.members.firstOrNull { it.role == WatchTogetherRole.host } ?: return null
        val name = if (host.accountId == profileId) host.deviceName else host.name
        return countdown(invite, at)?.let { "Join $name · $it" }
    }

    /** "Waiting · 4:32" while this profile's window is open. */
    fun waitingLabel(at: Long = now()): String? {
        val c = current?.takeIf { it.status == WatchTogetherStatus.waiting } ?: return null
        return countdown(c, at)?.let { "Waiting · $it" }
    }

    /** The m:ss left in the window an invite or a wait shows. */
    fun timeLeft(at: Long = now()): String? = (current ?: invite)?.let { countdown(it, at) }

    /**
     * The shared position at a moment on this device's clock: as of the latest change, run on from
     * its instant while playing.
     */
    fun sharedPositionMs(at: Long = now()): Long {
        val c = current ?: return 0
        val since = at + offsetMs - c.effectiveAtMs
        return c.effectivePositionMs + if (c.playing) maxOf(0, since) else 0
    }

    /**
     * Details polls the title every 5 seconds until this profile is in one, then samples the clock
     * and holds a poll for each change, in the player too, while a second loop corrects drift. Runs
     * until cancelled.
     */
    suspend fun run() = coroutineScope {
        val corrections = launch {
            while (isActive) {
                delay(1_000)
                output?.let { correct(it) }
            }
        }
        while (isActive) {
            when {
                current == null -> {
                    refresh()
                    delay(5_000)
                }
                sampled < SAMPLES -> {
                    sampled++
                    poll()
                }
                else -> {
                    val version = current?.version
                    val began = now()
                    val answered = poll(held = true)
                    // A hold that ran out is asked again at once: a change in the gap would be
                    // heard late. A failure, or an answer that came back unchanged and quick,
                    // waits.
                    if (current?.version == version && !(answered && now() - began >= HELD_MS)) {
                        delay(1_000)
                    }
                }
            }
        }
        corrections.cancel()
    }

    suspend fun refresh() {
        val page =
            runCatching { api.call { together.listWatchTogethers(itemId = itemId) } }.getOrNull()
                ?: return
        val open = page.items.filter { it.status != WatchTogetherStatus.ended }
        open.firstOrNull(::isMember)?.let(::receive)
        receivedAt = now()
        invite =
            if (current == null)
                open.firstOrNull { it.status == WatchTogetherStatus.waiting && !isMember(it) }
            else null
    }

    suspend fun open() = act { together.openWatchTogether(WatchTogetherInput(itemId)) }

    /**
     * Opens one from the session playing on another device of the profile, at that session's
     * position; false when the server would not. This device's player is open, so the start opens
     * none.
     */
    suspend fun open(fromSessionId: String): Boolean {
        onStart = null
        act { together.openWatchTogether(WatchTogetherInput(itemId, fromSessionId)) }
        return current != null
    }

    /**
     * Joins the one another device of the profile opened from this device's session, and plays for
     * both from where this device is, in the player it has open.
     */
    suspend fun join(id: String, positionMs: Long): Boolean {
        onStart = null
        act { together.joinWatchTogether(id) }
        if (!inSync) return false
        send(true, positionMs)
        return inSync
    }

    suspend fun join() {
        val id = invite?.id ?: return
        act { together.joinWatchTogether(id) }
    }

    /**
     * Ends it for both; this player plays on alone. A failed request still leaves, since the server
     * ends it once no member polls.
     */
    suspend fun leave() {
        val id = current?.id ?: return
        end()
        runCatching { api.call { together.leaveWatchTogether(id) } }
    }

    /**
     * One poll: a quick one samples the clock, a held one waits for the next change. Whether the
     * server answered.
     */
    suspend fun poll(held: Boolean = false): Boolean {
        val c = current ?: return false
        return try {
            val sent = now()
            val next = api.call {
                together.getWatchTogether(c.id, if (held) c.version else null, latestTrip)
            }
            if (!held) clock(next, sent)
            receive(next)
            true
        } catch (e: Exception) {
            // Gone means ended; any other failure waits for the next poll.
            if (ApiError.from(e)?.status == 404) end()
            false
        }
    }

    /** The player this device shows; it is brought to the shared state at once. */
    fun attach(output: VideoOutput) {
        this.output = output
        current?.let(::follow)
    }

    fun detach() {
        output = null
    }

    /** Plays for both, each side's player opening if it has none, and starting at one instant. */
    suspend fun play() = send(true, position())

    /** Pauses here at once and for the other at the instant it takes effect. */
    suspend fun pause() {
        val at = position()
        output?.pause()
        send(false, at)
    }

    suspend fun seek(ms: Long) {
        output?.seek(ms)
        send(current?.playing ?: false, ms)
    }

    private fun position() = output?.positionMs ?: sharedPositionMs()

    private suspend fun send(playing: Boolean, positionMs: Long) {
        val id = current?.id ?: return
        if (!inSync) return
        val sent = now()
        act {
            together
                .setWatchTogetherState(
                    id,
                    WatchTogetherStateInput(playing, maxOf(0, positionMs)),
                    latestTrip,
                )
                .also { clock(it, sent) }
        }
    }

    private suspend fun act(request: Api.() -> WatchTogether) {
        try {
            receive(api.call(request))
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    /** The server's clock as of the round trip's midpoint, from the quickest round trip yet. */
    private fun clock(answer: WatchTogether, sent: Long) {
        val trip = now() - sent
        latestTrip = trip.toInt()
        if (trip >= bestTrip) return
        bestTrip = trip
        offsetMs = answer.serverTimeMs - sent - trip / 2
    }

    /**
     * An answer older than one already applied is dropped: polls and actions overlap, and `version`
     * orders them.
     */
    private fun receive(next: WatchTogether) {
        val c = current
        if (c != null && c.id == next.id && next.version < c.version) return
        receivedAt = now()
        if (next.status == WatchTogetherStatus.ended) return end()
        current = next
        invite = null
        follow(next)
    }

    /**
     * Opens the player when playing turns true without one; otherwise has the player apply the
     * state at its instant, or at once if that has passed.
     */
    private fun follow(state: WatchTogether) {
        if (state.status != WatchTogetherStatus.joined) return
        if (!state.playing) startRequested = false
        val output = output
        if (output == null) {
            if (state.playing && !startRequested) {
                startRequested = true
                onStart?.invoke(sharedPositionMs())
            }
            return
        }
        val wait = state.effectiveAtMs - (now() + offsetMs)
        if (wait > 0) {
            output.schedule(state.playing, state.effectivePositionMs, wait)
            dueAt = now() + wait
            return
        }
        dueAt = 0
        if (state.playing && output.isPaused) output.play()
        if (!state.playing && !output.isPaused) output.pause()
        correct(output, force = true)
    }

    /**
     * Seeks only beyond the drift allowance and never while buffering, when the shared position
     * runs on ahead of a player still catching up to the last seek.
     */
    private fun correct(output: VideoOutput, force: Boolean = false) {
        val c = current ?: return
        if (c.status != WatchTogetherStatus.joined || output.isBuffering) return
        if (now() < dueAt + if (force) 0L else SETTLE_MS) return
        if (!force && (!c.playing || output.isPaused)) return
        val shared = sharedPositionMs()
        if (abs(output.positionMs - shared) <= DRIFT_MS) return
        output.seek(shared)
        dueAt = now()
    }

    private fun end() {
        current = null
        startRequested = false
    }

    /** This device takes part: the profile's other device may, in one this device is not in. */
    private fun isMember(t: WatchTogether) =
        t.members.any { it.accountId == profileId && it.thisDevice != false }

    /**
     * m:ss of the window left, counted from the server's figure so the device clock does not
     * matter; null once it has run out.
     */
    private fun countdown(t: WatchTogether, at: Long): String? {
        val left = t.joinMsLeft - (at - receivedAt)
        return if (left > 0) Format.clock((left + 999) / 1000) else null
    }

    companion object {
        /** A playing player this far from the shared position seeks to it. */
        const val DRIFT_MS = 500L
        /** Quick answers sampled for the clock before polls are held. */
        const val SAMPLES = 3
        /** A held poll answered unchanged after at least this long ran out its hold. */
        const val HELD_MS = 1_000L
        /** After a change or a correcting seek, the player is left this long before the next. */
        const val SETTLE_MS = 3_000L
    }
}
