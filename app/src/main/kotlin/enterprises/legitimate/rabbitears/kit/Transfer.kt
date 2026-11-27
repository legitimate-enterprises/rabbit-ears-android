package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Error
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Moves a download's bytes onto the device: [HttpTransfer] in the app, a fake in tests. */
interface Transfer {
    /**
     * Fetches [url] into [into], resuming the partial file beside it while the server's ETag is
     * still [etag]. [progress] hears the bytes held so far and the ETag being fetched. A refusal
     * throws an [ApiError] with the server's status.
     */
    suspend fun fetch(
        url: String,
        into: File,
        etag: String?,
        progress: (received: Long, etag: String?) -> Unit = { _, _ -> },
    )
}

/**
 * Fetches with the device's token, through the client [http] gives at the time, resuming with Range
 * and If-Range: a 206 appends to the partial file, a 200 starts it over. The file appears at [into]
 * only once whole.
 */
class HttpTransfer(private val http: () -> OkHttpClient) : Transfer {
    override suspend fun fetch(
        url: String,
        into: File,
        etag: String?,
        progress: (Long, String?) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            into.parentFile?.mkdirs()
            val part = File(into.path + ".part")
            if (etag == null) part.delete()
            val have = if (part.exists()) part.length() else 0L
            val request =
                Request.Builder()
                    .url(url)
                    // The client's disk cache is sized for artwork, not media.
                    .cacheControl(CacheControl.Builder().noStore().build())
                    .apply {
                        if (have > 0 && etag != null) {
                            header("Range", "bytes=$have-")
                            header("If-Range", etag)
                        }
                    }
                    .build()
            http().newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) {
                    val body = response.body.string()
                    val error = runCatching {
                        Serializer.kotlinxSerializationJson.decodeFromString(
                            Error.serializer(),
                            body,
                        )
                    }
                        .getOrNull()
                    throw ApiError(
                        response.code,
                        error?.code?.value ?: "internal",
                        error?.message ?: "The server answered ${response.code}.",
                    )
                }
                val tag = response.header("ETag")
                val append = response.code == 206
                var received = if (append) have else 0L
                progress(received, tag)
                var reported = received
                FileOutputStream(part, append).use { out ->
                    val source = response.body.byteStream()
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        received += n
                        if (received - reported >= REPORT_BYTES) {
                            progress(received, tag)
                            reported = received
                        }
                    }
                }
                progress(received, tag)
                into.delete()
                if (!part.renameTo(into)) throw java.io.IOException("Couldn't keep ${into.name}")
            }
        }

    companion object {
        private const val BUFFER = 64 * 1024
        /** Progress is told at most every this many bytes, since each telling is a disk write. */
        private const val REPORT_BYTES = 1024L * 1024
    }
}
