package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.api.models.PlaybackEvent
import enterprises.legitimate.rabbitears.api.models.PlaybackEventKind
import enterprises.legitimate.rabbitears.api.models.PlaybackRequest
import enterprises.legitimate.rabbitears.api.models.PlaybackStream
import enterprises.legitimate.rabbitears.api.models.PlaybackUpdate
import enterprises.legitimate.rabbitears.api.models.PositionReport
import enterprises.legitimate.rabbitears.api.models.Station
import enterprises.legitimate.rabbitears.api.models.StationStatus
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What plays the music: ExoPlayer under the media session in the app, a recorder in tests. Called
 * on the main thread. [isPaused] is observable state, since the system pauses for audio focus.
 */
interface AudioOutput {
    /** [artUrl] is the song's album art for the lock screen. */
    fun load(url: String, hls: Boolean, song: MusicSong, artUrl: String)

    fun play()

    fun pause()

    fun seek(ms: Long)

    fun stop()

    val positionMs: Long
    val isPaused: Boolean
    var onFailure: ((String) -> Unit)?
    var onEnd: (() -> Unit)?
}

/**
 * The music engine. It needs no screen: the app, the notification and the lock screen drive the
 * same one, which outlives the activity. Each song is its own playback session, started with the
 * device's snapshot; the server picks the quality. Runs on [scope], the process's main scope, where
 * screens launch its actions too: a start outlives the screen that asked for it.
 */
