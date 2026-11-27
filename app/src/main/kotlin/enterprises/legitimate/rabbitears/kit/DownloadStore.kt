package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.DownloadTitle
import enterprises.legitimate.rabbitears.api.models.Me
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable

@Serializable
enum class TitleState {
    Pending,
    WaitingForWifi,
    WaitingForDataSaver,
    WaitingForSpace,
    Downloading,
    OnDevice,
    Failed,
}

/**
 * What one profile asked to keep. A [container] stands: refreshed, it covers what the container
 * holds then. [titles] are the item ids it covers now, after the keep-after-watched policy.
 */
@Serializable
data class DownloadRequest(
    val id: Long,
    val profileId: String,
    val target: DownloadTarget,
    val name: String,
    val quality: DownloadQuality,
    val container: Boolean,
    val titles: List<String>,
)

@Serializable
data class StoredSubtitle(
    val trackId: String,
    val language: String? = null,
    val title: String? = null,
    val forced: Boolean,
    val file: String,
)

/** The server's claim on a title's file, made as [profileId], the only profile that may use it. */
@Serializable data class Claim(val id: String, val profileId: String)

/**
 * The one copy of a title on this device, whichever requests and profiles cover it. [file] is the
 * name of the media under the store's directory, set once the transfer starts. [claim] is held from
 * when the title is claimed until it is fetched, fails or goes.
 */
@Serializable
data class StoredTitle(
    val itemId: String,
    val kind: DownloadTitle.Kind,
    val libraryId: String,
    val quality: DownloadQuality,
    val editionId: String? = null,
    val runtimeMs: Long? = null,
    val sizeBytes: Long = 0,
    val receivedBytes: Long = 0,
    val state: TitleState = TitleState.Pending,
    val error: String? = null,
    val file: String? = null,
    val etag: String? = null,
    val subtitles: List<StoredSubtitle> = emptyList(),
    val claim: Claim? = null,
) {
    val progress: Double
        get() = if (sizeBytes <= 0) 0.0 else minOf(1.0, receivedBytes.toDouble() / sizeBytes)
}

/** A profile's watch state as this device last knew it, [synced] once the server has it. */
@Serializable
data class LocalWatch(
    val itemId: String,
    val positionMs: Long,
    val watched: Boolean,
    val atMs: Long,
    val synced: Boolean,
    val editionId: String? = null,
)

/** One profile's downloads settings on this device; [keep] is keep-after-watched by library. */
@Serializable
data class ProfileDownloadSettings(
    val preferLocal: Boolean = true,
    val keep: Map<String, Boolean> = emptyMap(),
    val dontAskBig: Boolean = false,
)

@Serializable
data class DeviceDownloadSettings(
    val wifiOnly: Boolean = true,
    val allowanceBytes: Long = DownloadStore.DEFAULT_ALLOWANCE,
    val quality: DownloadQuality = DownloadQuality.original,
)

@Serializable
data class DownloadState(
    val requests: List<DownloadRequest> = emptyList(),
    val titles: Map<String, StoredTitle> = emptyMap(),
    val watch: Map<String, Map<String, LocalWatch>> = emptyMap(),
    val profiles: Map<String, ProfileDownloadSettings> = emptyMap(),
    val device: DeviceDownloadSettings = DeviceDownloadSettings(),
    /** The signed-in household as last read, for browsing with no server; JSON of a Me. */
    val me: String? = null,
    val nextId: Long = 1,
    /** Claims of titles that went unfetched, for the server to let go of. */
    val releases: List<Claim> = emptyList(),
)

/**
 * This device's downloads under [dir]. A title is stored once and goes, file and all, with the last
 * request covering it. [writer] writes the whole state off the caller's thread, replacing the last
 * whole file, so a crash loses only the latest changes. [free] is the bytes the disk under [dir]
 * can give, which only the system knows: unlimited unless told.
 */
