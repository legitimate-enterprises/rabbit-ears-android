package enterprises.legitimate.rabbitears

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.Me
import enterprises.legitimate.rabbitears.kit.Api
import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.DownloadStore
import enterprises.legitimate.rabbitears.kit.Downloads
import enterprises.legitimate.rabbitears.kit.HttpTransfer
import enterprises.legitimate.rabbitears.kit.LocalData
import enterprises.legitimate.rabbitears.kit.MusicPlayer
import enterprises.legitimate.rabbitears.kit.QualityChoice
import enterprises.legitimate.rabbitears.kit.ResponseCache
import enterprises.legitimate.rabbitears.kit.ServerEndpoint
import enterprises.legitimate.rabbitears.kit.Snapshot
import enterprises.legitimate.rabbitears.kit.local
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Holds the session for the life of the process, so activities and services share one, and the
 * music, which plays on after the activity is gone.
 */
class RabbitEarsApp : Application() {
    lateinit var app: AppModel
        private set

    val scope = MainScope()

    val audio by lazy { ExoAudioOutput(this, ::startPlaybackService) }

    private val capabilities by lazy { SystemCapabilities(this) }

    private val credentials by lazy { KeystoreCredentialStore(this) }

    private val downloadsDir by lazy { File(filesDir, "downloads") }
    private val responsesDir by lazy { File(cacheDir, "responses") }
    private val httpDir by lazy { File(cacheDir, "http") }

    /** This device's downloads, in the app's own files, which no backup takes. */
    val downloads: Downloads by lazy {
        Downloads(
            DownloadStore(downloadsDir, ::freeBytes),
            HttpTransfer { downloadApi()?.http ?: Api.shared },
            WorkDownloadScheduler(this) { downloads.store },
            { api ->
                Snapshot.snapshot(capabilities.facts(), api.endpoint, QualityChoice.Original)
            },
        )
    }

    /** Bytes physically free; other apps' cache is the system's to clear, not counted as room. */
    @Suppress("UsableSpace") private fun freeBytes(): Long = filesDir.usableSpace

    /** The music player of the adult browsing, if any. */
    var music by mutableStateOf<MusicPlayer?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        LocalData.migrate(
            credentials,
            listOf(downloadsDir, responsesDir, httpDir),
        )
        // The name the owner gave the phone, as the server's device list shows it.
        val name =
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        // Artwork is kept on disk as the server's cache headers allow, and the server's other
        // answers as long as they last; the system may clear either.
        app =
            AppModel(
                credentials,
                name,
                Api.cached(httpDir),
                downloads = downloads.store,
                cache = ResponseCache(responsesDir, downloads.store),
            )
    }

    /** The profile's music player, ending another profile's playback first. */
    fun music(api: Api, profileId: String): MusicPlayer {
        music?.let { if (it.api === api && it.profileId == profileId) return it }
        stopMusic()
        val store = downloads.store
        return MusicPlayer(
                api,
                profileId,
                audio,
                capabilities,
                scope,
                local = { store.local(profileId, it) },
                preferLocal = { store.settings(profileId).preferLocal },
                unreachable = { app.offline },
            )
            .also { music = it }
    }

    /** Ends playback and forgets the queue, as on sign-out or a profile switch. */
    fun stopMusic() {
        val player = music ?: return
        music = null
        scope.launch { player.stop() }
    }

    /**
     * The signed-in device for a transfer, which may run before any screen restored the session.
     */
    fun downloadApi(): Api? =
        app.api
            ?: credentials.server?.let(ServerEndpoint::parse)?.let { endpoint ->
                credentials.token?.let { Api(endpoint, it) }
            }

    /** The household as last read, for whom the server is active and who the kids are. */
    fun householdForDownloads(): Me? =
        app.me
            ?: downloads.store.state.me?.let {
                runCatching { DownloadStore.json.decodeFromString(Me.serializer(), it) }.getOrNull()
            }

    private fun startPlaybackService() {
        // Started while the app is in front; once up, the media session keeps it in the
        // foreground.
        if (!PlaybackService.running)
            runCatching { startService(Intent(this, PlaybackService::class.java)) }
    }
}
