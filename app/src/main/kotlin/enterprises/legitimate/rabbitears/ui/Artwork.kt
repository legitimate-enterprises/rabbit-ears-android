package enterprises.legitimate.rabbitears.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.kit.Api
import enterprises.legitimate.rabbitears.kit.DownloadMeta
import enterprises.legitimate.rabbitears.kit.Fetched
import enterprises.legitimate.rabbitears.kit.PlaceholderKind
import enterprises.legitimate.rabbitears.kit.ProfileColors
import enterprises.legitimate.rabbitears.kit.artworkAddress
import enterprises.legitimate.rabbitears.kit.placeholderOfPath
import enterprises.legitimate.rabbitears.kit.remembersArtwork
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artwork needs the device token, so it is fetched through the API's client and kept decoded in
 * memory, up to [CACHE_BYTES], with the client's disk cache beneath. An image the server does not
 * have stays missing; one that failed to arrive is asked for again the next time its tile appears.
 * When the server names a new artwork generation, what is held in memory, and what was missing, is
 * asked for again.
 */
class ArtworkStore(
    private val api: Api,
    /** The artwork kept with downloads, shown when the server can't be reached. */
    private val kept: DownloadMeta? = null,
    private val unreachable: () -> Boolean = { false },
) {
    private val cache =
        object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: ImageBitmap) =
                value.asAndroidBitmap().allocationByteCount
        }

    private val missing = ConcurrentHashMap.newKeySet<String>()
    private var generation: Long? = null

    /** The image if it is in memory now, so a tile that has it needs no placeholder or fade. */
    fun peek(path: String): ImageBitmap? = cache.get(path)

    private fun keepCurrent() {
        val named = api.artworkGeneration
        if (named == generation) return
        if (generation != null) {
            cache.evictAll()
            missing.clear()
        }
        generation = named
    }

    suspend fun load(path: String): ImageBitmap? {
        keepCurrent()
        cache.get(path)?.let {
            return it
        }
        if (path in missing) return null
        val (fetched, image) =
            withContext(Dispatchers.IO) {
                val fetched =
                    (if (unreachable()) Fetched(null, null)
                        else api.bytes(artworkAddress(path, generation)))
                        .let { if (it.status == null) Fetched(null, kept?.art(path)) else it }
                fetched to
                    runCatching {
                        fetched.body?.let { bytes ->
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            // Tiles are at most a few hundred pixels; keep big posters from filling
                            // memory.
                            var sample = 1
                            while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
                            val options = BitmapFactory.Options().apply { inSampleSize = sample }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                ?.asImageBitmap()
                        }
                    }
            }
        val decoded = image.getOrNull()
        if (remembersArtwork(fetched.status, decoded != null)) {
            if (decoded != null) cache.put(path, decoded) else missing.add(path)
        }
        return decoded
    }

    companion object {
        const val MAX_WIDTH = 720

        val CACHE_BYTES = (Runtime.getRuntime().maxMemory() / 8).toInt()

        fun item(id: String, kind: String = "poster") = "/api/v2/items/$id/artwork/$kind"

        fun collection(id: String, kind: String = "poster") =
            "/api/v2/collections/$id/artwork/$kind"

        fun person(id: String) = "/api/v2/people/$id/artwork"
    }
}

val LocalArtwork = compositionLocalOf<ArtworkStore?> { null }

/** The tile palette, stable per id, so a title keeps its color until its artwork arrives. */
private val ART =
    longArrayOf(
        0x2E6B8A,
        0x6B3A2E,
        0x5B6B3F,
        0x4A3F5B,
        0x3F5B8A,
        0x5A2B2B,
        0x2B5A4A,
        0x8A5A2B,
        0x3B3F55,
        0x4C4A48,
    )
/** Cast and crew placeholders; the server's profile colors, which profiles take from it alone. */
private val AVATARS = ProfileColors.PALETTE.map { it.removePrefix("#").toLong(16) }

fun artColor(id: String) = Color(0xFF000000 or ART[Math.floorMod(id.hashCode(), ART.size)])

fun avatarColor(id: String) =
    Color(0xFF000000 or AVATARS[Math.floorMod(id.hashCode(), AVATARS.size)])

/** A colored box that paints the image at [path] over itself once it loads. */
@Composable
fun Art(
    path: String?,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) = Art(path, color, modifier, path?.let(::placeholderOfPath), content)

/**
 * As above, with [kind]'s placeholder over the color until the image has loaded, and for good when
 * there is none. The image fades in over it, unless it was already in memory.
 */
@Composable
fun Art(
    path: String?,
    color: Color,
    modifier: Modifier,
    kind: PlaceholderKind?,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val store = LocalArtwork.current
    val held = remember(path, store) { path?.let { store?.peek(it) } }
    val image by produceState(held, path, store) { value = path?.let { store?.load(it) } ?: value }
    // The system's animation scale applies, so a phone set to remove animations shows it at once.
    val shown = remember(path, store) { Animatable(if (held != null) 1f else 0f) }
    LaunchedEffect(image) { if (image != null) shown.animateTo(1f, tween(FADE_MS)) }
    Box(modifier.background(color)) {
        if (kind != null && shown.value < 1f)
            Image(
                painterResource(kind.icon),
                null,
                Modifier.align(Alignment.Center).fillMaxSize(0.4f).alpha(0.6f * (1f - shown.value)),
            )
        image?.let {
            Image(
                it,
                null,
                Modifier.matchParentSize().alpha(shown.value),
                contentScale = ContentScale.Crop,
            )
        }
        content()
    }
}

private const val FADE_MS = 200

private val PlaceholderKind.icon: Int
    get() =
        when (this) {
            PlaceholderKind.Movie -> R.drawable.ph_movie
            PlaceholderKind.Show -> R.drawable.ph_show
            PlaceholderKind.Album -> R.drawable.ph_album
            PlaceholderKind.Artist -> R.drawable.ph_artist
            PlaceholderKind.Song -> R.drawable.ph_song
            PlaceholderKind.Channel -> R.drawable.ph_channel
            PlaceholderKind.Video -> R.drawable.ph_video
            PlaceholderKind.Playlist -> R.drawable.ph_playlist
            PlaceholderKind.Station -> R.drawable.ph_station
            PlaceholderKind.Collection -> R.drawable.ph_collection
            PlaceholderKind.Person -> R.drawable.ph_person
        }
