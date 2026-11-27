package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.CapabilitySnapshot
import enterprises.legitimate.rabbitears.api.models.Download
import enterprises.legitimate.rabbitears.api.models.DownloadBatchInput
import enterprises.legitimate.rabbitears.api.models.DownloadBatchTitle
import enterprises.legitimate.rabbitears.api.models.DownloadInput
import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadResolveInput
import enterprises.legitimate.rabbitears.api.models.DownloadSyncInput
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.DownloadTitle
import enterprises.legitimate.rabbitears.api.models.Error.Code as ErrorCode
import enterprises.legitimate.rabbitears.api.models.WatchStateInput
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Starts the background transfers: WorkManager in the app, a counter in tests. */
interface DownloadScheduler {
    /** Runs [Downloads.run] in the background, only on an unmetered network when [wifiOnly]. */
    fun schedule(wifiOnly: Boolean)
}

/** What a Download would hold, for the confirmation a big one asks first. */
data class DownloadPlan(
    val profileId: Long,
    val target: DownloadTarget,
    val name: String,
    val quality: DownloadQuality,
    val resolved: List<DownloadTitle>,
    val count: Int,
    /** Bytes beyond what the device already holds. */
    val bytes: Long,
    val big: Boolean,
)

/**
 * Downloads for every profile of this device: asks the server what a request holds, claims and
 * fetches each title in turn through [transfer], keeps standing requests in step, and syncs watch
 * state made offline. [snapshot] is the device's capability snapshot for a server.
 */
