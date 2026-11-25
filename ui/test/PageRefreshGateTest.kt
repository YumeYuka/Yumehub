package moe.yumeyuka.yumehub.ui.navigation

import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.DataFreshness
import org.junit.Assert.assertEquals
import org.junit.Test

class PageRefreshGateTest {
    @Test
    fun pollingAfterAnEarlyReactivationWaitsOnlyForTheRemainingInterval() {
        val gate = PageRefreshGate()
        val interval = DataFreshness.ActiveMillis
        val requestTimes = mutableListOf<Long>()
        val versions = emptyMap<DataDomain, Long>()

        fun check(now: Long) {
            if (gate.acquire(now, versions)) requestTimes.add(now)
        }

        check(0)
        val reactivatedAt = interval - 1
        check(reactivatedAt)
        check(reactivatedAt + gate.millisUntilNextCheck(reactivatedAt))
        assertEquals(listOf(0L, interval), requestTimes)
    }

    @Test
    fun aDomainChangeRevalidatesImmediatelyAndStartsANewInterval() {
        val gate = PageRefreshGate()
        val versions = mapOf(DataDomain.Notifications to 1L)
        assertEquals(true, gate.acquire(0, emptyMap()))
        assertEquals(true, gate.acquire(100, versions))
        assertEquals(DataFreshness.ActiveMillis - 1, gate.millisUntilNextCheck(101))
        assertEquals(false, gate.acquire(101, versions))
    }
}
