package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Me
import enterprises.legitimate.rabbitears.api.models.WatchStatePage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The server's answers to this device's reads, kept on disk by active profile and request, which
 * every model reads through. A read is answered at once from what is kept, of any age, while the
 * server is asked behind it; an answer the server changed or refused is taken, and [onChange] is
 * told so views read again. A write the server answered has the next read of each kept answer wait
 * on the server first. What is kept stands in while the server cannot be reached or is failing,
 * ahead of the downloads' answer from only what is downloaded. [downloads] lays this device's
 * offline watch changes over a kept watch-state list.
 */
class ResponseCache(private val directory: File, private val downloads: DownloadStore? = null) {
    /** The profile the server answers as; null, as while locked, keeps and answers nothing. */
    var profile: String? = null
        get() = synchronized(this) { field }
        set(value) = synchronized(this) { field = value }

    /** An answer kept before this, in epoch milliseconds, is asked for first. */
    private var expiredBefore = Long.MIN_VALUE

    /** Keys the server is being asked for behind a read, one request each at most. */
    private val refreshing = mutableSetOf<String>()

    /**
     * Keys a refresh changed, answered once more without asking, so a read on the signal asks
     * nothing.
     */
    private val refreshed = mutableSetOf<String>()

    /** Whether the refreshes in flight have changed anything yet. */
    private var changed = false

    private var signal: () -> Unit = {}

    class Kept(val at: Long, val body: ByteArray)

    /** How a read with an answer kept goes. */
    enum class Use {
        AskFirst,
        Kept,
        KeptAndRefresh,
    }

    // Anything sent up to now, its millisecond included, may predate the write.
    fun expire() = synchronized(this) { expiredBefore = System.currentTimeMillis() + 1 }

    /** [signal] runs once the refreshes in flight are all done and any changed what was kept. */
    fun onChange(signal: () -> Unit) = synchronized(this) { this.signal = signal }

    /** Signed out: nothing kept stays, and nothing is kept until a profile is active again. */
    fun clear() =
        synchronized(this) {
            profile = null
            refreshed.clear()
            directory.deleteRecursively()
        }

    fun read(key: String): Kept? = runCatching {
        DataInputStream(file(key).inputStream()).use { Kept(it.readLong(), it.readBytes()) }
    }
        .getOrNull()

    /** [Use.KeptAndRefresh] counts the key as refreshing until [refreshed]. */
    fun use(kept: Kept, key: String): Use =
        synchronized(this) {
            when {
                kept.at < expiredBefore -> Use.AskFirst
                refreshed.remove(key) || key in refreshing -> Use.Kept
                else -> {
                    refreshing += key
                    Use.KeptAndRefresh
                }
            }
        }

    /** Ends a key's refresh; the last one in flight signals for them all. */
    fun refreshed(key: String, changed: Boolean) {
        val signal =
            synchronized(this) {
                refreshing -= key
                this.changed = this.changed || changed
                if (refreshing.isNotEmpty() || !this.changed) return
                this.changed = false
                signal
            }
        signal()
    }

    /**
     * Best effort, and only while the profile asking is still active, so an answer arriving after a
     * switch or a sign-out is dropped. True when kept; [refreshed] marks it answered once more
     * without asking. shortcut: nothing evicts a kept answer short of sign-out or the system
     * clearing the cache directory; prune by age if it grows large.
     */
    fun keep(kept: Kept, key: String, profile: String, refreshed: Boolean = false): Boolean =
        synchronized(this) {
            if (this.profile != profile) return false
            val written = runCatching {
                directory.mkdirs()
                val part = File(directory, "${file(key).name}.part")
                part.outputStream().use { out ->
                    DataOutputStream(out).apply {
                        writeLong(kept.at)
                        write(kept.body)
                    }
                }
                part.renameTo(file(key))
            }
            if (written.getOrNull() != true) return false
            if (refreshed) this.refreshed += key
            true
        }

