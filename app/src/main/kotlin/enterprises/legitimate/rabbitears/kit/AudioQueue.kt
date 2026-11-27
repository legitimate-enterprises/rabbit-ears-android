package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.MusicSong
import kotlin.random.Random

enum class RepeatMode {
    Off,
    All,
    One,
}

/**
 * The music queue: what has played, what plays now and Up next. It lives in the client; the server
 * sees one playback session per song. Every change returns a new queue, so Compose sees it.
 */
data class AudioQueue(
    val entries: List<Entry> = emptyList(),
    val index: Int = 0,
    val shuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    /** What it plays from, as in "Playing from In Rainbows". */
    val source: String = "",
    /** The music playlist it plays from, sent with each song's playback. */
    val playlistId: Long? = null,
) {
    /** [id] is unique within the queue, since a song may be queued twice. */
    data class Entry(
        val id: Int,
        val song: MusicSong,
        /** Place in the unshuffled order. */
        val order: Int,
    )

    val current: Entry?
        get() = entries.getOrNull(index)

    val upNext: List<Entry>
        get() = entries.drop(index + 1)

    /**
     * Shuffling reorders only Up next; turning it off puts every song back in its unshuffled place
     * around the current one.
     */
    fun shuffle(on: Boolean, random: Random = Random): AudioQueue {
        val now = current ?: return copy(shuffled = on)
        if (on)
            return copy(
                entries = listOf(now) + entries.filter { it.id != now.id }.shuffled(random),
                index = 0,
                shuffled = true,
            )
        val sorted = entries.sortedBy { it.order }
        return copy(
            entries = sorted,
            index = sorted.indexOfFirst { it.id == now.id }.coerceAtLeast(0),
            shuffled = false,
        )
    }

    /** Moves one Up next entry, with positions counted within Up next. */
    fun move(from: Int, to: Int): AudioQueue {
        val upcoming = upNext.toMutableList()
        if (from !in upcoming.indices || to !in upcoming.indices) return this
        upcoming.add(to, upcoming.removeAt(from))
        val all = entries.take(index + 1) + upcoming
        return copy(entries = if (shuffled) all else all.mapIndexed { i, e -> e.copy(order = i) })
    }

    /** Removes an Up next entry; history and the current song stay. */
    fun remove(id: Int): AudioQueue {
        val at = entries.indexOfFirst { it.id == id }
        return if (at <= index) this else copy(entries = entries.filterIndexed { i, _ -> i != at })
    }

    fun clearUpNext(): AudioQueue = copy(entries = entries.take(index + 1))

    /**
     * The queue moved on; null at the end of a queue that does not repeat. Repeat one holds the
     * song only when it ends on its own.
     */
    fun next(auto: Boolean): AudioQueue? {
        current ?: return null
        if (auto && repeatMode == RepeatMode.One) return this
        if (index + 1 < entries.size) return copy(index = index + 1)
        return if (repeatMode == RepeatMode.Off) null else copy(index = 0)
    }

    /** Steps back, wrapping to the end only when the queue repeats. */
    fun previous(): AudioQueue =
        when {
            index > 0 -> copy(index = index - 1)
            repeatMode != RepeatMode.Off -> copy(index = maxOf(0, entries.size - 1))
            else -> this
        }

    /** Plays an Up next entry now; the ones before it are skipped into history. */
    fun jump(id: Int): AudioQueue? =
        entries.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { copy(index = it) }

    companion object {
        /**
         * Starts at [start]; shuffled, at a song picked at random, and the rest follow at random.
         */
        fun of(
            songs: List<MusicSong>,
            start: Int = 0,
            source: String,
            playlistId: Long? = null,
            shuffled: Boolean = false,
            random: Random = Random,
        ): AudioQueue =
            AudioQueue(
                    songs.mapIndexed { i, song -> Entry(i, song, i) },
                    when {
                        shuffled && songs.isNotEmpty() -> random.nextInt(songs.size)
                        start in songs.indices -> start
                        else -> 0
                    },
                    source = source,
                    playlistId = playlistId,
                )
                .let { if (shuffled) it.shuffle(true, random) else it }
    }
}
