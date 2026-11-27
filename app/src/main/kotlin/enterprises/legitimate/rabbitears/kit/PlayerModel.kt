package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.CapabilitySnapshot
import enterprises.legitimate.rabbitears.api.models.HdrFormat
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.PlaybackEvent
import enterprises.legitimate.rabbitears.api.models.PlaybackEventKind
import enterprises.legitimate.rabbitears.api.models.PlaybackRequest
import enterprises.legitimate.rabbitears.api.models.PlaybackStream
import enterprises.legitimate.rabbitears.api.models.PlaybackUpdate
import enterprises.legitimate.rabbitears.api.models.PositionReport
import enterprises.legitimate.rabbitears.api.models.StationStatus
import enterprises.legitimate.rabbitears.api.models.ThroughputTelemetry
import enterprises.legitimate.rabbitears.api.models.TrackSelection
import enterprises.legitimate.rabbitears.api.models.Verdict
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What plays the stream: ExoPlayer in the app, a recorder in tests. Called on the main thread. */
interface VideoOutput {
    /**
     * A negative [startMs] joins a live stream at its live edge. [bufferGoalMs] is how far past the
     * playhead to hold; zero leaves the output its own buffer.
     */
    fun load(url: String, startMs: Long, hls: Boolean, bufferGoalMs: Long)

    fun play()

    fun pause()

    fun stop()

    fun seek(ms: Long)

    /** In afterMs, plays or pauses at positionMs, as precisely as the output can. */
    fun schedule(playing: Boolean, positionMs: Long, afterMs: Long)

    val positionMs: Long
    val isPaused: Boolean
    val isBuffering: Boolean

    fun telemetry(): ThroughputTelemetry

    var onFailure: ((String) -> Unit)?
    var onStall: (() -> Unit)?
}

/** The variant the player is on, which in Auto the player picks itself. */
data class RungInUse(val height: Int, val bitrate: Long)

data class QualityOption(
    val choice: QualityChoice,
    val name: String,
    val detail: String,
    val verdict: Verdict,
    val selected: Boolean,
    /** Listed but not playable on this network path. */
    val blocked: Boolean,
) {
    val trailing: String
        get() = if (blocked) "Wi-Fi only" else Format.verdict(verdict)
}

sealed interface SubtitleChoice {
    data object Off : SubtitleChoice

    data object ForcedOnly : SubtitleChoice

    data class Track(val id: String) : SubtitleChoice
}

data class SubtitleOption(val choice: SubtitleChoice, val name: String, val detail: String? = null)

/** The quality pill: tags such as `4K` and `HDR`, the verdict or Auto, and the bitrate. */
data class PillText(val tags: List<String>, val label: String, val rate: String)

object Telemetry {
    /**
     * Group E from what the player reports. The rung is named only when both its height and its
     * declared bitrate are known: a plain file has no variants.
     */
    fun sample(
        bandwidth: Long,
        bufferMs: Long,
        stalls: Int,
        height: Int,
        bitrate: Int,
    ): ThroughputTelemetry {
        val named = height >= 1 && bitrate >= 1
        return ThroughputTelemetry(
            measuredBitrate = maxOf(0, bandwidth),
            bufferMs = maxOf(0, bufferMs),
            stallCount = stalls,
            rungHeight = height.takeIf { named },
            rungBitrate = bitrate.toLong().takeIf { named },
        )
    }
}

/**
 * One playback session: starts it with the full snapshot, or tunes in to [station] at its live
 * position with no pause or seek, sends a single group when this device changes, walks the plan on
 * failure, and reports position and throughput. The server decides everything else. Runs on
 * [scope], which is the screen's.
 */
