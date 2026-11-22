package moe.yumeyuka.yumehub.data.markdown

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHtmlCacheTest {
    private val key = MarkdownHtmlKey(0, "Aa", null, null, null, false, null, false)

    @Test
    fun collidingDocumentsProceedIndependentlyAndCancelledWaiterDoesNotSplitLock() = runBlocking {
        val locks = MarkdownRequestLocks()
        val collision = key.copy(sourceVersion = "BB")
        assertEquals(key.hashCode(), collision.hashCode())
        val release = CompletableDeferred<Unit>()
        val owner =
            launch(start = CoroutineStart.UNDISPATCHED) { locks.withKey(key) { release.await() } }
        try {
            withTimeout(5_000) { locks.withKey(collision) {} }
            val cancelled =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    locks.withKey(key) { error("unexpected entry") }
                }
            cancelled.cancelAndJoin()
            var entered = false
            val next =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    locks.withKey(key) { entered = true }
                }
            assertFalse(entered)
            release.complete(Unit)
            next.join()
            assertTrue(entered)
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun renewedSessionOrTokenRoundTripRejectsOldContent() {
        val cache = MarkdownHtmlCache("viewer", "first", 1)
        assertTrue(cache.put(key, "private"))
        cache.observeSession(2)
        assertNull(cache.get(key))
        assertFalse(cache.put(key, "late"))
        val fresh = key.copy(generation = cache.generation.value)
        assertTrue(cache.put(fresh, "private"))
        cache.observeToken("second")
        cache.observeToken("first")
        assertFalse(cache.put(fresh, "late"))
        assertNull(cache.get(fresh))
    }

    @Test
    fun sizeUsesUtf8AndEntriesExpireAtTheOriginalDeadline() {
        var now = 0L
        val cache = MarkdownHtmlCache("viewer", "first", 1) { now }
        val atLimit = "é".repeat(MaxHtmlBytes / 2)
        assertTrue(cache.put(key, atLimit))
        assertThrows(GithubMarkdownException::class.java) { cache.put(key, atLimit + "é") }
        now = 59_999_999_999L
        assertEquals(atLimit, cache.get(key))
        now++
        assertNull(cache.get(key))
    }
}