    /** True when the profile asking is still active, so its views read again. */
    fun drop(key: String, profile: String): Boolean =
        synchronized(this) {
            if (this.profile != profile) return false
            file(key).delete()
            true
        }

    private fun file(key: String) =
        File(
            directory,
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {
                "%02x".format(it)
            },
        )

    /**
     * Reads through the cache, and notes from Me, locks and unlocks which profile the server
     * answers as. [behind] is the rest of the client, which a refresh goes through after the reader
     * has its answer.
     */
    fun interceptor(behind: OkHttpClient) = Interceptor { chain ->
        val request = chain.request()
        val segments = request.url.pathSegments.let { if (it.take(2) == API) it.drop(2) else it }
        val profile = profile
        val key = "$profile ${request.url}"
        when {
            keptOnly.get() == true ->
                (if (request.method == "GET" && profile != null) read(key) else null)?.let {
                    answer(it, request, segments)
                } ?: throw IOException("Not kept while the server is out of reach.")
            request.method != "GET" -> write(chain, segments)
            profile == null || ASKED.any { matches(it, segments) } -> note(chain, segments)
            else -> {
                val kept = read(key)
                val use = kept?.let { use(it, key) }
                if (use == Use.KeptAndRefresh) refresh(behind, request, key, profile, kept)
                if (kept != null && use != Use.AskFirst) answer(kept, request, segments)
                else ask(chain, key, profile, kept, segments)
            }
        }
    }