class PlayerModel(
    private val api: Api,
    private val profileId: String,
    val itemId: String,
    val output: VideoOutput,
    private val capabilities: CapabilitySource,
    private val scope: CoroutineScope,
    private val playlistId: String? = null,
    private val editionId: String? = null,
    private val station: StationTuner? = null,
    /** The title on the device, played in place of a session when [preferLocal] or offline. */
    private val local: LocalCopy? = null,
    private val preferLocal: Boolean = false,
    private val offline: OfflineLibrary? = null,
) {
    var stream by mutableStateOf<PlaybackStream?>(null)
        private set

    var adjusting by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var subtitleChoice by mutableStateOf<SubtitleChoice>(SubtitleChoice.ForcedOnly)
        private set

    var quality by mutableStateOf<QualityChoice>(QualityChoice.Auto)
        private set

    var rungInUse by mutableStateOf<RungInUse?>(null)
        private set

    /** The tracks and chapters of the file in play, for the menus and the scrub card. */
    var media by mutableStateOf<MediaSummary?>(null)
        private set

    private var cues by mutableStateOf(emptyList<SubtitleCue>())
    private var audioTrackId: String? = null
    private var sent: CapabilitySnapshot? = null
    private var reporter: Job? = null
    private var stopped = false
    /** Rises with every start, so an answer to an older one is dropped. */
    private var attempt = 0

    init {
        output.onFailure = { reason -> scope.launch { playerFailed(reason) } }
        output.onStall = { scope.launch { stalled() } }
        capabilities.onChange = { group -> scope.launch { capabilitiesChanged(group) } }
    }

    val pill: PillText?
        get() {
            val stream = stream ?: return null
            if (isLocal) return PillText(emptyList(), "Downloaded", "")
            if (stream.auto == true) {
                val rung = currentRung(stream)
                return PillText(
                    listOf(Format.resolution(rung.height * 16 / 9, rung.height)),
                    "Auto",
                    Format.bitrate(rung.bitrate),
                )
            }
            val n = stream.negotiated
            return PillText(
                listOfNotNull(
                    Format.resolution(n.width, n.height),
                    "HDR".takeIf { n.hdrFormat != null },
                ),
                Format.verdict(n.verdict),
                Format.bitrate(n.bitrate),
            )
        }

    /** Away from home Auto comes first; at home there is none, so unpicked reads as Original. */
    val qualityOptions: List<QualityOption>
        get() {
            val stream = stream ?: return emptyList()
            val chosen = effectiveQuality(stream)
            val options = mutableListOf<QualityOption>()
            if (stream.away == true) {
                val now = currentRung(stream).height
                options +=
                    QualityOption(
                        QualityChoice.Auto,
                        "Auto",
                        "Now ${Format.resolution(now * 16 / 9, now)} · adjusts to your connection",
                        Verdict.video_transcode,
                        chosen == QualityChoice.Auto,
                        blocked = false,
                    )
            }
            stream.rungs.forEachIndexed { index, rung ->
                val choice =
                    if (index == 0) QualityChoice.Original else QualityChoice.Rung(rung.bitrate)
                val label = Format.resolution(rung.width, rung.height)
                val rate = Format.bitrate(rung.bitrate)
                options +=
                    QualityOption(
                        choice,
                        if (index == 0) "Original" else label,
                        if (index == 0) media?.videoLine ?: "$label · $rate"
                        else "${ladderCodec()} · $rate",
                        rung.verdict,
                        chosen == choice,
                        rung.blocked == true,
                    )
            }
            return options
        }

    val subtitleOptions: List<SubtitleOption>
        get() =
            listOf(
                SubtitleOption(SubtitleChoice.Off, "Off"),
                SubtitleOption(
                    SubtitleChoice.ForcedOnly,
                    "Only for non-English",
                    "Shows the forced track when another language is spoken",
                ),
            ) +
                media?.subtitles.orEmpty().map {
                    SubtitleOption(
                        SubtitleChoice.Track(it.id),
                        media?.label(it).orEmpty(),
                        "Includes sound cues"
                            .takeIf { _ -> it.title?.contains("SDH", true) == true },
                    )
                }

    fun cue(ms: Long): String? = if (subtitleChoice == SubtitleChoice.Off) null else cues.at(ms)

    /** The preview frame nearest [ms], as a server path; null when the file has none. */
    fun previewPath(ms: Long): String? {
        val previews = stream?.previews ?: return null
        if (previews.count < 1 || previews.intervalMs < 1) return null
        val index =
            (ms.toDouble() / previews.intervalMs).roundToLong().coerceIn(0, previews.count - 1L)
        return previews.frameUrl.replace("{n}", index.toString())
    }

    /** The chapter at [ms] by title, or by number when it has none. */
    fun chapter(ms: Long): String? {
        val chapters = media?.file?.chapters.orEmpty()
        val index = chapters.indexOfFirst { it.startMs <= ms && ms < it.endMs }
        return if (index < 0) null else chapters[index].title ?: "Chapter ${index + 1}"
    }

    suspend fun start(startMs: Long, audioTrackId: String? = null) {
        this.audioTrackId = audioTrackId
        error = null
        if (station == null && local != null && (preferLocal || offline?.isUnreachable == true))
            return playLocal(startMs)
        val facts = capabilities.facts()
        api.endpoint.confirmHome(api, facts.path)
        val snapshot = Snapshot.snapshot(facts, api.endpoint, quality)
        sent = snapshot
        val mine = ++attempt
        val started =
            try {
                if (station != null)
                    station.tune(
                        snapshot,
                        audioTrackId,
                        subtitleTrackId(),
                        live = true,
                        again = { if (mine == attempt) start(0, audioTrackId) },
                    ) {
                        // A wait set by an answer since overtaken cues nothing.
                        if (mine == attempt) programEnded()
                    } ?: return
                else
                    api.call {
                        playback.startPlayback(
                            PlaybackRequest(
                                profileId,
                                itemId,
                                snapshot,
                                editionId = editionId,
                                audioTrackId = audioTrackId,
                                positionMs = startMs,
                                playlistId = playlistId,
                            ),
                        )
                    }
            } catch (e: Exception) {
                if (!stopped && mine == attempt) {
                    if (station == null && local != null && e is IOException)
                        return playLocal(startMs)
                    fail(e)
                }
                return
            }
        // Closed, or asked again, while the server was starting it: end it, never play it.
        if (stopped || mine != attempt) {
            runCatching { api.call { playback.endPlayback(started.sessionId, startMs) } }
            return
        }
        load(started)
        catchUp(facts)
        if (stopped) return
        reporter?.cancel()
        reporter = scope.launch {
            while (isActive) {
                delay(REPORT_MS)
                tick()
            }
        }
    }

    suspend fun capabilitiesChanged(group: CapabilityGroup) {
        if (isLocal) return
        val session = stream?.sessionId ?: return
        // A station is asked again, which decides for the device as it is now at the live position.
        if (station != null) return start(0, audioTrackId)
        val facts = capabilities.facts()
        if (group == CapabilityGroup.Network) api.endpoint.confirmHome(api, facts.path)
        update {
            when (group) {
                CapabilityGroup.Display ->
                    playback.updatePlaybackDisplay(session, Snapshot.display(facts.screen))
                CapabilityGroup.AudioRoute ->
                    playback.updatePlaybackAudioRoute(session, Snapshot.audioRoute(facts.outputs))
                CapabilityGroup.Network ->
                    playback.updatePlaybackNetwork(
                        session,
                        Snapshot.network(facts.path, api.endpoint, quality),
                    )
            }
        }
    }

    suspend fun selectQuality(option: QualityOption) {
        val stream = stream ?: return
        if (option.blocked || option.choice == effectiveQuality(stream)) return
        quality = option.choice
        capabilitiesChanged(CapabilityGroup.Network)
    }

    suspend fun selectSubtitles(choice: SubtitleChoice) {
        subtitleChoice = choice
        if (isLocal) return loadCues()
        val session = stream?.sessionId ?: return
        val selection = TrackSelection(audioTrackId, (choice as? SubtitleChoice.Track)?.id)
        update { playback.selectPlaybackTracks(session, selection) }
        loadCues()
    }

    /** The player refused the stream: the entry is marked bad and the next one plays. */
    suspend fun playerFailed(reason: String) {
        if (isLocal) {
            error = "This download can't be played."
            return
        }
        val session = stream?.sessionId ?: return
        // A stream fetched at home fails when home is left.
        api.endpoint.confirmHome(api, capabilities.facts().path, force = api.endpoint.atHome)
        val event =
            PlaybackEvent(PlaybackEventKind.client_failure, output.positionMs, reason.take(500))
        update { playback.reportPlaybackEvent(session, event) }
    }

    suspend fun stalled() {
        if (isLocal) return
        val session = stream?.sessionId ?: return
        val event = PlaybackEvent(PlaybackEventKind.stall, output.positionMs)
        update(telemetry = true) { playback.reportPlaybackEvent(session, event) }
    }

    /** The ten-second report: throughput telemetry, which carries the position. */
    suspend fun tick() {
        if (isLocal) return recordLocal(output.positionMs)
        val session = stream?.sessionId ?: return
        val telemetry = output.telemetry().copy(positionMs = output.positionMs)
        rungInUse =
            telemetry.rungHeight?.let { height ->
                telemetry.rungBitrate?.let { RungInUse(height, it) }
            }
        update(telemetry = true, positioned = true) {
            playback.reportPlaybackThroughput(session, telemetry)
        }
    }

    suspend fun reportPosition() {
        if (isLocal) return recordLocal(output.positionMs)
        val session = stream?.sessionId ?: return
        val report = PositionReport(output.positionMs, output.isPaused)
        runCatching { api.call { playback.reportPlaybackPosition(session, report) } }
    }

    /**
     * A station's program ended, by its stream running out or its end time passing, whichever comes
     * first; off the air, its next program is due. Either way the station is asked again, once. A
     * live stream ends with its last program, which a player reaches a little ahead of the
     * schedule, so the ask waits for that program's end.
     */
    suspend fun programEnded() {
        if (stopped || station == null) return
        if (stream?.live == true) {
            val mine = attempt
            station.programOver()
            if (stopped || mine != attempt) return
        }
        val session = stream?.sessionId
        if (session == null && station.onAir.status == StationStatus.on_air) return
        reporter?.cancel()
        stream = null
        // Read here: the player answers only on the main thread, and the call runs off it.
        val position = output.positionMs
        session?.let { runCatching { api.call { playback.endPlayback(it, position) } } }
        // A picked track is the ended file's; sent on, it would name nothing and turn the next
        // program's forced track off.
        if (subtitleChoice is SubtitleChoice.Track) subtitleChoice = SubtitleChoice.ForcedOnly
        start(0, audioTrackId)
    }

    /**
     * A station's screen is out of sight: its session ends, and nothing is asked or played until
     * [start] tunes in again.
     */
    suspend fun standBy() {
        if (stopped) return
        attempt++
        reporter?.cancel()
        station?.cancel()
        output.pause()
        val position = output.positionMs
        val session = stream?.sessionId ?: return
        stream = null
        runCatching { api.call { playback.endPlayback(session, position) } }
    }

    /** Another device took the session: the server ended it, so nothing is left to end. */
    fun taken() {
        stopped = true
        reporter?.cancel()
        output.stop()
        stream = null
    }

    /**
     * Ends the session with where the player stopped, which the server keeps as watch state; true
     * when the device's copy played instead, whose progress only the device has heard of.
     */
    suspend fun stop(): Boolean {
        if (stopped) return false
        stopped = true
        reporter?.cancel()
        station?.cancel()
        val position = output.positionMs
        output.stop()
        if (isLocal) {
            recordLocal(position, ended = true)
            stream = null
            return true
        }
        val session = stream?.sessionId ?: return false
        stream = null
        runCatching { api.call { playback.endPlayback(session, position) } }
        return false
    }

    /**
     * Sends one mid-playback message after recording where the player is, unless the message
     * carries it, so a restart resumes at this exact position, paused if the player was. Telemetry
     * neither shows as adjusting nor surfaces its failures; playback carries on.
     */
    private suspend fun update(
        telemetry: Boolean = false,
        positioned: Boolean = false,
        send: Api.() -> PlaybackUpdate,
    ) {
        if (isLocal) return
        val session = stream?.sessionId
        if (!telemetry) adjusting = true
        try {
            if (!positioned) reportPosition()
            val update = api.call(send)
            // An answer that lands after close, or after its session was left, must not bring
            // the player back.
            if (stopped || stream?.sessionId != session) return
            if (update.action == PlaybackUpdate.Action.restart || api.endpoint.home.moved)
                load(update.stream, play = !output.isPaused)
            else stream = update.stream
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!telemetry) fail(e)
        } finally {
            if (!telemetry) adjusting = false
        }
    }

    /** Sends each group that changed while the session was starting, which had nowhere to go. */
    private suspend fun catchUp(sent: DeviceFacts) {
        val now = capabilities.facts()
        if (now.screen != sent.screen) capabilitiesChanged(CapabilityGroup.Display)
        if (now.outputs != sent.outputs) capabilitiesChanged(CapabilityGroup.AudioRoute)
        if (now.path != sent.path) capabilitiesChanged(CapabilityGroup.Network)
    }

    private suspend fun load(stream: PlaybackStream, play: Boolean = true) {
        this.stream = stream
        rungInUse = null
        output.load(
            api.endpoint.resolveStream(stream.streamUrl),
            if (stream.live == true) -1 else stream.positionMs,
            stream.streamKind == PlaybackStream.StreamKind.hls,
            (stream.bufferGoalSeconds ?: 0) * 1000L,
        )
        if (play) output.play()
        loadCues()
        loadMedia(stream.mediaFileId)
    }

    /** A station's next program is another file; a session keeps the one it started on. */
    private suspend fun loadMedia(fileId: String) {
        if (media?.file?.id == fileId) return
        media = null
        val file = runCatching { api.call { media.getMediaFile(fileId) } }.getOrNull() ?: return
        if (stream?.mediaFileId == fileId) media = MediaSummary(file)
    }

    private suspend fun loadCues() {
        cues = emptyList()
        val stream = stream ?: return
        val wanted =
            when (val choice = subtitleChoice) {
                SubtitleChoice.Off -> return
                SubtitleChoice.ForcedOnly ->
                    stream.sidecars.firstOrNull { it.forced } ?: stream.sidecars.firstOrNull()
                is SubtitleChoice.Track -> stream.sidecars.firstOrNull { it.trackId == choice.id }
            } ?: return
        val text = api.call { LocalPlayback.text(this, wanted.url) } ?: return
        cues = SubtitleCue.parse(text)
    }

    private fun subtitleTrackId() = (subtitleChoice as? SubtitleChoice.Track)?.id

    private fun effectiveQuality(stream: PlaybackStream): QualityChoice =
        if (quality == QualityChoice.Auto && stream.away != true) QualityChoice.Original
        else quality

    /** Until the player reports a variant, the negotiated rung stands in. */
    private fun currentRung(stream: PlaybackStream): RungInUse =
        rungInUse ?: RungInUse(stream.negotiated.height, stream.negotiated.bitrate)

    /**
     * The server's conversion codec: HEVC keeps a PQ source's HDR when this device decodes 10-bit
     * HEVC with HDR10 on an HDR10 screen, else H.264.
     */
    private fun ladderCodec(): String {
        val source = media?.video?.hdrFormat
        val pq =
            source == HdrFormat.hdr10 ||
                source == HdrFormat.hdr10plus ||
                source == HdrFormat.dolby_vision
        val snapshot = sent ?: return "H.264"
        val hevc =
            snapshot.decoder.videoCodecs.any {
                it.codec == "hevc" &&
                    it.maxBitDepth >= 10 &&
                    HdrFormat.hdr10 in it.hdrFormats.orEmpty()
            }
        return if (pq && hevc && HdrFormat.hdr10 in snapshot.display.hdrFormats) "HEVC" else "H.264"
    }

    val isLocal: Boolean
        get() = LocalPlayback.isLocal(stream)

    /** Plays the device's copy; its progress is kept on the device with when it happened. */
    private suspend fun playLocal(startMs: Long) {
        val copy = local ?: return
        error = null
        val stream = LocalPlayback.stream(copy, startMs)
        this.stream = stream
        output.load(stream.streamUrl, startMs, hls = false, bufferGoalMs = 0)
        output.play()
        loadCues()
        reporter?.cancel()
        reporter = scope.launch {
            while (isActive) {
                delay(REPORT_MS)
                recordLocal(output.positionMs)
            }
        }
    }

    /**
     * Keeps the position, and whether it counts as watched, on the device. Only once playback has
     * [ended] may that remove the title's file, which must not happen under the player or before Up
     * next is resolved.
     */
    private fun recordLocal(positionMs: Long, ended: Boolean = false) {
        val copy = local ?: return
        val watched = LocalPlayback.watched(positionMs, copy.runtimeMs)
        offline?.record(itemId, positionMs, watched, copy.editionId, ended)
    }

    private fun fail(e: Exception) {
        error =
            if (ApiError.from(e)?.status == 409) "This title can't play on this device."
            else AppError.from(e, AppError.SignedOut).message
    }

    companion object {
        const val REPORT_MS = 10_000L
    }
}

/** The episode after [id] in its show's viewing order; null for a movie or the last episode. */
fun Catalog.nextEpisode(id: String): Item? {
    val item = item(id)?.takeIf { it.kind == ItemKind.episode } ?: return null
    val episodes = show(item)?.let { episodes(it.id) } ?: return null
    val index = episodes.indexOfFirst { it.id == id }
    return if (index < 0) null else episodes.getOrNull(index + 1)
}
