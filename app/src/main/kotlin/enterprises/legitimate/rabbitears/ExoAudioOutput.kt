package enterprises.legitimate.rabbitears

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import enterprises.legitimate.rabbitears.api.models.MusicSong
import enterprises.legitimate.rabbitears.kit.AudioOutput

/**
 * The music's ExoPlayer, one per process, which the media session in [PlaybackService] publishes to
 * the notification and the lock screen. It holds only the song playing; the queue is the music
 * player's. [onPlay] runs before each play so the service is up to keep it going in the background.
 */
@OptIn(UnstableApi::class)
class ExoAudioOutput(context: Context, private val onPlay: () -> Unit) : AudioOutput {
    val player: ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

    override var onFailure: ((String) -> Unit)? = null
    override var onEnd: (() -> Unit)? = null

    override var isPaused by mutableStateOf(true)
        private set

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    isPaused = !playWhenReady
                }

                override fun onPlayerError(error: PlaybackException) {
                    onFailure?.invoke("${error.errorCodeName}: ${error.message}")
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onEnd?.invoke()
                }
            },
        )
    }

    override fun load(url: String, hls: Boolean, song: MusicSong, artUrl: String) {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artistName)
                .setAlbumTitle(song.albumTitle)
                .setArtworkUri(artUrl.toUri())
                .setDurationMs(song.durationMs)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        val item =
            MediaItem.Builder()
                .setUri(url)
                .setMediaId(song.id)
                .setMediaMetadata(metadata)
                .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
        player.setMediaItem(item)
        player.prepare()
    }

    override fun play() {
        onPlay()
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun seek(ms: Long) = player.seekTo(maxOf(0, ms))

    override fun stop() {
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    override val positionMs: Long
        get() = player.currentPosition

    val durationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
}
