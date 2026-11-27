package enterprises.legitimate.rabbitears.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.RabbitEarsApp
import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.Library
import enterprises.legitimate.rabbitears.api.models.LibraryKind
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.DownloadPlan
import enterprises.legitimate.rabbitears.kit.DownloadRequest
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.TitleState
import enterprises.legitimate.rabbitears.kit.pages
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** A Download asked for from a More menu: what it names, and whether it is music. */
data class DownloadAsk(val target: DownloadTarget, val name: String, val music: Boolean)

/** Download, or Remove download once the profile has asked for this one. */
@Composable
fun DownloadMenuItem(ask: DownloadAsk, close: () -> Unit, onDownload: (DownloadAsk) -> Unit) {
    val nav = LocalNav.current
    val downloads = nav.downloads ?: return
    val profileId = nav.catalog.profileId
    if (downloads.store.request(profileId, ask.target) != null)
        MenuAction("Remove download") {
            close()
            downloads.remove(profileId, ask.target)
        }
    else if (!nav.app.offline)
        MenuAction("Download") {
            close()
            onDownload(ask)
        }
}

/** A ⋮ holding only Download, for a page that has no other menu. */
@Composable
fun DownloadMore(ask: DownloadAsk) {
    if (LocalNav.current.downloads == null) return
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<DownloadAsk?>(null) }
    Box {
        IconAction(R.drawable.ic_more, "More", { open = true })
        DropdownMenu(open, { open = false }) {
            DownloadMenuItem(ask, { open = false }) { asking = it }
        }
    }
    asking?.let { DownloadDialog(it) { asking = null } }
}

private val VIDEO_QUALITIES =
    listOf(
        DownloadQuality.original,
        DownloadQuality._1080p,
        DownloadQuality._720p,
        DownloadQuality._480p,
        DownloadQuality._360p,
    )
private val MUSIC_QUALITIES = listOf(DownloadQuality.original, DownloadQuality.aac256)

private fun label(quality: DownloadQuality) =
    when (quality) {
        DownloadQuality.original -> "Original"
        DownloadQuality.aac256 -> "AAC 256"
        else -> quality.value
    }

/**
 * Asks the quality, with this device's default picked, then what the request holds; a big one is
 * confirmed with its count and size, and may stop asking for the profile.
 */
@Composable
fun DownloadDialog(ask: DownloadAsk, onDone: () -> Unit) {
    val nav = LocalNav.current
    val downloads = nav.downloads ?: return onDone()
    val api = nav.catalog.api
    val profileId = nav.catalog.profileId
    val scope = rememberCoroutineScope()
    val choices = if (ask.music) MUSIC_QUALITIES else VIDEO_QUALITIES
    val default = downloads.store.device.quality
    var quality by remember {
        mutableStateOf(
            if (default in choices) default
            else if (ask.music) DownloadQuality.aac256 else DownloadQuality.original,
        )
    }
    var plan by remember { mutableStateOf<DownloadPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dontAsk by remember { mutableStateOf(false) }
    val notify = notificationAsker()
    val process = LocalContext.current.applicationContext as RabbitEarsApp
    val add = { p: DownloadPlan ->
        notify()
        val dontAskAgain = dontAsk
        // Outlives the dialog, which closes at once rather than wait on a big request's metadata.
        process.scope.launch { downloads.add(api, p, dontAskAgain) }
        onDone()
    }
    val big = plan
    if (big != null) {
        AlertDialog(
            onDone,
            { TextButton({ add(big) }) { Text("Download") } },
            dismissButton = { TextButton(onDone) { Text("Cancel") } },
            title = { Text("This is a lot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${Format.plural(big.count, "title")}, about ${Format.bytes(big.bytes)}.",
                        color = Palette.secondary,
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable { dontAsk = !dontAsk },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            dontAsk,
                            null,
                            colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                        )
                        Text("Don’t ask again")
                    }
                }
            },
            containerColor = Palette.raised,
        )
        return
    }
    AlertDialog(
        onDone,
        {
            TextButton(
                {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val p = downloads.plan(api, profileId, ask.target, ask.name, quality)
                            when {
                                p.count == 0 -> error = "Nothing here can be downloaded now."
                                p.big -> plan = p
                                else -> add(p)
                            }
                        } catch (e: Exception) {
                            error = AppError.from(e, AppError.SignedOut).message
                        }
                        busy = false
                    }
                },
                enabled = !busy,
            ) {
                Text("Download")
            }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
        title = { Text("Download") },
        text = {
            Column {
                choices.forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { quality = q },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            q == quality,
                            { quality = q },
                            colors = RadioButtonDefaults.colors(selectedColor = Palette.accent),
                        )
                        Text(label(q))
                    }
                }
                error?.let { Text(it, color = Palette.accent) }
            }
        },
        containerColor = Palette.raised,
    )
}

