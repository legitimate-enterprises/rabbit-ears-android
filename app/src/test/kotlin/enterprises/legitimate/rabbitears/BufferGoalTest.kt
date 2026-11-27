package enterprises.legitimate.rabbitears

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class BufferGoalTest {
    private val control = BufferGoal().apply { onPrepared(PlayerId.UNSET) }
    private val timeline =
        SinglePeriodTimeline(C.TIME_UNSET, true, false, false, null, MediaItem.EMPTY)

    private fun loads(bufferedSeconds: Long) =
        control.shouldContinueLoading(
            LoadControl.Parameters(
                PlayerId.UNSET,
                timeline,
                MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
                0,
                bufferedSeconds * 1_000_000,
                1f,
                true,
                false,
                C.TIME_UNSET,
                C.TIME_UNSET,
            ),
        )

    @Test
    fun aStreamsGoalIsHeldAheadAndWithoutOneTheDefault() {
        control.goalMs = 120_000
        assertTrue(loads(100))
        assertFalse(loads(120))
        control.goalMs = 0
        assertTrue(loads(40))
        assertFalse(loads(50))
    }
}
