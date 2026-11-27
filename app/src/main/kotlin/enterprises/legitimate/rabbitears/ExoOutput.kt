package enterprises.legitimate.rabbitears

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import enterprises.legitimate.rabbitears.kit.Telemetry
import enterprises.legitimate.rabbitears.kit.VideoOutput
import kotlin.math.abs

/**
 * Holds [goalMs] past the playhead, or the default buffer when it is zero, never past the bytes the
 * default sets aside: a long goal on a high bitrate holds less than it asks.
 */
@OptIn(UnstableApi::class)
class BufferGoal(
    // Time is bounded by the goal alone, so the default's own durations never stop it short.
    private val base: DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                Int.MAX_VALUE,
                Int.MAX_VALUE,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build(),
) : LoadControl {
    @Volatile var goalMs = 0L

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val goalMs = goalMs.takeIf { it > 0 } ?: DefaultLoadControl.DEFAULT_MAX_BUFFER_MS.toLong()
        return parameters.bufferedDurationUs < goalMs * 1000 &&
            base.shouldContinueLoading(parameters)
    }

    // Each named, as delegation leaves Java's default methods to the interface, which throws.
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = base.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId) = base.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId) =
        base.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId) =
        base.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters) =
        base.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ) = base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}

@OptIn(UnstableApi::class)
class ExoOutput(context: Context) : VideoOutput {
    private val buffer = BufferGoal()

    val player: ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(buffer)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

    override var onFailure: ((String) -> Unit)? = null
    override var onStall: (() -> Unit)? = null

    var onEnded: (() -> Unit)? = null

    var playing by mutableStateOf(false)
        private set

    var aspect by mutableFloatStateOf(16f / 9)
        private set

    private var stalls = 0
    // Holds only a scheduled start or pause, so clearing it cancels that.
    private val handler = Handler(Looper.getMainLooper())
    private var bandwidth = 0L
    // A load or seek buffers by design; only buffering after playback was ready is a stall.
    private var ready = false

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onFailure?.invoke("${error.errorCodeName}: ${error.message}")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> ready = true
                        Player.STATE_BUFFERING ->
                            if (ready && player.playWhenReady) {
                                ready = false
                                stalls++
                                onStall?.invoke()
                            }
                        Player.STATE_ENDED -> onEnded?.invoke()
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0)
                        aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            },
        )
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onBandwidthEstimate(
                    eventTime: AnalyticsListener.EventTime,
                    totalLoadTimeMs: Int,
                    totalBytesLoaded: Long,
                    bitrateEstimate: Long,
                ) {
                    bandwidth = bitrateEstimate
                }
            },
        )
    }

    override fun load(url: String, startMs: Long, hls: Boolean, bufferGoalMs: Long) {
        ready = false
        buffer.goalMs = bufferGoalMs
        val item =
            MediaItem.Builder()
                .setUri(url)
                .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
        if (startMs < 0) player.setMediaItem(item) else player.setMediaItem(item, startMs)
        player.prepare()
    }

    override fun play() {
        player.playWhenReady = true
    }

    override fun pause() {
        handler.removeCallbacksAndMessages(null)
        player.playWhenReady = false
    }

    /**
     * A paused player seeks now, so it has buffered by the moment it starts; at that moment, on the
     * main looper's clock, it seeks only if it is off by more than a frame or so.
     */
    override fun schedule(playing: Boolean, positionMs: Long, afterMs: Long) {
        handler.removeCallbacksAndMessages(null)
        if (playing && !player.playWhenReady) seek(positionMs)
        handler.postAtTime(
            {
                if (abs(player.currentPosition - positionMs) > 100) seek(positionMs)
                player.playWhenReady = playing
            },
            SystemClock.uptimeMillis() + afterMs,
        )
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        onEnded = null
        player.release()
    }

    override fun seek(ms: Long) {
        ready = false
        player.seekTo(maxOf(0, ms))
    }

    override val positionMs: Long
        get() = player.currentPosition

    override val isPaused: Boolean
        get() = !player.playWhenReady

    override val isBuffering: Boolean
        get() = player.playbackState == Player.STATE_BUFFERING

    val durationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0

    override fun telemetry() =
        Telemetry.sample(
            bandwidth,
            player.totalBufferedDuration,
            stalls,
            player.videoFormat?.height ?: -1,
            player.videoFormat?.bitrate ?: -1,
        )
}
