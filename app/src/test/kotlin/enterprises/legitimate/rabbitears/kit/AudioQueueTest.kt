package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.MusicSong
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

fun song(
    id: String,
    title: String = "Song $id",
    album: String = "50",
    durationMs: Long? = 200_000,
) =
    MusicSong(
        id,
        "1",
        album,
        "In Rainbows",
        "40",
        "40",
        "Radiohead",
        title,
        true,
        trackNumber = id.toInt(),
        durationMs = durationMs,
        codec = "flac",
    )

class AudioQueueTest {
    private val songs = (1..5).map { song("$it") }

    private fun ids(q: AudioQueue) = q.entries.map { it.song.id }

    @Test
    fun startsAtTheChosenSongWithTheRestUpNext() {
        val q = AudioQueue.of(songs, start = 2, source = "In Rainbows")
        assertEquals("3", q.current?.song?.id)
        assertEquals(listOf("4", "5"), q.upNext.map { it.song.id })
    }

    @Test
    fun aShuffledStartPlaysAnySongFirst() {
        val queues =
            (1..60).map { AudioQueue.of(songs, source = "x", shuffled = true, random = Random(it)) }
        assertTrue(queues.all { it.shuffled && it.index == 0 && it.entries.size == songs.size })
        assertTrue(queues.mapNotNull { it.current?.song?.id }.toSet().size > 1)
        assertNull(AudioQueue.of(emptyList(), source = "x", shuffled = true).current)
    }

    @Test
    fun shuffleKeepsTheCurrentSongFirstAndOffRestoresTheOrder() {
        val q = AudioQueue.of(songs, start = 2, source = "x").shuffle(true, Random(1))
        assertEquals("3", q.current?.song?.id)
        assertEquals(0, q.index)
        assertEquals(songs.map { it.id }.toSet(), ids(q).toSet())
        val off = q.shuffle(false)
        assertEquals(listOf("1", "2", "3", "4", "5"), ids(off))
        assertEquals("3", off.current?.song?.id)
    }

    @Test
    fun movingUpNextRenumbersSoUnshuffleKeepsTheNewOrder() {
        val q = AudioQueue.of(songs, start = 1, source = "x").move(0, 2)
        assertEquals(listOf("1", "2", "4", "5", "3"), ids(q))
        assertEquals(ids(q), ids(q.shuffle(false)))
        assertSame(q, q.move(0, 9))
    }

    @Test
    fun removeAndClearTouchOnlyUpNext() {
        val q = AudioQueue.of(songs, start = 1, source = "x")
        assertSame(q, q.remove(q.entries[0].id))
        assertSame(q, q.remove(q.entries[1].id))
        assertEquals(listOf("1", "2", "3", "5"), ids(q.remove(q.entries[3].id)))
        assertEquals(listOf("1", "2"), ids(q.clearUpNext()))
    }

    @Test
    fun nextStopsAtTheEndUnlessItRepeats() {
        val last = AudioQueue.of(songs, start = 4, source = "x")
        assertNull(last.next(auto = true))
        assertEquals(0, last.copy(repeatMode = RepeatMode.All).next(auto = true)?.index)
        val one = last.copy(repeatMode = RepeatMode.One)
        assertEquals(4, one.next(auto = true)?.index)
        assertEquals(0, one.next(auto = false)?.index)
        assertEquals(3, last.previous().index)
        assertEquals(0, AudioQueue.of(songs, source = "x").previous().index)
        assertEquals(4, AudioQueue.of(songs, source = "x", random = Random(0)).jump(4)?.index)
        assertNull(AudioQueue().next(auto = false))
    }
}
