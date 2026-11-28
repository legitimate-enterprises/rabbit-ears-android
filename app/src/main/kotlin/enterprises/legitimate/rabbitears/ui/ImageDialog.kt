package enterprises.legitimate.rabbitears.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.api.models.ArtworkKind
import enterprises.legitimate.rabbitears.api.models.Station
import enterprises.legitimate.rabbitears.kit.ArtChoice
import enterprises.legitimate.rabbitears.kit.CustomImageModel
import enterprises.legitimate.rabbitears.kit.ImageRequest
import enterprises.legitimate.rabbitears.kit.ImageSource
import enterprises.legitimate.rabbitears.kit.ImageTarget
import enterprises.legitimate.rabbitears.ui.theme.Palette
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Set image and, while one is set, Remove image, beside a station owner's Edit station. */
@Composable
fun StationImageActions(station: Station, reload: suspend () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var imaging by remember { mutableStateOf<ImageRequest?>(null) }
    imaging?.let {
        ImageDialog(it) { changed ->
            imaging = null
            if (changed) scope.launch { reload() }
        }
    }
    Pill(
        "Set image",
        { imaging = ImageRequest.of(station, nav.app.activeProfile?.id ?: nav.catalog.profileId) },
    )
    if (station.imageUrl != null)
        Pill(
            "Remove image",
            {
                scope.launch {
                    if (CustomImageModel(nav.catalog.api).remove(ImageTarget.Station(station.id)))
                        reload()
                }
            },
        )
}

/**
 * Set image: a photo from the system picker, one of the playlist's titles' artwork, or a link the
 * server fetches. [onDone] is told whether the image changed.
 */
@Composable
fun ImageDialog(request: ImageRequest, onDone: (Boolean) -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val model = remember(request) { CustomImageModel(nav.catalog.api) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    val choices by
        produceState<List<ArtChoice>?>(null, request) {
            value = model.choices(request.profileId, request.playlistId)
        }
    val run = { source: suspend () -> ImageSource ->
        scope.launch { if (model.set(source(), request.target)) onDone(true) }
        Unit
    }
    val picker =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            uri?.let { run { ImageSource.File(jpeg(context, it) ?: ByteArray(0)) } }
        }
    AlertDialog(
        { onDone(false) },
        confirmButton = { TextButton({ onDone(false) }) { Text("Cancel") } },
        title = { Text("Set image") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Pill(
                    "Choose a photo",
                    {
                        picker.launch(
                            PickVisualMediaRequest(PickVisualMedia.ImageOnly),
                        )
                    },
                )
                Text("Pick a title's artwork", style = MaterialTheme.typography.titleSmall)
                when {
                    choices == null -> Text("Loading…", color = Palette.dim)
                    choices.orEmpty().isEmpty() ->
                        Text("Nothing in it has artwork to pick.", color = Palette.dim)
                    else ->
                        FlowRow(
                            Modifier.heightIn(max = 260.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            choices.orEmpty().forEach { choice ->
                                Art(
                                    choice.path,
                                    artColor(choice.itemId),
                                    Modifier.width(
                                            if (choice.kind == ArtworkKind.backdrop) 112.dp
                                            else 56.dp,
                                        )
                                        .aspectRatio(
                                            if (choice.kind == ArtworkKind.backdrop) 16f / 9
                                            else 2f / 3,
                                        )
                                        .clip(MaterialTheme.shapes.small)
                                        .semantics { contentDescription = choice.label }
                                        .clickable(enabled = !model.busy) {
                                            run { ImageSource.Artwork(choice.itemId, choice.kind) }
                                        },
                                )
                            }
                        }
                }
                OutlinedTextField(
                    link,
                    { link = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Paste an image link") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Use link", { if (link.isNotBlank()) run { ImageSource.Link(link) } })
                }
                model.error?.let { Text(it, color = Palette.accent) }
            }
        },
    )
}

/**
 * The picked image as an upright JPEG no larger than [MAX_SIDE] on its long side, a format the
 * server takes whatever the photo's own; null when it cannot be read.
 */
private suspend fun jpeg(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bitmap =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(context.contentResolver, uri),
                    ) { decoder, info, _ ->
                        val side = maxOf(info.size.width, info.size.height)
                        if (side > MAX_SIDE) {
                            val scale = MAX_SIDE.toFloat() / side
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                else
                // shortcut: API 26-27 ignore EXIF orientation, so a rotated photo uploads sideways;
                // fine while few devices run them.
                context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            BitmapFactory.Options().apply { inSampleSize = 2 },
                        )
                    }
            bitmap?.let {
                ByteArrayOutputStream()
                    .apply { it.compress(Bitmap.CompressFormat.JPEG, 90, this) }
                    .toByteArray()
            }
        }
            .getOrNull()
    }

private const val MAX_SIDE = 3200
