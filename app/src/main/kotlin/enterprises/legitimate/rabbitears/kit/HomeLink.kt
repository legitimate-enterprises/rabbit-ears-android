package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.apis.PlaybackApi
import enterprises.legitimate.rabbitears.api.models.LocalCheckProof
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * What the last local check found for a server signed in to at a public address: the address that
 * reaches it on its own network, where this device then is too.
 */
class HomeLink {
    /** The server's base URL on its own network; null when the last check found none. */
    @Volatile
    var localBase: String? = null
        private set

    private var checked: Pair<Long, PathFacts>? = null
    /** Where the stream now playing was resolved; a device plays one at a time. */
    private var streaming: String? = null

    /**
     * Whether the last check found another address than the stream now playing was resolved at,
     * which then has to be loaded again.
     */
    val moved: Boolean
        @Synchronized get() = streaming != localBase

    /** The address a stream is fetched at, remembered for [moved]. */
    @Synchronized fun stream(): String? = localBase.also { streaming = it }

    @Synchronized
    fun stands(path: PathFacts, nowMs: Long) =
        checked?.let { (at, on) -> on == path && nowMs - at < STANDING_MS } ?: false

    /**
     * Opens a check through [api], then confirms it at every address the server names at once. The
     * first whose reply proves the key becomes [localBase]; with none, or on any error, there is
     * none. A cancelled check finds nothing out.
     */
    suspend fun refresh(api: Api, path: PathFacts, nowMs: Long) {
        val found = confirmed(api)
        synchronized(this) {
            localBase = found
            checked = nowMs to path
        }
    }

    private suspend fun confirmed(api: Api): String? {
        val check =
            try {
                api.call { playback.startLocalCheck() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        val key = runCatching { Base64.getDecoder().decode(check.key) }.getOrNull() ?: return null
        val addresses = check.addresses.mapNotNull { ServerEndpoint.parse(it.toString()) }
        val quick = api.base.newBuilder().callTimeout(PATIENCE_MS, TimeUnit.MILLISECONDS).build()
        val answers = Channel<String?>(Channel.UNLIMITED)
        // Not the caller's children: the first proof returns at once, not after the slowest.
        addresses.forEach { at ->
            CoroutineScope(Dispatchers.IO).launch {
                answers.send(runCatching { confirm(check.id, at, key, quick) }.getOrNull())
            }
        }
        return withTimeoutOrNull(PATIENCE_MS) {
            repeat(addresses.size) {
                answers.receive()?.let {
                    return@withTimeoutOrNull it
                }
            }
            null
        }
    }

    /**
     * The address, once its reply proves it holds the key as well: at a private address anything
     * may answer. No token is sent.
     */
    private fun confirm(
        id: String,
        at: ServerEndpoint,
        key: ByteArray,
        http: OkHttpClient,
    ): String? {
        val nonce = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "")
        val reply =
            PlaybackApi(at.api, http)
                .confirmLocalCheck(id, LocalCheckProof(nonce, proof(key, "client:$nonce")))
        val proven =
            MessageDigest.isEqual(
                proof(key, "server:$nonce").toByteArray(),
                reply.proof.lowercase().toByteArray(),
            )
        return at.base.takeIf { proven }
    }

    private fun proof(key: ByteArray, text: String) =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** How long the addresses have to answer; an unreachable private one never does. */
        const val PATIENCE_MS = 2_000L
        /** How long a check stands on its path; the server forgets one after 15 minutes. */
        const val STANDING_MS = 600_000L
    }
}
