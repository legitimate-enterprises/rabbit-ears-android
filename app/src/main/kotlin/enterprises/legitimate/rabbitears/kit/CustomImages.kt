package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.infrastructure.ClientError
import enterprises.legitimate.rabbitears.api.infrastructure.ClientException
import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.infrastructure.ServerError
import enterprises.legitimate.rabbitears.api.infrastructure.ServerException
import enterprises.legitimate.rabbitears.api.models.ArtworkKind
import enterprises.legitimate.rabbitears.api.models.CustomImageInput
import enterprises.legitimate.rabbitears.api.models.Playlist
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import enterprises.legitimate.rabbitears.api.models.Station
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** What a custom image is set on: a playlist under its owner's profile, or a station. */
sealed interface ImageTarget {
    val path: String

    data class Playlist(val profileId: String, val playlistId: String) : ImageTarget {
        override val path = "profiles/$profileId/playlists/$playlistId/image"
    }

    data class Station(val id: String) : ImageTarget {
        override val path = "stations/$id/image"
    }
}

sealed interface ImageSource {
    /** An image from the device, already made a JPEG the server takes. */
    class File(val bytes: ByteArray) : ImageSource

    data class Artwork(val itemId: String, val kind: ArtworkKind) : ImageSource

    /** A link the server fetches once. */
    data class Link(val url: String) : ImageSource
}

/** One choice of "Pick a title's artwork". */
data class ArtChoice(val itemId: String, val kind: ArtworkKind, val label: String)

/** An owner's Set image request: where it goes, and the playlist whose titles it may pick from. */
data class ImageRequest(val target: ImageTarget, val profileId: String, val playlistId: String) {
    companion object {
        fun of(playlist: Playlist, profileId: String) =
            ImageRequest(ImageTarget.Playlist(profileId, playlist.id), profileId, playlist.id)

        fun of(station: Station, profileId: String) =
            ImageRequest(ImageTarget.Station(station.id), profileId, station.playlistId)
    }
}

object CustomImage {
    /**
     * The artwork the entries offer, each title once: a poster, and a backdrop for movies and TV;
     * music has only covers, and a collection offers none.
     */
    fun choices(entries: List<PlaylistEntry>): List<ArtChoice> {
        val seen = mutableSetOf<String>()
        return entries.flatMap { entry ->
            val id = entry.itemId
            if (id == null || !seen.add(id)) return@flatMap emptyList()
            val kinds =
                if (entry.item == null) listOf(ArtworkKind.poster)
                else listOf(ArtworkKind.poster, ArtworkKind.backdrop)
            kinds.map { ArtChoice(id, it, "${entry.title}, ${it.value}") }
        }
    }
}

/** Sets and removes a playlist's or station's custom image; the server allows its owner only. */
class CustomImageModel(private val api: Api) {
    var error by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /**
     * What "Pick a title's artwork" offers from the playlist's entries; empty when none or unread.
     */
    suspend fun choices(profileId: String, playlistId: String): List<ArtChoice> =
        CustomImage.choices(PlaylistsModel(api, profileId).entries(playlistId).orEmpty())

    /** True once the server keeps it. */
    suspend fun set(source: ImageSource, target: ImageTarget): Boolean {
        val body: RequestBody =
            when (source) {
                is ImageSource.File -> source.bytes.toRequestBody(OCTETS)
                is ImageSource.Artwork ->
                    json(CustomImageInput(itemId = source.itemId, kind = source.kind))
                is ImageSource.Link ->
                    json(
                        CustomImageInput(
                            url =
                                source.url.trim().ifEmpty {
                                    return false
                                },
                        ),
                    )
            }
        return write { send(Request.Builder().url(url(target)).put(body).build()) }
    }

    /** True once the image is gone, back to what shows without one. */
    suspend fun remove(target: ImageTarget): Boolean = write {
        send(Request.Builder().url(url(target)).delete().build())
    }

    private fun url(target: ImageTarget) = "${api.endpoint.api}/${target.path}"

    private fun json(input: CustomImageInput) =
        BODY.encodeToString(CustomImageInput.serializer(), input).toRequestBody(JSON)

    /**
     * Throws the generated client's exceptions, so [ApiError] reads a refusal as it does any other.
     */
    private suspend fun send(request: Request) = api.call {
        http.newCall(request).execute().use { r ->
            if (r.isSuccessful) return@use
            val text = r.body.string()
            val headers = r.headers.toMultimap()
            throw if (r.code < 500)
                ClientException(text, r.code, ClientError<Unit>(text, text, r.code, headers))
            else ServerException(text, r.code, ServerError<Unit>(text, text, r.code, headers))
        }
    }

    private suspend fun write(block: suspend () -> Unit): Boolean {
        busy = true
        return try {
            block()
            error = null
            true
        } catch (e: Exception) {
            error =
                ApiError.from(e)?.fields?.values?.firstOrNull()
                    ?: AppError.from(e, AppError.SignedOut).message
            false
        } finally {
            busy = false
        }
    }

    private companion object {
        val OCTETS = "application/octet-stream".toMediaType()
        val JSON = "application/json".toMediaType()

        /**
         * The spec's fields are not nullable, so an absent one is left out rather than sent null.
         */
        val BODY = Json(Serializer.kotlinxSerializationJson) { explicitNulls = false }
    }
}