/** Asks once for the notification that shows downloads under way, where Android asks for it. */
@Composable
private fun notificationAsker(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Whether the profile has [target] on the device, by [DownloadStore.badged]. */
@Composable
fun isDownloaded(target: DownloadTarget): Boolean {
    val nav = LocalNav.current
    val store = nav.downloads?.store ?: return false
    return store.badged(nav.catalog.profileId, target)
}

@Composable fun isDownloaded(id: String) = isDownloaded(DownloadTarget(itemId = id))

/**
 * The marks in the artwork's bottom end corner, laid right to left with Downloaded rightmost,
 * Shared beside it and In your order leftmost; [overBar] lifts them above a [TileBar].
 */
@Composable
fun BoxScope.StatusBadges(
    downloaded: Boolean,
    shared: Boolean = false,
    overBar: Boolean = false,
    ordered: Boolean = false,
) {
    Row(
        Modifier.align(Alignment.BottomEnd)
            .padding(end = 6.dp, bottom = if (overBar) 10.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (ordered) StatusBadge(R.drawable.ic_swap_vert, "In your order")
        if (shared) StatusBadge(R.drawable.ic_people, "Shared")
        if (downloaded) StatusBadge(R.drawable.ic_download, "Downloaded")
    }
}

/** A row's Downloaded mark, beside its trailing text. */
@Composable
fun DownloadedMark() {
    Icon(
        painterResource(R.drawable.ic_download),
        "Downloaded",
        Modifier.size(16.dp),
        tint = Palette.dim,
    )
}

@Composable
private fun StatusBadge(icon: Int, label: String) {
    Box(
        Modifier.size(22.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).semantics {
            contentDescription = label
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(14.dp), tint = Palette.accent)
    }
}

private val ALLOWANCES = listOf(2L, 5L, 10L, 25L, 50L, 100L).map { it * 1024 * 1024 * 1024 }

@Composable
fun DownloadsPreferences() {
    val nav = LocalNav.current
    val downloads = nav.downloads ?: return
    val store = downloads.store
    val profileId = nav.catalog.profileId
    val settings = store.settings(profileId)
    val device = store.device
    Text("Downloads", style = MaterialTheme.typography.titleLarge)
    Toggle(
        "Play downloads",
        "Plays what is on this phone even when your server is reachable.",
        settings.preferLocal,
    ) {
        store.setSettings(profileId, settings.copy(preferLocal = it))
    }
    Toggle("Wi-Fi only", "Downloads only on Wi-Fi.", device.wifiOnly) {
        store.setDevice(device.copy(wifiOnly = it))
        downloads.schedule()
    }
    Text("Quality", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VIDEO_QUALITIES.forEach { q ->
            Chip(label(q), device.quality == q) { store.setDevice(device.copy(quality = q)) }
        }
    }
    Text("Room on this phone", style = MaterialTheme.typography.titleMedium)
    Text(
        "${Format.bytes(store.usedBytes)} of ${Format.bytes(device.allowanceBytes)} used",
        color = Palette.secondary,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ALLOWANCES.forEach { bytes ->
            Chip(Format.bytes(bytes), device.allowanceBytes == bytes) {
                store.setDevice(device.copy(allowanceBytes = bytes))
                // A bigger allowance makes room for what waited for it.
                store.state.titles.values
                    .filter { it.state == TitleState.WaitingForSpace }
                    .forEach { t -> store.retry(t.itemId) }
                downloads.schedule()
            }
        }
    }
    KeepAfterWatched(profileId)
    store.requests(profileId).forEach { RequestRow(it) }
}

/** One switch per movies, TV and YouTube library; music always keeps. */
@Composable
private fun KeepAfterWatched(profileId: String) {
    val nav = LocalNav.current
    val store = nav.downloads?.store ?: return
    var libraries by remember { mutableStateOf<List<Library>>(emptyList()) }
    LaunchedEffect(nav) {
        if (nav.app.offline) return@LaunchedEffect
        libraries =
            runCatching {
                    pages { o ->
                        nav.catalog.api
                            .call { this.libraries.listLibraries(PAGE, o ?: 0) }
                            .let { it.items to it.nextOffset }
                    }
                }
                .getOrDefault(emptyList())
                .filter { it.kind != LibraryKind.music }
    }
    if (libraries.isEmpty()) return
    Text("Keep after watched", style = MaterialTheme.typography.titleMedium)
    libraries.forEach { library ->
        val settings = store.settings(profileId)
        val keeps = settings.keep[library.id] ?: (library.kind == LibraryKind.movies)
        Toggle(library.name, if (keeps) "Kept once watched." else "Removed once watched.", keeps) {
            store.setSettings(profileId, settings.copy(keep = settings.keep + (library.id to it)))
        }
    }
}

/** A request with its size and where its titles stand, removable, its failures retried. */
@Composable
private fun RequestRow(request: DownloadRequest) {
    val nav = LocalNav.current
    val downloads = nav.downloads ?: return
    val titles = request.titles.mapNotNull { downloads.store.title(it) }
    val size = titles.sumOf { maxOf(it.sizeBytes, it.receivedBytes) }
    val states =
        titles
            .groupBy { it.state }
            .map { (state, of) ->
                when (state) {
                    TitleState.OnDevice -> "${of.size} on this phone"
                    TitleState.Downloading ->
                        "downloading ${(of.first().progress * PERCENT).toInt()}%"
                    TitleState.Pending -> "${of.size} to download"
                    TitleState.WaitingForWifi -> "${of.size} waiting for Wi-Fi"
                    TitleState.WaitingForDataSaver ->
                        "${of.size} waiting for Wi-Fi or Data Saver off"
                    TitleState.WaitingForSpace -> "${of.size} waiting for room"
                    TitleState.Failed -> "${of.size} failed"
                }
            }
    val failed = titles.filter { it.state == TitleState.Failed }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(request.name.ifEmpty { "Download" }, style = MaterialTheme.typography.titleMedium)
            Text(
                (listOf(Format.bytes(size)) + states).joinToString(" · "),
                color = Palette.secondary,
            )
            failed.firstNotNullOfOrNull { it.error }?.let { Text(it, color = Palette.dim) }
        }
        if (failed.isNotEmpty())
            TextButton({ failed.forEach { downloads.retry(it.itemId) } }) { Text("Retry") }
        TextButton({ downloads.remove(request.profileId, request.target) }) { Text("Remove") }
    }
}

private const val PAGE = 200
private const val PERCENT = 100
