package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.apis.AuthApi
import enterprises.legitimate.rabbitears.api.apis.CollectionsApi
import enterprises.legitimate.rabbitears.api.apis.DownloadsApi
import enterprises.legitimate.rabbitears.api.apis.EventsApi
import enterprises.legitimate.rabbitears.api.apis.ItemsApi
import enterprises.legitimate.rabbitears.api.apis.LibrariesApi
import enterprises.legitimate.rabbitears.api.apis.MediaApi
import enterprises.legitimate.rabbitears.api.apis.MusicApi
import enterprises.legitimate.rabbitears.api.apis.PeopleApi
import enterprises.legitimate.rabbitears.api.apis.PlaybackApi
import enterprises.legitimate.rabbitears.api.apis.PlaylistsApi
import enterprises.legitimate.rabbitears.api.apis.ProfilesApi
import enterprises.legitimate.rabbitears.api.apis.StationsApi
import enterprises.legitimate.rabbitears.api.apis.SystemApi
import enterprises.legitimate.rabbitears.api.apis.TogetherApi
import enterprises.legitimate.rabbitears.api.apis.YoutubeApi
import enterprises.legitimate.rabbitears.api.infrastructure.ClientError
import enterprises.legitimate.rabbitears.api.infrastructure.ClientException
import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.infrastructure.ServerError
import enterprises.legitimate.rabbitears.api.infrastructure.ServerException
import enterprises.legitimate.rabbitears.api.models.Error
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The generated client for one server, signed in as the device when [token] is given. Calls block,
 * so run them through [call], which reports device_locked to [onLocked] before rethrowing it.
 */
class Api(
    val endpoint: ServerEndpoint,
    val token: String?,
    /** Without the device token, for what must never carry it. */
    val base: OkHttpClient = shared,
    private val onLocked: () -> Unit = {},
    /** What every read goes through, kept answers standing in for the server's. */
    cache: ResponseCache? = null,
) {
    /**
     * The artwork generation the latest response named. Images are asked for under it, so the cache
     * keeps one until some image changes and the server says so on whatever is asked next.
     */
    @Volatile
    var artworkGeneration: Long? = null
        private set

    private val signed: OkHttpClient =
        base
            .newBuilder()
            .apply {
                interceptors()
                    .add(
                        0,
                        Interceptor { chain ->
                            // Reached only without a cache, which answers a kept-only read itself.
                            if (ResponseCache.keptOnly.get() == true)
                                throw IOException("The server is out of reach.")
                            val request = chain.request()
                            val response =
                                chain.proceed(
                                    if (token == null) request
                                    else
                                        request
                                            .newBuilder()
                                            .header("Authorization", "Bearer $token")
                                            .build(),
                                )
                            // A response from the cache names the generation it was stored under.
                            if (response.networkResponse != null)
                                response.header("Artwork-Generation")?.toLongOrNull()?.let {
                                    if (it > 0) artworkGeneration = it
                                }
                            response
                        },
                    )
            }
            .build()

    /** Carries the device token, for requests outside the generated client such as artwork. */
    val http: OkHttpClient =
        cache?.let {
            signed.newBuilder().apply { interceptors().add(0, it.interceptor(signed)) }.build()
        } ?: signed

    val auth by lazy { AuthApi(endpoint.api, http) }
    val collections by lazy { CollectionsApi(endpoint.api, http) }
    val downloads by lazy { DownloadsApi(endpoint.api, http) }
    val events by lazy { EventsApi(endpoint.api, http) }
    val items by lazy { ItemsApi(endpoint.api, http) }
    val libraries by lazy { LibrariesApi(endpoint.api, http) }
    val media by lazy { MediaApi(endpoint.api, http) }
    val music by lazy { MusicApi(endpoint.api, http) }
    val people by lazy { PeopleApi(endpoint.api, http) }
    val playback by lazy { PlaybackApi(endpoint.api, http) }
    val playlists by lazy { PlaylistsApi(endpoint.api, http) }
    val profiles by lazy { ProfilesApi(endpoint.api, http) }
    val stations by lazy { StationsApi(endpoint.api, http) }
    val system by lazy { SystemApi(endpoint.api, http) }
    val together by lazy { TogetherApi(endpoint.api, http) }
    val youtube by lazy { YoutubeApi(endpoint.api, http) }

    suspend fun <T> call(block: Api.() -> T): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            if (ApiError.from(e)?.code == "device_locked") onLocked()
            throw e
        }

    /** The bytes at a server path, such as `/api/v1/items/7/artwork/poster`, with the status. */
    fun bytes(path: String): Fetched = runCatching {
        http.newCall(Request.Builder().url(endpoint.resolve(path)).build()).execute().use {
            Fetched(it.code, if (it.isSuccessful) it.body.bytes() else null)
        }
    }
        .getOrDefault(Fetched(null, null))

    companion object {
        val shared: OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

        fun cached(dir: File, bytes: Long = CACHE_BYTES): OkHttpClient =
            shared.newBuilder().cache(Cache(dir, bytes)).build()

        const val CACHE_BYTES = 256L * 1024 * 1024
    }
}

/** The server's shared error shape, read from a generated client exception. */
data class ApiError(
    val status: Int,
    val code: String,
    override val message: String,
    /** Seconds from a 429's Retry-After. */
    val retryAfter: Int? = null,
    /** The request fields a 400 names, each with the server's words for it. */
    val fields: Map<String, String> = emptyMap(),
    /** With off_air, when the station's next program starts. */
    val resumesAt: OffsetDateTime? = null,
) : Exception(message) {
    companion object {
        fun from(error: Throwable): ApiError? {
            val (status, body, headers) =
                when (error) {
                    is ApiError -> return error
                    is ClientException ->
                        (error.response as? ClientError<*>).let {
                            Triple(error.statusCode, it?.body as? String, it?.headers)
                        }
                    is ServerException ->
                        (error.response as? ServerError<*>).let {
                            Triple(error.statusCode, it?.body as? String, it?.headers)
                        }
                    else -> return null
                }
            val retry = headers?.entries?.firstOrNull { it.key.equals("retry-after", true) }
            val retryAfter = retry?.value?.firstOrNull()?.toIntOrNull()
            val decoded = body?.let {
                runCatching {
                    Serializer.kotlinxSerializationJson.decodeFromString(Error.serializer(), it)
                }
                    .getOrNull()
            }
            return decoded?.let {
                ApiError(
                    status,
                    it.code.value,
                    it.message,
                    retryAfter,
                    it.fields.orEmpty().associate { f -> f.field to f.message },
                    it.resumesAt,
                )
            } ?: ApiError(status, "internal", "The server answered $status.", retryAfter)
        }
    }
}
