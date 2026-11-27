package enterprises.legitimate.rabbitears

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import enterprises.legitimate.rabbitears.kit.MusicPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps music playing with the app in the background and gives the system its notification and lock
 * screen controls. Previous, next, play, pause and stop go to the music player, which owns the
 * queue and the server's sessions; seeking goes straight to ExoPlayer. On a music station the
 * system offers Stop alone.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var watch: Job? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val app = application as RabbitEarsApp
        val music = { block: suspend MusicPlayer.() -> Unit ->
            app.music?.let { app.scope.launch { it.block() } }
            Unit
        }
        val player =
            object : ForwardingPlayer(app.audio.player) {
                override fun getAvailableCommands(): Player.Commands =
                    super.getAvailableCommands()
                        .buildUpon()
                        .addAll(
                            COMMAND_SEEK_TO_NEXT,
                            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                            COMMAND_SEEK_TO_PREVIOUS,
                            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        )
                        .build()

                override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

                override fun play() = music { resume() }

                override fun pause() = music { pause() }

                override fun seekToNext() = music { next() }

                override fun seekToNextMediaItem() = music { next() }

                override fun seekToPrevious() = music { previous() }

                override fun seekToPreviousMediaItem() = music { previous() }

                // Stop ends the song or station, empties the queue and takes the notification away.
                override fun stop() = music { stop() }
            }
        // Album art is behind the device token, which changes with each sign-in.
        val art = DataSource.Factory {
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(
                    app.app.api?.token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
                )
                .createDataSource()
        }
        session =
            MediaSession.Builder(this, player)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setCallback(
                    object : MediaSession.Callback {
                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): MediaSession.ConnectionResult {
                            val station = app.music?.station != null
                            if (!station || !session.isMediaNotificationController(controller))
                                return super.onConnect(session, controller)
                            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                                .setAvailablePlayerCommands(STATION_COMMANDS)
                                .setMediaButtonPreferences(listOf(STOP))
                                .build()
                        }
                    },
                )
                .setBitmapLoader(
                    CacheBitmapLoader(
                        DataSourceBitmapLoader.Builder(this).setDataSourceFactory(art).build(),
                    ),
                )
                .build()
        watch = app.scope.launch { snapshotFlow { app.music?.station != null }.collect(::offer) }
    }

    /**
     * The notification controller's commands are what the notification, the lock screen and other
     * system controls offer, so tuning in or out of a station swaps them.
     */
    private fun offer(station: Boolean) {
        val session = session ?: return
        session.mediaNotificationControllerInfo?.let {
            session.setAvailableCommands(
                it,
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                if (station) STATION_COMMANDS
                else MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        }
        session.setMediaButtonPreferences(if (station) listOf(STOP) else emptyList())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        running = false
        watch?.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private val STATION_COMMANDS =
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_STOP,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                )
                .build()

        private val STOP =
            CommandButton.Builder(CommandButton.ICON_STOP)
                .setPlayerCommand(Player.COMMAND_STOP)
                .setDisplayName("Stop")
                .build()

        /** Whether the service is up; it is started once, from the foreground. */
        var running = false
            private set
    }
}