class Downloads(
    val store: DownloadStore,
    private val transfer: Transfer,
    private val scheduler: DownloadScheduler,
    private val snapshot: (Api) -> CapabilitySnapshot,
    private val pollMs: Long = POLL_MS,
) {
    private val meta = DownloadMeta(store)

    fun request(profileId: Long, target: DownloadTarget) = store.request(profileId, target)

    suspend fun plan(
        api: Api,
        profileId: Long,
        target: DownloadTarget,
        name: String,
        quality: DownloadQuality,
    ): DownloadPlan {
        val resolved = resolve(api, profileId, target, quality)
        val container = target.itemId == null || resolved.none { it.itemId == target.itemId }
        val kept = store.kept(profileId, container, resolved)
        val bytes = store.newBytes(kept)
        return DownloadPlan(
            profileId,
            target,
            name,
            quality,
            resolved,
            kept.size,
            bytes,
            store.isBig(profileId, kept.size, bytes),
        )
    }

    /**
     * Files the plan and starts fetching, then keeps its metadata, which a big request takes long
     * to fetch; [dontAskAgain] stops big requests asking first.
     */
    suspend fun add(api: Api, plan: DownloadPlan, dontAskAgain: Boolean = false) {
        if (dontAskAgain)
            store.setSettings(
                plan.profileId,
                store.settings(plan.profileId).copy(dontAskBig = true),
            )
        val request = store.put(plan.profileId, plan.target, plan.name, plan.quality, plan.resolved)
        schedule()
        meta.save(api, plan.profileId, request, plan.resolved)
    }

    fun remove(profileId: Long, target: DownloadTarget) {
        store.request(profileId, target)?.let { store.remove(it.id) }
        // The server converts for a claim until a run lets go of it.
        if (store.state.releases.isNotEmpty()) schedule()
    }

    fun retry(itemId: Long) {
        store.retry(itemId)
        schedule()
    }

    fun schedule() = scheduler.schedule(store.device.wifiOnly)

    /**
     * Claims ahead, then fetches every waiting title, one at a time, as [active], the server's
     * active profile read again for each title since it can change during a run, or as a kid that
     * asked for it. The server answers for another adult only once they are active, and for no
     * other profile while a kid is; a title nobody may claim now waits. On a network that holds
     * transfers back ([waitingFor]) it only claims. True asks to be run again later: the network
     * went, or the server was not reached.
     */
    suspend fun run(
        api: Api,
        active: () -> Long?,
        kids: Set<Long>,
        unmetered: Boolean,
        dataSaver: Boolean = false,
    ): Boolean {
        val waiting = waitingFor(store.device.wifiOnly, !unmetered, dataSaver)
        store.awaitNetwork(waiting ?: TitleState.Pending)
        val passed = mutableSetOf<Long>()
        val asked = mutableSetOf<Long>()
        while (true) {
            // Titles added during the run are claimed before the next transfer starts.
            claimAhead(api, active(), kids, asked)
            if (waiting != null) return false
            val title = store.next(passed) ?: return false
            val profile = claimant(title.itemId, active(), kids)
            if (profile == null) {
                passed += title.itemId
                continue
            }
            when (
                fetch(api, profile, title) { claimant(title.itemId, active(), kids) == profile }
            ) {
                Outcome.Done -> Unit
                Outcome.Later -> return true
                Outcome.Stop -> return false
            }
        }
    }

    /** [kids] are the household's kids, whom the server lets an active adult act for. */
    private fun claimant(itemId: Long, active: Long?, kids: Set<Long>): Long? {
        val asking = store.state.requests.filter { itemId in it.titles }.map { it.profileId }
        if (active != null && active in asking) return active
        return if (active in kids) null else asking.firstOrNull { it in kids }
    }

    /** [run]'s claims alone, for a network that holds transfers back. */
    suspend fun claim(api: Api, active: Long?, kids: Set<Long>) =
        claimAhead(api, active, kids, mutableSetOf())

    /**
     * Lets go of the claims of titles that went unfetched, then claims every waiting title that
     * fits and holds no claim, in one batch per profile and quality, in the order they are fetched,
     * so the server converts them back to back while earlier ones transfer. [asked] holds the
     * titles already sent this run. A title the batch could not claim fails as its own claim would;
     * a batch the server did not answer leaves its titles to be claimed at their turn.
     */
    private suspend fun claimAhead(
        api: Api,
        active: Long?,
        kids: Set<Long>,
        asked: MutableSet<Long>,
    ) {
        for (claim in store.state.releases) {
            try {
                api.call { downloads.deleteDownload(claim.profileId, claim.id) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Unreached, it is let go of on a later run; any answer of the server's ends it.
                if (ApiError.from(e) == null) return
            }
            store.released(claim)
        }
        val groups =
            store
                .fitting()
                .filter { it.claim == null && it.itemId !in asked }
                .mapNotNull { title -> claimant(title.itemId, active, kids)?.let { it to title } }
                .groupBy({ (profile, title) -> profile to title.quality }, { it.second })
        for ((key, titles) in groups) {
            val (profileId, quality) = key
            for (chunk in titles.chunked(BATCH_LIMIT)) {
                asked += chunk.map { it.itemId }
                val input =
                    DownloadBatchInput(
                        chunk.map { DownloadBatchTitle(it.itemId, it.editionId) },
                        quality,
                        snapshot(api),
                    )
                val results =
                    try {
                        api.call { downloads.createDownloads(profileId, input) }.results
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        return
                    }
                for (result in results) {
                    result.download?.let { store.claimed(result.itemId, Claim(it.id, profileId)) }
                    result.error?.let { error ->
                        val status =
                            when (error.code) {
                                ErrorCode.conflict -> CONFLICT
                                ErrorCode.not_found -> NOT_FOUND
                                else -> 0
                            }
                        store.setTitle(result.itemId) {
                            it.copy(
                                state = TitleState.Failed,
                                error = refusal(status, error.message),
                            )
                        }
                    }
                }
            }
        }
    }

    /** The title being fetched was removed, or the device signed out, so its transfer is moot. */
    private class Removed : Exception()

    private enum class Outcome {
        Done,
        Later,
        Stop,
    }

    /** [still] says whether [profileId] may still claim the title, which a profile switch ends. */
    private suspend fun fetch(
        api: Api,
        profileId: Long,
        title: StoredTitle,
        still: () -> Boolean,
    ): Outcome {
        val id = title.itemId
        store.setTitle(id) { it.copy(state = TitleState.Downloading, error = null) }
        var claimed: Claim? = null
        // The server's copy is only for fetching, kept while a fetch may resume against it.
        var release = true
        try {
            var tries = 0
            // Past its days, or claimed by a cleanup, the claim is made again, once.
            fun lost(e: Exception): Boolean {
                val gone = claimed ?: return false
                if (ApiError.from(e)?.status != NOT_FOUND || !still() || tries++ > 0) return false
                store.released(gone)
                claimed = null
                return true
            }
            while (true) {
                // A claim made as another profile is not this one's to use; claiming anew lets it
                // go.
                val held = store.title(id)?.claim?.takeIf { it.profileId == profileId }
                claimed = held
                var download: Download
                try {
                    download =
                        if (held != null) api.call { downloads.getDownload(profileId, held.id) }
                        else
                            claim(api, profileId, title).also {
                                val made = Claim(it.id, profileId)
                                claimed = made
                                store.claimed(id, made)
                            }
                    while (
                        download.status == Download.Status.queued ||
                            download.status == Download.Status.converting
                    ) {
                        delay(pollMs)
                        download = api.call { downloads.getDownload(profileId, download.id) }
                    }
                } catch (e: Exception) {
                    if (lost(e)) continue
                    throw e
                }
                if (download.status == Download.Status.failed) {
                    store.setTitle(id) {
                        it.copy(
                            state = TitleState.Failed,
                            error = download.error ?: "Couldn’t be converted.",
                        )
                    }
                    return Outcome.Done
                }
                val name = "$id.${download.container}"
                // A new claim may be another file; a partial one of the old must not be resumed.
                val etag = store.title(id)?.takeIf { it.file == name }?.etag
                store.setTitle(id) { it.copy(file = name, sizeBytes = download.sizeBytes) }
                // The file appears only once whole, so one held claim's file is fetched only once.
                if (held == null || !store.media(name).exists())
                    try {
                        transfer.fetch(
                            api.endpoint.resolve(download.url!!),
                            store.media(name),
                            etag,
                        ) { got, tag ->
                            // A transfer writing into an unlinked file only learns of a removal
                            // here.
                            if (store.title(id) == null) throw Removed()
                            store.progress(id, got, tag)
                        }
                    } catch (e: ApiError) {
                        if (lost(e)) continue
                        throw e
                    }
                val subtitles =
                    download.subtitles.map {
                        val file = "$id.${it.trackId}.vtt"
                        transfer.fetch(api.endpoint.resolve(it.url), store.media(file), null)
                        StoredSubtitle(it.trackId, it.language, it.title, it.isForced, file)
                    }
                val size = store.media(name).length()
                store.setTitle(id) {
                    it.copy(
                        state = TitleState.OnDevice,
                        sizeBytes = size,
                        receivedBytes = size,
                        subtitles = subtitles,
                    )
                }
                // Removed while it was being fetched: nothing covers what arrived.
                if (store.title(id) == null) {
                    store.media(name).delete()
                    subtitles.forEach { store.media(it.file).delete() }
                }
                return Outcome.Done
            }
        } catch (e: CancellationException) {
            release = false
            store.setTitle(id) { it.copy(state = TitleState.Pending) }
            throw e
        } catch (e: Exception) {
            val error = ApiError.from(e)
            return when {
                e is Removed -> Outcome.Done
                error == null -> {
                    release = false
                    // A full disk is no lost connection: running again would only fill it anew.
                    val full = e is IOException && store.freeBytes < 0
                    store.setTitle(id) {
                        it.copy(
                            state = if (full) TitleState.WaitingForSpace else TitleState.Pending,
                        )
                    }
                    if (full) Outcome.Stop else Outcome.Later
                }
                error.code == "unauthorized" -> {
                    store.wipe()
                    Outcome.Stop
                }
                // Another 401, as a proxy's or a file fetch's, waits as a locked device does.
                error.status == 401 || error.code == "device_locked" -> {
                    release = false
                    store.setTitle(id) { it.copy(state = TitleState.Pending) }
                    Outcome.Stop
                }
                // Refused as a profile no longer active: the title and its claim wait for it.
                !still() -> {
                    release = false
                    store.setTitle(id) { it.copy(state = TitleState.Pending) }
                    Outcome.Done
                }
                else -> {
                    store.setTitle(id) {
                        it.copy(
                            state = TitleState.Failed,
                            error = refusal(error.status, error.message),
                        )
                    }
                    Outcome.Done
                }
            }
        } finally {
            val done = claimed
            if (release && done != null) {
                runCatching { api.call { downloads.deleteDownload(profileId, done.id) } }
                store.released(done)
            }
        }
    }

    /** What a title the server would not claim says, by the status it answered for it. */
    private fun refusal(status: Int, message: String) =
        when (status) {
            CONFLICT -> "Can’t be kept on this device."
            NOT_FOUND -> "No longer available."
            else -> message
        }

    private suspend fun claim(api: Api, profileId: Long, title: StoredTitle): Download {
        val input = DownloadInput(title.itemId, title.quality, snapshot(api), title.editionId)
        return api.call { downloads.createDownload(profileId, input) }
    }

    private suspend fun resolve(
        api: Api,
        profileId: Long,
        target: DownloadTarget,
        quality: DownloadQuality,
    ): List<DownloadTitle> {
        val input = DownloadResolveInput(listOf(target), quality, snapshot(api))
        return api.call { downloads.resolveDownloads(profileId, input) }.titles
    }

    /**
     * While online as the profile: sends its offline watch changes oldest first, removes what it
     * may no longer see, and brings each request up to what it stands for now. The server's
     * not_found for the profile deletes what it keeps, its unauthorized everything; a proxy's 401
     * or 404 deletes nothing. What already waits is fetched whether or not the rest went through.
     */
    suspend fun sync(api: Api, profileId: Long) {
        try {
            try {
                pushWatch(api, profileId)
                val ids = store.titles(profileId).map { it.itemId }
                if (ids.isNotEmpty()) {
                    val answer =
                        ids.chunked(SYNC_LIMIT).flatMap { chunk ->
                            api.call {
                                    downloads.syncDownloads(profileId, DownloadSyncInput(chunk))
                                }
                                .remove
                        }
                    store.drop(profileId, answer)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                when (ApiError.from(e)?.code) {
                    "unauthorized" -> store.wipe()
                    "not_found" -> store.wipe(profileId)
                }
                return
            }
            refresh(api, profileId)
        } finally {
            schedule()
        }
    }

    /** Each change carries when it was made, so the server keeps whichever is later. */
    suspend fun pushWatch(api: Api, profileId: Long) {
        for (mark in store.unsynced(profileId)) {
            val at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(mark.atMs), ZoneOffset.UTC)
            val input = WatchStateInput(mark.watched, mark.positionMs, mark.editionId, at)
            val state =
                try {
                    api.call { profiles.putWatchState(profileId, mark.itemId, input) }
                } catch (e: Exception) {
                    // A title gone from the server takes its change with it.
                    if (ApiError.from(e)?.code != "not_found") throw e
                    store.synced(profileId, mark.copy(synced = true), mark.atMs)
                    continue
                }
            val answered =
                LocalWatch(
                    state.itemId,
                    state.positionMs,
                    state.watched,
                    state.updatedAt.toInstant().toEpochMilli(),
                    synced = true,
                    mark.editionId,
                )
            store.synced(profileId, answered, mark.atMs)
        }
    }

    /**
     * Re-resolves each of the profile's requests; one whose target is gone goes. A title the
     * request covers is kept again when its metadata never arrived.
     */
    private suspend fun refresh(api: Api, profileId: Long) {
        for (request in store.requests(profileId)) {
            val resolved =
                try {
                    resolve(api, profileId, request.target, request.quality)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (ApiError.from(e)?.code == "not_found") store.remove(request.id)
                    continue
                }
            val now = store.put(profileId, request.target, request.name, request.quality, resolved)
            val covered = now.titles.toSet()
            meta.save(
                api,
                profileId,
                now,
                resolved.filter { it.itemId in covered && !meta.has(it) },
            )
        }
    }

    companion object {
        const val POLL_MS = 3_000L
        const val SYNC_LIMIT = 5000
        /** The most titles the server claims in one batch. */
        const val BATCH_LIMIT = 5000
        private const val CONFLICT = 409
        private const val NOT_FOUND = 404

        /**
         * What transfers wait for on the network, or null when they may go. Only a metered network
         * holds them back: when the owner asks for Wi-Fi only, and always under Data Saver.
         */
        fun waitingFor(wifiOnly: Boolean, metered: Boolean, dataSaver: Boolean): TitleState? =
            when {
                !metered -> null
                wifiOnly -> TitleState.WaitingForWifi
                dataSaver -> TitleState.WaitingForDataSaver
                else -> null
            }
    }
}
