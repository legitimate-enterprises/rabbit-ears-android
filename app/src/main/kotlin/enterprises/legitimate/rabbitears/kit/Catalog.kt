package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Genre
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.ItemRelated
import enterprises.legitimate.rabbitears.api.models.RelatedCollection
import enterprises.legitimate.rabbitears.api.models.WatchState
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import java.time.OffsetDateTime

enum class GridFilter(val label: String) {
    All("All"),
    Unwatched("Unwatched"),
    InProgress("In progress"),
    Kids("Kids"),
}

/** A sort or filter stored by [name]; [default] when none is stored or it no longer exists. */
inline fun <reified E : Enum<E>> enumNamed(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

/** One entry of Home's In progress: a catalog title or a YouTube video. */
sealed interface Resumable {
    data class Title(val item: Item) : Resumable

    data class Video(val video: YouTubeVideo) : Resumable
}

/**
 * Home's top: the most recent in-progress entry, none when nothing is, and the others beside it.
 */
data class HomeLead(val hero: Resumable?, val also: List<Resumable>)

/** What Play starts: a title, where in it, and the edition a resume keeps. */
data class Start(val itemId: String, val positionMs: Long, val editionId: String? = null)

enum class GridSort(val label: String) {
    RecentlyAdded("Recently added"),
    Title("Title"),
    Year("Released"),
}

/**
 * A profile's whole catalog and watch state, with every derived list the screens show. The server
 * applied the profile's ceilings before any of it arrived. [kids] are the kids' titles an adult who
 * hides them asked for with the Kids pill: found by id and shown under that pill alone, so no other
 * list, count or Home row holds them.
 */
class Catalog(
    items: List<Item> = emptyList(),
    watch: List<WatchState> = emptyList(),
    kids: List<Item> = emptyList(),
) {
    private val items = items.associateBy { it.id }
    private val kids = kids.associateBy { it.id }
    private val watch = watch.associateBy { it.itemId }.toMutableMap()
    private val childIds: Map<String, List<String>> =
        (this.items.values + this.kids.values)
            .filter { it.parentId != null }
            .distinctBy { it.id }
            .groupBy { it.parentId!! }
            .mapValues { (_, children) -> children.sortedWith(numberOrder).map { it.id } }

    private fun copy(
        items: Collection<Item> = this.items.values,
        watch: Collection<WatchState> = this.watch.values,
        kids: Collection<Item> = this.kids.values,
    ) = Catalog(items.toList(), watch.toList(), kids.toList())

    /** A copy with one item's watch state replaced, so Compose sees a new value. */
    fun with(state: WatchState): Catalog = copy(watch = watch.values + state)

    fun without(id: String): Catalog = copy(watch = (watch - id).values)

    fun with(states: List<WatchState>): Catalog =
        copy(watch = (watch + states.associateBy { it.itemId }).values)

    fun with(item: Item): Catalog = copy(items = items.values.filter { it.id != item.id } + item)

    /** A copy whose kids' titles of [kinds] are [found], those of other kinds kept. */
    fun holding(kinds: Collection<ItemKind>, found: List<Item>): Catalog =
        copy(kids = kids.values.filter { it.kind !in kinds } + found)

    fun withoutKids(): Catalog = copy(kids = emptyList())

    fun item(id: String): Item? = items[id] ?: kids[id]

    fun watchState(id: String): WatchState? = watch[id]

    fun children(id: String): List<Item> = childIds[id].orEmpty().mapNotNull { item(it) }

    fun parent(item: Item): Item? = item.parentId?.let { item(it) }

    fun show(item: Item): Item? =
        generateSequence(item) { parent(it) }.firstOrNull { it.kind == ItemKind.show }

    /** Every episode under a show or season, in viewing order with specials last. */
    fun episodes(id: String): List<Item> {
        val item = item(id) ?: return emptyList()
        return when (item.kind) {
            ItemKind.episode -> listOf(item)
            ItemKind.movie -> emptyList()
            else ->
                children(id).flatMap {
                    if (it.kind == ItemKind.episode) listOf(it) else episodes(it.id)
                }
        }
    }

    /** The episode page's Up next: the two episodes after [episode] in its show's order. */
    fun nextEpisodes(episode: Item): List<Item> {
        val all = show(episode)?.let { episodes(it.id) }.orEmpty()
        val at = all.indexOfFirst { it.id == episode.id }
        return if (at < 0) emptyList() else all.drop(at + 1).take(2)
    }

    fun isWatched(id: String) = watch[id]?.watched == true

    fun isInProgress(id: String) = watch[id]?.let { it.positionMs > 0 } == true

    fun unwatchedCount(id: String): Int {
        val item = item(id) ?: return 0
        if (item.kind == ItemKind.movie || item.kind == ItemKind.episode)
            return if (isWatched(id)) 0 else 1
        return episodes(id).count { !isWatched(it.id) }
    }

    fun durationMs(id: String): Long? = item(id)?.metadata?.runtimeMinutes?.let { it * 60_000L }

    fun progress(id: String): Double? {
        if (!isInProgress(id)) return null
        val duration = durationMs(id)?.takeIf { it > 0 } ?: return null
        return minOf(1.0, watch[id]!!.positionMs.toDouble() / duration)
    }

    fun remainingMs(id: String): Long? {
        if (!isInProgress(id)) return null
        val duration = durationMs(id) ?: return null
        return maxOf(0, duration - watch[id]!!.positionMs)
    }

    val continueWatching: List<Item>
        get() =
            watch.values
                .filter { isInProgress(it.itemId) && items[it.itemId] != null }
                .sortedByDescending { it.lastPlayedAt ?: EPOCH }
                .mapNotNull { items[it.itemId] }

    /** Home's In progress: [continueWatching] and the in-progress [videos], latest played first. */
    fun continueWatching(videos: List<YouTubeVideo>): List<Resumable> =
        (continueWatching.map { Resumable.Title(it) to watch[it.id]?.lastPlayedAt } +
                videos.map { Resumable.Video(it) to it.lastPlayedAt })
            .sortedByDescending { it.second ?: EPOCH }
            .map { it.first }

    fun homeLead(videos: List<YouTubeVideo>): HomeLead =
        continueWatching(videos).let { HomeLead(it.firstOrNull(), it.drop(1)) }

    /** For each show whose latest play finished an episode, the episode after it. */
    val nextUp: List<Item>
        get() =
            items.values
                .filter { it.kind == ItemKind.show }
                .mapNotNull { show ->
                    val episodes = episodes(show.id).filter { it.seasonNumber != 0 }
                    if (episodes.any { isInProgress(it.id) }) return@mapNotNull null
                    val latest =
                        latestPlayed(episodes)?.takeIf { isWatched(it.id) }
                            ?: return@mapNotNull null
                    val next =
                        episodes.drop(episodes.indexOf(latest) + 1).firstOrNull {
                            !isWatched(it.id)
                        } ?: return@mapNotNull null
                    next to (watch[latest.id]?.lastPlayedAt ?: EPOCH)
                }
                .sortedByDescending { it.second }
                .map { it.first }

    val recentlyAdded: List<Item>
        get() =
            items.values
                .filter { it.kind == ItemKind.movie || it.kind == ItemKind.show }
                .sortedByDescending { it.addedAt }
                .take(20)

    /** The episode Play or Resume starts for a show or season. */
    fun resumeTarget(id: String): Item? {
        val all = episodes(id)
        val episodes = all.filter { it.seasonNumber != 0 }.ifEmpty { all }
        latestPlayed(episodes.filter { isInProgress(it.id) })?.let {
            return it
        }
        latestPlayed(episodes.filter { isWatched(it.id) })?.let { latest ->
            episodes
                .drop(episodes.indexOf(latest) + 1)
                .firstOrNull { !isWatched(it.id) }
                ?.let {
                    return it
                }
        }
        return episodes.firstOrNull { !isWatched(it.id) } ?: episodes.firstOrNull()
    }

    /**
     * What Play or Resume starts for [item]: a movie or episode itself, a show or season its
     * [resumeTarget]; null for a show without episodes.
     */
    fun start(item: Item): Start? {
        val target =
            if (item.kind == ItemKind.movie || item.kind == ItemKind.episode) item
            else resumeTarget(item.id) ?: return null
        val watch = watchState(target.id)?.takeIf { isInProgress(target.id) }
        return Start(target.id, watch?.positionMs ?: 0, watch?.editionId)
    }

    /**
     * The watched fraction a tile of [item] shows: that of what its Play resumes, null when Play
     * starts from the beginning.
     */
    fun resumeProgress(item: Item): Double? = start(item)?.let { progress(it.itemId) }

    fun grid(
        kind: ItemKind,
        filter: GridFilter = GridFilter.All,
        sort: GridSort = GridSort.RecentlyAdded,
        genre: String? = null,
    ): List<Item> =
        (if (filter == GridFilter.Kids) items.values + kids.values else items.values)
            .distinctBy { it.id }
            .filter { it.kind == kind }
            .filter { item -> genre == null || item.genres.orEmpty().any { it.id == genre } }
            .filter {
                when (filter) {
                    GridFilter.All -> true
                    GridFilter.Unwatched -> unwatchedCount(it.id) > 0
                    GridFilter.InProgress -> isInProgress(it.id) || showStarted(it)
                    GridFilter.Kids -> it.kidsContent == true
                }
            }
            .sortedWith(
                when (sort) {
                    GridSort.RecentlyAdded -> compareByDescending { it.addedAt }
                    GridSort.Title -> compareBy(String.CASE_INSENSITIVE_ORDER) { sortTitle(it) }
                    GridSort.Year -> compareByDescending { year(it) ?: 0 }
                },
            )

    fun genres(kind: ItemKind): List<Genre> =
        items.values
            .filter { it.kind == kind }
            .flatMap { it.genres.orEmpty() }
            .distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** The grid caption, runtime first: `2h 46m · 2024`, `50m · 2 seasons`. */
    fun caption(item: Item): String {
        val parts = mutableListOf<String>()
        item.metadata?.runtimeMinutes?.takeIf { it > 0 }?.let { parts += Format.runtime(it) }
        if (item.kind == ItemKind.show) parts += Format.plural(seasons(item.id).size, "season")
        else year(item)?.let { parts += it.toString() }
        return parts.joinToString(" · ")
    }

    /** The name a tile shows: the show's for an episode. */
    fun heading(item: Item): String =
        title(if (item.kind == ItemKind.episode) show(item) ?: item else item)

    /** `1h 12m left`, or `S3 E2 · 18m left` for an episode; [named] adds the episode's title. */
    fun leftCaption(item: Item, named: Boolean = false): String =
        listOfNotNull(
                Format.episodeCode(item).takeIf { item.kind == ItemKind.episode },
                title(item).takeIf { named && item.kind == ItemKind.episode },
                remainingMs(item.id)?.let(Format::remaining),
            )
            .joinToString(" · ")

    /** `S4 E1 · 49m`, as Next up captions an episode. */
    fun episodeCaption(item: Item): String =
        listOfNotNull(Format.episodeCode(item), item.metadata?.runtimeMinutes?.let(Format::runtime))
            .joinToString(" · ")

    /** What a screen reader says of a tile that plays at once: `Play Severance, S4 E1`. */
    fun playLabel(item: Item): String =
        "Play ${heading(item)}" +
            if (item.kind == ItemKind.episode) ", ${Format.episodeCode(item)}" else ""

    /** A show's seasons without specials. */
    fun seasons(showId: String): List<Item> =
        children(showId).filter { it.kind == ItemKind.season && it.seasonNumber != 0 }

    private fun showStarted(item: Item): Boolean {
        if (item.kind != ItemKind.show) return false
        val episodes = episodes(item.id)
        return episodes.any { isInProgress(it.id) } ||
            (episodes.any { isWatched(it.id) } && episodes.any { !isWatched(it.id) })
    }

    private fun latestPlayed(items: List<Item>): Item? =
        items.filter { watch[it.id] != null }.maxByOrNull { watch[it.id]?.lastPlayedAt ?: EPOCH }

    companion object {
        private val EPOCH: OffsetDateTime = OffsetDateTime.parse("1970-01-01T00:00:00Z")

        fun title(item: Item): String = item.metadata?.title ?: item.parsedTitle

        /**
         * What a movie or show sorts by: the server's sort title, else the title shown. Articles
         * are the server's to drop, never this client's.
         */
        fun sortTitle(item: Item): String = item.sortTitle ?: title(item)

        fun year(item: Item): Int? = item.parsedYear ?: item.metadata?.releaseDate?.year

        /** A movie's collection rows, in the server's order, each holding at least one film. */
        fun related(answer: ItemRelated?): List<RelatedCollection> =
            answer?.collections.orEmpty().filter { it.items.isNotEmpty() }

        /** Seasons and episodes by number with specials (season 0) last, then title. */
        private val numberOrder: Comparator<Item> =
            compareBy<Item>(
                    {
                        if (it.kind == ItemKind.season && it.seasonNumber == 0) Int.MAX_VALUE
                        else it.seasonNumber ?: 0
                    },
                    { if (it.kind == ItemKind.episode) it.episodeNumber ?: 0 else 0 },
                )
                .thenBy { title(it) }
    }
}