class MusicPlayer(
    val api: Api,
    val profileId: Long,
    private val output: AudioOutput,
    private val capabilities: CapabilitySource,
    val scope: CoroutineScope,
    /** The song on the device, played in place of a session when [preferLocal] or offline. */
    private val local: (songId: Long) -> LocalCopy? = { null },
    private val preferLocal: () -> Boolean = { false },
    private val unreachable: () -> Boolean = { false },
    /** How long a queued song waits between tries while the server can't be reached. */
    private val retryMs: Long = RETRY_MS,
) {
    var queue by mutableStateOf(AudioQueue())
        private set

    var stream by mutableStateOf<PlaybackStream?>(null)
        private set

    /**
     * The music station tuned in to, if any. It plays in place of the queue, which stays as it was;
     * Stop is its only control, and playing from the library leaves it.
     */
    var station by mutableStateOf<StationTuner?>(null)
        private set

    /** One line for an alert with OK; the screen clears it. */
    var alert by mutableStateOf<String?>(null)

    private var reporter: Job? = null
    /** Whether the song playing was chosen rather than reached by the queue. */
    private var picked = false
    private var failures = 0
    /** Rises with every start, so an answer to an older one is dropped. */
    private var attempt = 0
    /** Where the song stood when its session ended under it; its next start resumes there. */
    private var resumeAtMs = 0L

    init {
        output.onFailure = { reason -> scope.launch { outputFailed(reason) } }
        output.onEnd = { scope.launch { songEnded() } }
    }

    val current: MusicSong?
        get() = station.let { if (it != null) it.onAir.now?.song else queue.current?.song }

    /** A station's Up next: every song the server lists after this one, in order, read only. */
    val stationUpNext: List<MusicSong>
        get() = station?.onAir?.upNext.orEmpty().mapNotNull { it.song }

    /** Whether there is anything for the mini player: a song, or a station off the air. */
    val showing: Boolean
        get() = current != null || station != null

    val playing: Boolean
        get() = stream != null && !output.isPaused

    val positionMs: Long
        get() = if (stream == null) 0 else output.positionMs

    /** Replaces the queue and plays from [start]. Shuffled, a song picked at random plays first. */
    suspend fun play(
        songs: List<MusicSong>,
        start: Int = 0,
        source: String,
        playlistId: Long? = null,
        shuffled: Boolean = false,
    ) {
        leaveStation()
        val next = AudioQueue.of(songs, start, source, playlistId, shuffled)
        begin(next.copy(repeatMode = queue.repeatMode), picked = true)
    }

    /** On a station this is Stop: the session ends, and Play joins again where it is then. */
    suspend fun pause() {
        station?.let {
            attempt++
            it.cancel()
            return finish()
        }
        if (!playing) return
        output.pause()
        reportPosition()
    }

    /**
     * Resumes, or after the queue ended starts its current song again. The server ends a session
     * that reports nothing for 30 minutes, as a paused one may; Play then starts the song again in
     * a new session where it stopped.
     */
    suspend fun resume() {
        if (station != null) return tuneIn()
        val session = stream?.sessionId
        if (session != null && reportPosition() && stream?.sessionId == session) {
            resumeAtMs = output.positionMs
            reporter?.cancel()
            stream = null
        }
        if (stream == null) {
            if (queue.current != null) begin(queue, picked = true, fromMs = resumeAtMs)
            return
        }
        output.play()
    }

    /**
     * A video is starting on this device, which ends the song's session on the server. The queue
     * stays; Play starts the song again in a new session where it stopped.
     */
    suspend fun yieldToVideo() {
        attempt++
        station?.cancel()
        if (stream == null) return
        resumeAtMs = output.positionMs
        finish()
    }

    suspend fun toggle() = if (playing) pause() else resume()

    fun seek(ms: Long) {
        if (stream != null && station == null) output.seek(maxOf(0, ms))
    }

    suspend fun next() {
        if (station != null) return
        val next = queue.next(auto = false) ?: return finish()
        begin(next, picked = true)
    }

    /** Past the first 3 seconds, or with nothing before, the song restarts. */
    suspend fun previous() {
        if (station != null) return
        val previous = queue.previous()
        if (output.positionMs > 3000 || previous.index == queue.index) return seek(0)
        begin(previous, picked = true)
    }

    /** Plays an Up next entry now. */
    suspend fun jump(id: Int) {
        if (station != null) return
        begin(queue.jump(id) ?: return, picked = true)
    }

    fun setShuffle(on: Boolean) {
        queue = queue.shuffle(on)
    }

    fun setRepeat(mode: RepeatMode) {
        queue = queue.copy(repeatMode = mode)
    }

    fun move(from: Int, to: Int) {
        queue = queue.move(from, to)
    }

    fun remove(id: Int) {
        queue = queue.remove(id)
    }

    fun clearUpNext() {
        queue = queue.clearUpNext()
    }

    /** Ends playback, leaves a station and empties the queue, so the mini player goes. */
    suspend fun stop() {
        attempt++
        leaveStation()
        resumeAtMs = 0
        queue = AudioQueue()
        finish()
    }

    /** The ten-second report, which keeps the profile's Recently played current. */
    suspend fun tick() {
        reportPosition()
    }

    suspend fun songEnded() {
        if (station != null) return programEnded()
        failures = 0
        endSession()
        val next = queue.next(auto = true) ?: return stop()
        begin(next, picked = false)
    }

    /**
     * The player refused the stream: the server marks the entry bad and answers with the next; with
     * none left the song cannot play.
     */
    suspend fun outputFailed(reason: String) {
        val session = stream?.sessionId ?: return
        val song = current ?: return
        if (session == LocalPlayback.SESSION) {
            finish()
            return cannotPlay(song, queue, picked)
        }
        // A stream fetched at home fails when home is left.
        api.endpoint.confirmHome(api, capabilities.facts().path, force = api.endpoint.atHome)
        val event =
            PlaybackEvent(PlaybackEventKind.client_failure, output.positionMs, reason.take(500))
        try {
            val update = api.call { playback.reportPlaybackEvent(session, event) }
            if (stream?.sessionId != session) return
            if (update.action == PlaybackUpdate.Action.restart || api.endpoint.home.moved)
                load(update.stream, song)
            else stream = update.stream
        } catch (_: Exception) {
            if (stream?.sessionId != session) return
            finish()
            cannotPlay(song, queue, picked)
        }
    }

    /** Tunes in to a music station at its live position; the queue stays as it is. */
    suspend fun tune(station: Station, tuner: StationTuner = StationTuner(api, station, scope)) {
        this.station?.cancel()
        this.station = tuner
        tuneIn()
    }

    private suspend fun tuneIn() {
        val tuner = station ?: return
        val mine = ++attempt
        val facts = capabilities.facts()
        api.endpoint.confirmHome(api, facts.path)
        val snapshot = Snapshot.snapshot(facts, api.endpoint, QualityChoice.Auto)
        val started =
            try {
                tuner.tune(snapshot, again = ::tuneIn, cue = ::programEnded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == attempt) alert = AppError.from(e, AppError.SignedOut).message
                return
            }
        if (mine != attempt) {
            started?.let { end(it.sessionId, 0) }
            return
        }
        val song = tuner.onAir.now?.song
        if (started == null || song == null) return finish()
        endSession()
        load(started, song)
    }

    /**
     * The station's song ended, by its stream or its end time, whichever comes first; off the air,
     * its next song is due. Either way the station is asked again, once.
     */
    private suspend fun programEnded() {
        val tuner = station ?: return
        if (stream == null && tuner.onAir.status == StationStatus.on_air) return
        endSession()
        tuneIn()
    }

    private fun leaveStation() {
        station?.cancel()
        station = null
    }

    /**
     * Starts the candidate's current song in a new session. The song playing now carries on until
     * the new one has started, so a chosen song that cannot play leaves it playing.
     */
    private suspend fun begin(candidate: AudioQueue, picked: Boolean, fromMs: Long = 0) {
        val song = candidate.current?.song ?: return
        val mine = ++attempt
        if (picked) failures = 0
        val copy = local(song.id)
        val started =
            if (copy != null && (preferLocal() || unreachable())) LocalPlayback.stream(copy, fromMs)
            else {
                val facts = capabilities.facts()
                api.endpoint.confirmHome(api, facts.path)
                val request =
                    PlaybackRequest(
                        profileId,
                        song.id,
                        Snapshot.snapshot(facts, api.endpoint, QualityChoice.Auto),
                        positionMs = fromMs,
                        playlistId = candidate.playlistId,
                    )
                startSession(request, copy, fromMs, mine, patient = !picked)
            }
        if (mine != attempt) {
            started?.let { end(it.sessionId, 0) }
            return
        }
        started ?: return cannotPlay(song, candidate, picked)
        endSession()
        resumeAtMs = 0
        queue = candidate
        this.picked = picked
        load(started, song)
    }

    /**
     * The song's new session, or the device's copy when the server can't be reached; null when it
     * can't start. A [patient] start, a queued song's, tries again [RETRIES] times while the server
     * can't be reached, as when it restarts at a song's end, before the song is given up.
     */
    private suspend fun startSession(
        request: PlaybackRequest,
        copy: LocalCopy?,
        fromMs: Long,
        mine: Int,
        patient: Boolean,
    ): PlaybackStream? {
        var retries = 0
        while (true) {
            try {
                return api.call { playback.startPlayback(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is IOException) return null
                if (copy != null) return LocalPlayback.stream(copy, fromMs)
                if (!patient || retries++ == RETRIES) return null
                delay(retryMs)
                if (mine != attempt) return null
            }
        }
    }

    /**
     * A chosen song alerts once. A queued one is skipped silently until [FAILURE_LIMIT] in a row
     * stop playback with one alert.
     */
    private suspend fun cannotPlay(song: MusicSong, candidate: AudioQueue, picked: Boolean) {
        if (picked) {
            alert = "“${song.title}” can’t be played"
            return
        }
        failures++
        val next = candidate.next(auto = false)
        if (failures >= FAILURE_LIMIT || next == null) {
            if (failures >= FAILURE_LIMIT) alert = "These songs can’t be played"
            failures = 0
            return finish()
        }
        begin(next, picked = false)
    }

    private fun load(started: PlaybackStream, song: MusicSong) {
        stream = started
        output.load(
            api.endpoint.resolveStream(started.streamUrl),
            started.streamKind == PlaybackStream.StreamKind.hls,
            song,
            api.endpoint.resolve("/api/v1/items/${song.albumId}/artwork/poster"),
        )
        if (started.positionMs > 0) output.seek(started.positionMs)
        output.play()
        reporter?.cancel()
        reporter = scope.launch {
            while (isActive) {
                delay(PlayerModel.REPORT_MS)
                tick()
            }
        }
    }

    private suspend fun finish() {
        endSession()
        output.stop()
    }

    private suspend fun endSession() {
        reporter?.cancel()
        val session = stream?.sessionId ?: return
        val position = output.positionMs
        stream = null
        end(session, position)
    }

    private suspend fun end(session: Long, position: Long) {
        if (session == LocalPlayback.SESSION) return
        runCatching { api.call { playback.endPlayback(session, position) } }
    }

    /** Whether the server answered that it no longer has the session. */
    private suspend fun reportPosition(): Boolean {
        val session = stream?.sessionId ?: return false
        if (session == LocalPlayback.SESSION) return false
        val report = PositionReport(output.positionMs, output.isPaused)
        val error = runCatching {
            api.call { playback.reportPlaybackPosition(session, report) }
        }
            .exceptionOrNull()
        return error?.let(ApiError::from)?.status == 404
    }

    companion object {
        /** Queued songs that fail in a row before playback stops. */
        const val FAILURE_LIMIT = 3

        /** Times a queued song is tried again, [RETRY_MS] apart, before it counts as failed. */
        const val RETRIES = 3
        const val RETRY_MS = 5_000L
    }
}
