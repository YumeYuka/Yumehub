package moe.yumeyuka.yumehub.ui.navigation

import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.DataFreshness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRefreshGateTest {
    @Test
    fun viewModelInitialLoadDoesNotSuppressInvalidationOrExpiryOnReturn() {
        val gate = PageRefreshGate()
        val updated = mapOf(DataDomain.Content to 1L)
        assertFalse(gate.acquire(0L, emptyMap(), refreshInitially = false))
        assertTrue(gate.acquire(1L, updated, refreshInitially = false))
        assertFalse(gate.acquire(2L, updated, refreshInitially = false))
        assertTrue(gate.acquire(1L + DataFreshness.ActiveMillis, updated, refreshInitially = false))
    }
}