class DownloadStore(
    val dir: File,
    private val free: () -> Long = { Long.MAX_VALUE },
    private val writer: Executor = WRITER,
) {
    private val stateFile = File(dir, "state.json")
    private val dirty = AtomicBoolean()

    var state by mutableStateOf(read())
        private set

    /**
     * Made once per process, before any transfer, so a title still Downloading was cut off by the
     * process dying; it goes back to Pending to resume from its partial file.
     */
    private fun read(): DownloadState = runCatching {
        json.decodeFromString(DownloadState.serializer(), stateFile.readText())
    }
        .getOrDefault(DownloadState())
        .let { s ->
            s.copy(
                titles =
                    s.titles.mapValues { (_, t) ->
                        if (t.state == TitleState.Downloading) t.copy(state = TitleState.Pending)
                        else t
                    },
            )
        }

    /**
     * Applies [change] and writes the result; titles left uncovered are deleted with it, after the
     * write that no longer lists them, and their claims are kept to be let go of. A change that
     * leaves the state as it was writes nothing.
     */
    @Synchronized
    private fun update(change: (DownloadState) -> DownloadState) {
        val before = state
        val after =
            change(before).let { s ->
                val covered = s.requests.flatMap { it.titles }.toSet()
                val (kept, uncovered) = s.titles.values.partition { it.itemId in covered }
                s.copy(
                    titles = kept.associateBy { it.itemId },
                    releases = s.releases + uncovered.mapNotNull { it.claim },
                )
            }
        if (after == before) return
        state = after
        persist()
        val gone = (before.titles.keys - after.titles.keys).map { before.titles.getValue(it) }
        if (gone.isNotEmpty()) writer.execute { gone.forEach(::deleteFiles) }
    }

    /** Writes the latest state soon; changes made before the write starts share it. */
    private fun persist() {
        if (dirty.compareAndSet(false, true))
            writer.execute {
                dirty.set(false)
                // A write that fails leaves the last whole file, and the next change writes again.
                runCatching {
                    dir.mkdirs()
                    val tmp = File(dir, "state.json.tmp")
                    tmp.writeText(json.encodeToString(DownloadState.serializer(), state))
                    tmp.renameTo(stateFile)
                }
            }
    }

    /** Waits for the changes made so far to be on disk. */
    fun flush() {
        val done = CountDownLatch(1)
        writer.execute { done.countDown() }
        done.await()
    }

    val device: DeviceDownloadSettings
        get() = state.device

    fun settings(profileId: String) = state.profiles[profileId] ?: ProfileDownloadSettings()

    fun setDevice(settings: DeviceDownloadSettings) = update { it.copy(device = settings) }

    fun setSettings(profileId: String, settings: ProfileDownloadSettings) = update {
        it.copy(profiles = it.profiles + (profileId to settings))
    }

    fun requests(profileId: String) = state.requests.filter { it.profileId == profileId }

    fun request(profileId: String, target: DownloadTarget) =
        state.requests.firstOrNull { it.profileId == profileId && it.target == target }

    fun title(itemId: String) = state.titles[itemId]

    fun titles(profileId: String): List<StoredTitle> =
        requests(profileId).flatMap { it.titles }.distinct().mapNotNull { state.titles[it] }

    /** The profile's copy of the title, when it is on the device. */
    fun onDevice(profileId: String, itemId: String): StoredTitle? =
        state.titles[itemId]?.takeIf {
            it.state == TitleState.OnDevice && requests(profileId).any { r -> itemId in r.titles }
        }

    /**
     * For the artwork badge: a title the profile has on the device, or the profile's request for
     * exactly [target] with every one of its titles on the device.
     */
    fun badged(profileId: String, target: DownloadTarget): Boolean {
        if (target.itemId?.let { onDevice(profileId, it) } != null) return true
        val titles = request(profileId, target)?.titles.orEmpty()
        return titles.isNotEmpty() && titles.all { state.titles[it]?.state == TitleState.OnDevice }
    }

    /** Whether a library's watched titles stay; music always does. */
    fun keeps(profileId: String, libraryId: String, kind: DownloadTitle.Kind): Boolean =
        kind == DownloadTitle.Kind.song ||
            settings(profileId).keep[libraryId] ?: (kind == DownloadTitle.Kind.movie)

    /** Whether a request of [count] titles taking [bytes] more is asked about first. */
    fun isBig(profileId: String, count: Int, bytes: Long): Boolean =
        !settings(profileId).dontAskBig &&
            (count > BIG_COUNT || bytes * BIG_SHARE_DIVISOR > device.allowanceBytes)

    /** What of [resolved] a request keeps: a container leaves out what its policy removes. */
    fun kept(profileId: String, container: Boolean, resolved: List<DownloadTitle>) =
        resolved.filter {
            it.available &&
                !(container &&
                    watchedNow(profileId, it) &&
                    !keeps(profileId, it.libraryId, it.kind))
        }

    fun newBytes(titles: List<DownloadTitle>) =
        titles.filter { it.itemId !in state.titles }.sumOf { it.sizeBytes ?: 0 }

    /**
     * Files the request, or replaces the titles of the profile's request for the same target, with
     * what [resolved] holds now. Titles not yet stored wait as pending.
     */
    fun put(
        profileId: String,
        target: DownloadTarget,
        name: String,
        quality: DownloadQuality,
        resolved: List<DownloadTitle>,
    ): DownloadRequest {
        var made: DownloadRequest? = null
        update { s ->
            val existing =
                s.requests.firstOrNull { it.profileId == profileId && it.target == target }
            val container = target.itemId == null || resolved.none { it.itemId == target.itemId }
            val keep = kept(profileId, container, resolved)
            val request =
                existing?.copy(titles = keep.map { it.itemId }, container = container)
                    ?: DownloadRequest(
                        s.nextId,
                        profileId,
                        target,
                        name,
                        quality,
                        container,
                        keep.map { it.itemId },
                    )
            made = request
            val added =
                keep
                    .filter { it.itemId !in s.titles }
                    .associate { it.itemId to stored(it, request.quality) }
            val seeded = keep.fold(s.watch) { w, t -> seed(w, profileId, t) }
            s.copy(
                requests =
                    if (existing == null) s.requests + request
                    else s.requests.map { if (it.id == existing.id) request else it },
                titles = s.titles + added,
                watch = seeded,
                nextId = if (existing == null) s.nextId + 1 else s.nextId,
            )
        }
        return made!!
    }

    fun remove(requestId: Long) = update { s ->
        s.copy(requests = s.requests.filter { it.id != requestId })
    }

    /** Takes titles the profile may no longer see out of every request of its. */
    fun drop(profileId: String, itemIds: Collection<String>) = update { s ->
        val gone = itemIds.toSet()
        s.copy(
            requests =
                s.requests.map {
                    if (it.profileId == profileId) it.copy(titles = it.titles - gone) else it
                },
        )
    }

    /**
     * Deletes everything, as on sign-out or a revoked device. The files go on the writer, after the
     * emptied state is on disk, so nothing may bring the old state back once they are gone.
     */
    fun wipe() {
        update { DownloadState(device = it.device) }
        writer.execute {
            dir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
        }
    }

    /** Deletes what one profile keeps, as when the server no longer knows it. */
    fun wipe(profileId: String) = update { s ->
        s.copy(
            requests = s.requests.filter { it.profileId != profileId },
            watch = s.watch - profileId,
            profiles = s.profiles - profileId,
        )
    }

    fun setTitle(itemId: String, change: (StoredTitle) -> StoredTitle) = update { s ->
        val title = s.titles[itemId] ?: return@update s
        s.copy(titles = s.titles + (itemId to change(title)))
    }

    /**
     * Keeps the title's claim. One the title held before, or one for a title gone meanwhile, is
     * kept to be let go of.
     */
    fun claimed(itemId: String, claim: Claim) = update { s ->
        val title = s.titles[itemId] ?: return@update s.copy(releases = s.releases + claim)
        s.copy(
            titles = s.titles + (itemId to title.copy(claim = claim)),
            releases = s.releases + listOfNotNull(title.claim?.takeIf { it != claim }),
        )
    }

    /** The server let go of the claim, or no longer has it. */
    fun released(claim: Claim) = update { s ->
        s.copy(
            titles =
                s.titles.mapValues { (_, t) -> if (t.claim == claim) t.copy(claim = null) else t },
            releases = s.releases - claim,
        )
    }

    /**
     * Notes how much of a title has arrived. Kept in memory and reaching disk with the next write:
     * a resume reads the partial file, not this. A new ETag is written at once, as a resume needs
     * it to know the partial file is still the one asked for.
     */
    @Synchronized
    fun progress(itemId: String, receivedBytes: Long, etag: String?) {
        val title = state.titles[itemId] ?: return
        if (title.etag != etag)
            return setTitle(itemId) { it.copy(receivedBytes = receivedBytes, etag = etag) }
        state =
            state.copy(
                titles = state.titles + (itemId to title.copy(receivedBytes = receivedBytes)),
            )
    }

    fun setMe(me: String?) = update { it.copy(me = me) }

    /**
     * Deletes what each profile the household no longer has keeps. Only the owner's answer lists
     * pending adults, so only it names every profile the household has.
     */
    fun prune(me: Me) {
        if (me.household?.adultId != me.activeProfileId) return
        val known = state.requests.map { it.profileId } + state.profiles.keys + state.watch.keys
        (known.toSet() - me.profiles.map { it.id }.toSet()).forEach(::wipe)
    }

    /** Bytes the device holds or has set aside for titles under way. */
    val usedBytes: Long
        get() =
            state.titles.values
                .filter { it.state == TitleState.OnDevice || it.state == TitleState.Downloading }
                .sumOf { maxOf(it.sizeBytes, it.receivedBytes) }

    /** Bytes the disk can still give a download, beyond what it leaves free. */
    val freeBytes: Long
        get() {
            // A disk with no directory yet has none to give.
            dir.mkdirs()
            return free() - RESERVE_BYTES
        }

    /**
     * The next title to fetch, in request order. Titles that would pass the allowance, or fill the
     * disk, wait for space and are passed over for one that fits; a failed one waits to be retried,
     * and [skip] names titles to pass over.
     */
    fun next(skip: Set<String> = emptySet()): StoredTitle? {
        var room = device.allowanceBytes - usedBytes
        var disk = freeBytes
        var found: StoredTitle? = null
        for (title in queue()) {
            if (title.state !in WAITING || title.itemId in skip) continue
            val left = left(title)
            val fits = title.sizeBytes <= room && left <= disk
            if (!fits || found != null) {
                if (!fits && title.state != TitleState.WaitingForSpace)
                    setTitle(title.itemId) { it.copy(state = TitleState.WaitingForSpace) }
                continue
            }
            found = title
            room -= title.sizeBytes
            disk -= left
        }
        return found
    }

    /** The waiting titles, in request order, that fit the allowance and the disk all together. */
    fun fitting(): List<StoredTitle> {
        var room = device.allowanceBytes - usedBytes
        var disk = freeBytes
        return queue().filter { title ->
            val left = left(title)
            val fits = title.state in WAITING && title.sizeBytes <= room && left <= disk
            if (fits) {
                room -= title.sizeBytes
                disk -= left
            }
            fits
        }
    }

    private fun queue() =
        state.requests.flatMap { it.titles }.distinct().mapNotNull { state.titles[it] }

    /** The bytes a title still needs on the disk; a partial file already holds its own. */
    private fun left(title: StoredTitle): Long {
        val have = title.file?.let { media("$it.part").length() } ?: 0
        return maxOf(0, title.sizeBytes - have)
    }

    /**
     * Marks every title waiting to start, or waiting on the network, as [waiting]: for Wi-Fi, for
     * Data Saver, or Pending once nothing holds it back.
     */
    fun awaitNetwork(waiting: TitleState) = update { s ->
        s.copy(
            titles =
                s.titles.mapValues { (_, t) ->
                    if (t.state in NETWORK) t.copy(state = waiting) else t
                },
        )
    }

    /** Back to pending after a failure, or after the allowance grew. */
    fun retry(itemId: String) =
        setTitle(itemId) { it.copy(state = TitleState.Pending, error = null) }

    fun watch(profileId: String, itemId: String) = state.watch[profileId]?.get(itemId)

    fun watches(profileId: String) = state.watch[profileId].orEmpty().values

    /**
     * Once watched and [ended], a title a container covers in a library that does not keep watched
     * titles leaves that container. Until then it stays, whose playback may still be under way; the
     * next sync applies it should playback never get to end.
     */
    fun record(
        profileId: String,
        itemId: String,
        positionMs: Long,
        watched: Boolean,
        atMs: Long,
        editionId: String? = null,
        ended: Boolean = true,
    ) = update { s ->
        val mark = LocalWatch(itemId, positionMs, watched, atMs, synced = false, editionId)
        val watch = s.watch + (profileId to (s.watch[profileId].orEmpty() + (itemId to mark)))
        s.copy(
            watch = watch,
            requests = if (ended) policed(s, profileId, itemId, watched) else s.requests,
        )
    }

    /** The changes still to send, oldest first, as the server must apply them. */
    fun unsynced(profileId: String) = watches(profileId).filter { !it.synced }.sortedBy { it.atMs }

    /**
     * Takes the server's state for a title, unless this device changed it since [sentAtMs]: what
     * was sent then is synced, a later local change still waits.
     */
    fun synced(profileId: String, mark: LocalWatch, sentAtMs: Long? = null) = update { s ->
        val local = s.watch[profileId]?.get(mark.itemId)
        if (local != null && !local.synced && sentAtMs != null && local.atMs > sentAtMs)
            return@update s
        val watch = s.watch + (profileId to (s.watch[profileId].orEmpty() + (mark.itemId to mark)))
        s.copy(watch = watch, requests = policed(s, profileId, mark.itemId, mark.watched))
    }

    private fun policed(s: DownloadState, profileId: String, itemId: String, watched: Boolean) =
        if (!watched) s.requests
        else {
            val title = s.titles[itemId]
            if (title == null || keeps(profileId, title.libraryId, title.kind)) s.requests
            else
                s.requests.map {
                    if (it.profileId == profileId && it.container)
                        it.copy(titles = it.titles - itemId)
                    else it
                }
        }

    private fun watchedNow(profileId: String, title: DownloadTitle): Boolean {
        val local = watch(profileId, title.itemId)
        return if (local != null && !local.synced) local.watched else title.watched
    }

    /** The server's watch state for a title, unless this device has a change yet to send. */
    private fun seed(
        watch: Map<String, Map<String, LocalWatch>>,
        profileId: String,
        title: DownloadTitle,
    ): Map<String, Map<String, LocalWatch>> {
        val mine = watch[profileId].orEmpty()
        if (mine[title.itemId]?.synced == false) return watch
        val mark =
            LocalWatch(
                title.itemId,
                title.positionMs,
                title.watched,
                mine[title.itemId]?.atMs ?: 0,
                synced = true,
                title.editionId,
            )
        return watch + (profileId to (mine + (title.itemId to mark)))
    }

    private fun stored(title: DownloadTitle, quality: DownloadQuality) =
        StoredTitle(
            title.itemId,
            title.kind,
            title.libraryId,
            title.quality ?: quality,
            title.editionId,
            title.runtimeMs,
            title.sizeBytes ?: 0,
        )

    fun media(name: String) = File(dir, "media/$name")

    /** Where the title's metadata, as the server answered it, is kept. */
    fun meta(key: String) = File(dir, "meta/$key.json")

    private fun deleteFiles(title: StoredTitle) {
        title.file?.let {
            media(it).delete()
            media("$it.part").delete()
        }
        title.subtitles.forEach { media(it.file).delete() }
    }

    companion object {
        const val DEFAULT_ALLOWANCE = 10L * 1024 * 1024 * 1024
        /** What a download leaves free on the disk, for the system and the phone's other uses. */
        const val RESERVE_BYTES = 512L * 1024 * 1024
        const val BIG_COUNT = 50
        /** A request is a lot past a fifth of the allowance. */
        const val BIG_SHARE_DIVISOR = 5
        private val NETWORK =
            setOf(TitleState.Pending, TitleState.WaitingForWifi, TitleState.WaitingForDataSaver)
        val WAITING = NETWORK + TitleState.WaitingForSpace
        val json = Serializer.kotlinxSerializationJson

        /** One thread for every store's writes, which wait their turn. */
        private val WRITER = Executors.newSingleThreadExecutor {
            Thread(it, "downloads-state").apply { isDaemon = true }
        }
    }
}