    /**
     * The server's answer, kept when it is a successful read; the kept one when the server is
     * unreachable or failing.
     */
    private fun ask(
        chain: Interceptor.Chain,
        key: String,
        profile: String,
        kept: Kept?,
        segments: List<String>,
    ): Response {
        val request = chain.request()
        val sent = System.currentTimeMillis()
        val response =
            try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (kept != null) return answer(kept, request, segments)
                throw e
            }
        if (kept != null && response.code >= 500) {
            response.close()
            return answer(kept, request, segments)
        }
        if (!keeps(response)) {
            // An answer now refused, as a title taken from a kid, is never answered again.
            if (kept != null) drop(key, profile)
            return response
        }
        val type = response.body.contentType()
        val body = response.body.bytes()
        keep(Kept(sent, body), key, profile)
        return response.newBuilder().body(body.toResponseBody(type)).build()
    }

    /**
     * Takes a changed answer, or drops the kept one for anything else the server answers; either
     * has views read again. A server that is unreachable or failing leaves what is kept.
     */
    private fun refresh(
        behind: OkHttpClient,
        request: Request,
        key: String,
        profile: String,
        kept: Kept,
    ) {
        val sent = System.currentTimeMillis()
        behind
            .newCall(request)
            .enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) = refreshed(key, false)

                    override fun onResponse(call: Call, response: Response) {
                        var changed = false
                        try {
                            response.use {
                                changed =
                                    when {
                                        it.code >= 500 -> false
                                        !keeps(it) -> drop(key, profile)
                                        else -> {
                                            val body = it.body.bytes()
                                            !body.contentEquals(kept.body) &&
                                                keep(Kept(sent, body), key, profile, true)
                                        }
                                    }
                            }
                        } catch (_: IOException) {} finally {
                            refreshed(key, changed)
                        }
                    }
                },
            )
    }

    /** Me names the profile the server answers as, or none while locked. */
    private fun note(chain: Interceptor.Chain, segments: List<String>): Response {
        val response = chain.proceed(chain.request())
        if (segments != listOf("me") || response.code != 200) return response
        val type = response.body.contentType()
        val body = response.body.string()
        val me = runCatching {
            Serializer.kotlinxSerializationJson.decodeFromString(Me.serializer(), body)
        }
            .getOrNull()
        profile = me?.takeIf { it.locked != true }?.activeProfileId
        return response.newBuilder().body(body.toResponseBody(type)).build()
    }

    /**
     * A write the server answered, with anything, has the next read of everything kept ask first; a
     * lock or unlock keeps nothing until it says which profile is active.
     */
    private fun write(chain: Interceptor.Chain, segments: List<String>): Response {
        val method = chain.request().method
        val marks = UNMARKING.none { it.first == method && matches(it.second, segments) }
        val unlock = method == "POST" && matches("profiles/*/unlock", segments)
        if (unlock || method == "POST" && segments == listOf("me", "lock")) profile = null
        val response = chain.proceed(chain.request())
        if (marks) expire()
        if (unlock && response.isSuccessful) profile = segments[1]
        return response
    }

    private fun answer(
        kept: Kept,
        request: Request,
        segments: List<String>,
    ): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", JSON.toString())
            .body(withUnsynced(kept.body, request, segments).toResponseBody(JSON))
            .build()

    /**
     * A watch-state list the server last answered, with the profile's changes it has not had yet.
     */
    private fun withUnsynced(
        body: ByteArray,
        request: Request,
        segments: List<String>,
    ): ByteArray {
        val profileId = segments.getOrNull(1)
        if (downloads == null || profileId == null || !matches("profiles/*/watch-state", segments))
            return body
        val mine = downloads.unsynced(profileId).map { it.asWatchState() }
        if (mine.isEmpty()) return body
        val json = Serializer.kotlinxSerializationJson
        val page =
            runCatching {
                json.decodeFromString(WatchStatePage.serializer(), body.decodeToString())
            }
                .getOrNull() ?: return body
        val ids = mine.map { it.itemId }.toSet()
        val first = (request.url.queryParameter("offset")?.toIntOrNull() ?: 0) == 0
        val items = page.items.filter { it.itemId !in ids } + if (first) mine else emptyList()
        return json
            .encodeToString(WatchStatePage.serializer(), page.copy(items = items))
            .toByteArray()
    }

    companion object {
        /**
         * Set while the app already knows the server is out of reach: a read is answered only from
         * what is kept, and anything else fails at once without the network.
         */
        val keptOnly = ThreadLocal<Boolean>()

        private val API = listOf("api", "v2")
        private val JSON = "application/json".toMediaType()

        /**
         * Reads never kept: what something waits on to change or what changes with the time, Me,
         * which names the active profile, a link's token, and the watch state and up next the
         * downloads answer offline from this device's own plays.
         */
        private val ASKED =
            listOf(
                "me",
                "events",
                "profiles/*/watch-state/*",
                "profiles/*/up-next/*",
                "playback/sessions",
                "playback/sessions/*",
                "watch-together",
                "watch-together/*",
                "stations",
                "stations/*",
                "stations/*/schedule",
                "profiles/*/downloads",
                "profiles/*/downloads/*",
                "me/deletion",
                "device-links/*",
            )

        /**
         * Writes that leave every kept answer as it was: the player's periodic reports, whose
         * effect on watch state comes as a live event, and the questions asked by POST.
         */
        private val UNMARKING =
            listOf(
                "PUT" to "playback/sessions/*/position",
                "POST" to "playback/sessions/*/events",
                "PUT" to "playback/sessions/*/throughput",
                "PUT" to "playback/sessions/*/network",
                "PUT" to "playback/sessions/*/display",
                "PUT" to "playback/sessions/*/audio-route",
                "POST" to "items/*/playability",
                "POST" to "profiles/*/downloads/resolve",
                "POST" to "local-checks",
            )

        /** Whether [segments] are the path [template], where `*` stands for any one segment. */
        private fun matches(template: String, segments: List<String>): Boolean {
            val parts = template.split('/')
            return parts.size == segments.size &&
                parts.zip(segments).all { (part, segment) -> part == "*" || part == segment }
        }

        private fun keeps(response: Response) =
            response.code == 200 && response.body.contentType()?.subtype == "json"
    }
}
