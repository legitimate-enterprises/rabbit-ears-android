package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.Event
import enterprises.legitimate.rabbitears.api.models.EventKind
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemCredits
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.ItemRelated
import enterprises.legitimate.rabbitears.api.models.KidsContentBatchInput
import enterprises.legitimate.rabbitears.api.models.KidsContentInput
import enterprises.legitimate.rabbitears.api.models.Person
import enterprises.legitimate.rabbitears.api.models.SearchResult
import enterprises.legitimate.rabbitears.api.models.Verdict
import enterprises.legitimate.rabbitears.api.models.WatchState
import enterprises.legitimate.rabbitears.api.models.WatchStateBatchInput
import enterprises.legitimate.rabbitears.api.models.WatchStateInput
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Every page of a paged listing, following nextOffset until it is absent. */
suspend fun <T> pages(fetch: suspend (offset: Int?) -> Pair<List<T>, Int?>): List<T> {
    val all = mutableListOf<T>()
    var offset: Int? = null
    do {
        val (items, next) = fetch(offset)
        all += items
        offset = next
    } while (offset != null)
    return all
}

/** Loads the active profile's movies and TV and writes its watch state. */
class CatalogModel(
    val api: Api,
    val profileId: String,
    /** What is downloaded, shown in place of the server's catalog when it can't be reached. */
    val offline: OfflineLibrary? = null,
    hideKidsContent: Boolean = false,
) {
    var catalog by mutableStateOf(Catalog())
        private set

    /**
     * Whether the profile is an adult who hides kids' content, so the catalog lacks it and the Kids
     * pill reads it apart. Follows the profile as the server has it; see [hideKids].
     */
    var hideKidsContent by mutableStateOf(hideKidsContent)
        private set

    /** The kinds whose kids' titles are held apart, which every load reads again. */
    private var kidsKinds = emptySet<ItemKind>()

    var loaded by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var loading = false

    /** Counts loads, so only the latest one's answer lands. */
    private var loads = 0

    /**
     * Fetches every movie, show, season and episode the profile may see. shortcut: the whole
     * catalog is held in memory, its kinds asked for at once; move to server-side rows and badges
     * once libraries reach tens of thousands of episodes. A call while one is under way supersedes
     * it; the catalog shown stays until the latest one has arrived.
     */
    suspend fun load() {
        val mine = ++loads
        loading = true
        try {
            val fetch = suspend {
                coroutineScope {
                    val kinds =
                        listOf(ItemKind.movie, ItemKind.show, ItemKind.season, ItemKind.episode)
                            .map { async { listing(it) } }
                    val kids =
                        kidsKinds
                            .takeIf { hideKidsContent }
                            .orEmpty()
                            .map { async { listing(it, kidsContent = true) } }
                    val watch = async {
                        pages { offset ->
                            api.call {
                                    profiles.listWatchStates(
                                        profileId,
                                        limit = PAGE,
                                        offset = offset ?: 0,
                                    )
                                }
                                .let { it.items to it.nextOffset }
                        }
                    }
                    Catalog(kinds.awaitAll().flatten(), watch.await(), kids.awaitAll().flatten())
                }
            }
            val next = offline?.or({ catalog() }, fetch) ?: fetch()
            if (mine != loads) return
            catalog = next
            error = null
        } catch (e: Exception) {
            if (mine != loads) return
            error = AppError.from(e, AppError.SignedOut).message
        } finally {
            if (mine == loads) loading = false
        }
        loaded = true
    }

    /**
     * [load] for a pull to refresh, which a load under way may have answered from kept answers:
     * waits for it, then reads again.
     */
    suspend fun reload() {
        while (loading) delay(RELOAD_POLL_MS)
        load()
    }

    private suspend fun listing(kind: ItemKind, kidsContent: Boolean? = null) = pages { offset ->
        api.call {
                items.listItems(
                    kind = kind,
                    kidsContent = kidsContent,
                    limit = PAGE,
                    offset = offset ?: 0,
                )
            }
            .let { it.items to it.nextOffset }
    }

    /**
     * With Hide on, reads the kids' titles of [kind]'s grid for the Kids pill, a show with its
     * seasons and episodes, and holds them apart from the catalog; every later load reads them
     * again. The server lists them for the pill though it hides them elsewhere. Waits out a load
     * under way, which may not have asked for them.
     */
    suspend fun loadKids(kind: ItemKind) {
        if (!hideKidsContent) return
        val kinds = if (kind == ItemKind.show) SHOW_KINDS else listOf(kind)
        kidsKinds += kinds
        while (loading) delay(RELOAD_POLL_MS)
        try {
            val found = coroutineScope {
                kinds.map { async { listing(it, kidsContent = true) } }.awaitAll().flatten()
            }
            if (hideKidsContent) catalog = catalog.holding(kinds, found)
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    /**
     * Takes the profile's Hide setting as the server now has it. A change drops the kids' titles
     * held apart and reads the catalog again, since the server's answers differ; the same setting
     * does nothing.
     */
    suspend fun hideKids(on: Boolean) {
        if (on == hideKidsContent) return
        hideKidsContent = on
        kidsKinds = emptySet()
        catalog = catalog.withoutKids()
        reload()
    }

    /** Marks an item watched or unwatched; a show or season marks each episode. */
    suspend fun setWatched(watched: Boolean, item: Item) {
        val targets =
            if (item.kind == ItemKind.show || item.kind == ItemKind.season)
                catalog.episodes(item.id)
            else listOf(item)
        for (target in targets) write(target.id, WatchStateInput(watched)) ?: return
    }

    /**
     * Takes a movie or episode out of progress, so Play starts it from the top, keeping whether it
     * was watched and when it last played. With no server, a downloaded title's dismissal is kept
     * for the next sync.
     */
    suspend fun dismiss(item: Item) {
        try {
            val send = suspend { api.call { profiles.dismissWatchState(profileId, item.id) } }
            offline?.or(
                {
                    if (!has(item.id)) throw IOException()
                    dismiss(item.id)
                },
                send,
            ) ?: send()
            catalog.watchState(item.id)?.let { catalog = catalog.with(it.copy(positionMs = 0)) }
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    /** Marks a selection in one request; a show or season marks each episode. */
    suspend fun setWatched(watched: Boolean, items: List<Item>) {
        try {
            api.call {
                profiles.putWatchStates(
                    profileId,
                    WatchStateBatchInput(items.map { it.id }, watched),
                )
            }
            val now = OffsetDateTime.now()
            val states =
                items
                    .flatMap {
                        if (it.kind == ItemKind.movie || it.kind == ItemKind.episode) listOf(it)
                        else catalog.episodes(it.id)
                    }
                    .map {
                        val played = catalog.watchState(it.id)?.playCount ?: 0
                        WatchState(it.id, 0, watched, played, now, lastPlayedAt = now)
                    }
            catalog = catalog.with(states)
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    suspend fun setKidsContent(items: List<Item>, on: Boolean) {
        try {
            api.call { this.items.setKidsContents(KidsContentBatchInput(items.map { it.id }, on)) }
            catalog =
                items.fold(catalog) { c, it ->
                    c.with(it.copy(kidsContent = on, kidsContentOverride = on))
                }
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    /**
     * Takes the server's watch state after play, since the server decides when a position counts as
     * watched.
     */
    suspend fun refreshWatchState(id: String) {
        val local = offline?.watchState(id)
        runCatching {
            offline?.or({ local ?: throw IOException() }) {
                api.call { profiles.getWatchState(profileId, id) }
            } ?: api.call { profiles.getWatchState(profileId, id) }
        }
            .onSuccess { catalog = catalog.with(it) }
        // The server holds none, as after Mark as unwatched on another device.
        .onFailure { if (ApiError.from(it)?.status == 404) catalog = catalog.without(id) }
    }

    /**
     * Takes what a batch of live events changed: the watch state of each title named, or everything
     * for an empty batch or a library that changed.
     */
    suspend fun heard(batch: List<Event>) {
        if (batch.isEmpty() || batch.any { it.kind == EventKind.library }) return load()
        batch
            .filter { it.kind == EventKind.watch_state || it.kind == EventKind.playback }
            .mapNotNull { it.itemId }
            .distinct()
            .filter { catalog.item(it) != null }
            .forEach { refreshWatchState(it) }
    }

    suspend fun credits(id: String): ItemCredits? = runCatching {
        api.call { people.getItemCredits(id) }
    }
        .getOrNull()

    /** The person and every movie and show crediting them; null when either read fails. */
    suspend fun person(id: String): Pair<Person, List<Item>>? = runCatching {
        coroutineScope {
            val person = async { api.call { people.getPerson(id) } }
            val titles = pages { offset ->
                api.call { items.listItems(personId = id, limit = PAGE, offset = offset ?: 0) }
                    .let { it.items to it.nextOffset }
            }
            person.await() to titles
        }
    }
        .getOrNull()

    /** A title's related movies and its neighbors in play order; null when the read fails. */
    suspend fun related(id: String): ItemRelated? = runCatching {
        api.call { items.getItemRelated(id) }
    }
        .getOrNull()

    /**
     * The tracks of [fileId], else of the item's first media file, for the details lines; null when
     * there is none.
     */
    suspend fun media(id: String, fileId: String? = null): MediaSummary? = runCatching {
        if (fileId != null) MediaSummary(api.call { media.getMediaFile(fileId) })
        else
            api.call { media.listMediaFiles(id, limit = 1) }
                .items
                .firstOrNull()
                ?.let(::MediaSummary)
    }
        .getOrNull()

    /**
     * How play would go on this device now, from the snapshot the player sends; null when it cannot
     * tell.
     */
    suspend fun verdict(id: String, facts: DeviceFacts): Verdict? = runCatching {
        val snapshot = Snapshot.snapshot(facts, api.endpoint, QualityChoice.Auto)
        api.call { playback.checkPlayability(id, snapshot) }.verdict
    }
        .getOrNull()

    /** Everything matching the query, as the server filters it for the profile; null when blank. */
    suspend fun search(query: String): SearchResult? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return api.call { items.search(q, SEARCH_LIMIT) }
    }

    /**
     * Search's answer for [query] once typing pauses, handed to [show] unless a newer query
     * cancelled this one, even while its request was still out.
     */
    suspend fun searchTyped(query: String, show: (SearchResult?) -> Unit) {
        delay(SEARCH_PAUSE_MS)
        val found = if (query.isBlank()) null else runCatching { search(query) }.getOrNull()
        currentCoroutineContext().ensureActive()
        show(found)
    }

    /** Overrides whether a movie or show is kids' content. */
    suspend fun setKidsContent(item: Item, on: Boolean) {
        try {
            catalog = catalog.with(api.call { items.setKidsContent(item.id, KidsContentInput(on)) })
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    /** Drops the override, so the rating decides again. */
    suspend fun useRating(id: String) {
        try {
            api.call { items.clearKidsContent(id) }
            catalog = catalog.with(api.call { items.getItem(id) })
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    /** With no server, a downloaded title's change is kept for the next sync. */
    private suspend fun write(id: String, input: WatchStateInput): Unit? =
        try {
            val put = suspend { api.call { profiles.putWatchState(profileId, id, input) } }
            catalog =
                catalog.with(
                    offline?.or(
                        {
                            if (!has(id)) throw IOException()
                            record(id, input.positionMs ?: 0, input.watched)
                            watchState(id)!!
                        },
                        put,
                    ) ?: put(),
                )
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
            null
        }

    companion object {
        const val PAGE = 200
        const val SEARCH_LIMIT = 30
        private const val SEARCH_PAUSE_MS = 300L
        private const val RELOAD_POLL_MS = 25L
        private val SHOW_KINDS = listOf(ItemKind.show, ItemKind.season, ItemKind.episode)
    }
}
