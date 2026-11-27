package enterprises.legitimate.rabbitears

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkDownloadSchedulerTest {
    private fun info(state: WorkInfo.State, vararg tags: String) =
        WorkInfo(UUID.randomUUID(), state, tags.toSet())

    private fun policy(rule: String, vararg chain: WorkInfo) =
        WorkDownloadScheduler.policy(chain.toList(), rule)

    @Test
    fun aRunInProgressUnderTheSameRuleLetsTheNewOneQueueBehindIt() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            policy("wifi", info(WorkInfo.State.RUNNING, "wifi")),
        )
    }

    @Test
    fun aRunBackingOffOrBlockedIsReplaced() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            policy("wifi", info(WorkInfo.State.ENQUEUED, "wifi")),
        )
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            policy("wifi", info(WorkInfo.State.BLOCKED, "wifi")),
        )
    }

    @Test
    fun aRunUnderAnotherRuleIsReplacedSoTheNewRuleAppliesAtOnce() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            policy("any", info(WorkInfo.State.RUNNING, "wifi")),
        )
    }

    @Test
    fun withNothingQueuedTheNewOneJustGoes() {
        assertEquals(ExistingWorkPolicy.REPLACE, policy("any"))
    }
}
