package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FilterOptionsCacheTest {
    @Test
    fun concurrentEquivalentKeysShareOneLoad() = runBlocking {
        val cache = FilterOptionsCache()
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<CollaborationFilterOptions>()
        val expected = CollaborationFilterOptions(repositoryId = "repository-id")
        var loadCount = 0

        val first =
            async {
                cache.get("User", "Owner", "Repo", 1) {
                    loadCount++
                    loadStarted.complete(Unit)
                    releaseLoad.await()
                }
            }
        loadStarted.await()
        val second =
            async {
                cache.get("user", "owner", "repo", 1) {
                    loadCount++
                    releaseLoad.await()
                }
            }
        yield()

        assertEquals(1, loadCount)
        releaseLoad.complete(expected)
        assertSame(expected, first.await())
        assertSame(expected, second.await())
    }
}
