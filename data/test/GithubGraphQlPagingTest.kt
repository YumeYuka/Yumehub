package moe.yumeyuka.yumehub.data.repository

import androidx.paging.PagingSource.LoadParams.Append
import androidx.paging.PagingSource.LoadParams.Refresh
import androidx.paging.PagingSource.LoadResult.Page
import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubGraphQlPagingTest {
    @Test
    fun freshSnapshotRendersWithoutNetworkAndAppendUsesItsCursor() = runBlocking {
        val fixture = Fixture(System.currentTimeMillis())
        val source = fixture.source()

        val first = source.load(Refresh(null, 30, false)) as Page
        assertEquals(listOf("cached"), first.data)
        assertTrue(fixture.requests.isEmpty())
        assertNull(fixture.revalidate)

        val second = source.load(Append(checkNotNull(first.nextKey), 30, false)) as Page
        assertEquals(listOf("next"), second.data)
        assertEquals(listOf("old-cursor"), fixture.requests)
    }

    @Test
    fun staleSnapshotReturnsBeforeRevalidationAndReplacementDoesNotRefetch() = runBlocking {
        val fixture = Fixture(1L)
        val first = fixture.source().load(Refresh(null, 30, false)) as Page
        assertEquals(listOf("cached"), first.data)
        assertTrue(fixture.requests.isEmpty())

        val (updated, changed) = checkNotNull(fixture.revalidate).invoke()
        assertTrue(changed)
        assertEquals("new-cursor", updated.nextCursor)
        fixture.cached = updated

        val replacement = fixture.source().load(Refresh(null, 30, false)) as Page
        assertEquals(listOf("latest"), replacement.data)
        assertEquals(listOf<String?>(null), fixture.requests)
    }

    @Test
    fun failedBackgroundRevalidationPreservesCachedPageAndCursor() = runBlocking {
        val fixture = Fixture(1L, failRefresh = true)
        val source = fixture.source()
        val first = source.load(Refresh(null, 30, false)) as Page
        assertEquals(listOf("cached"), first.data)
        val result = runCatching { checkNotNull(fixture.revalidate).invoke() }
        assertTrue(result.exceptionOrNull() is IOException)

        val second = source.load(Append(checkNotNull(first.nextKey), 30, false)) as Page
        assertEquals(listOf("next"), second.data)
        assertEquals(listOf(null, "old-cursor"), fixture.requests)
    }

    private class Fixture(updatedAt: Long, val failRefresh: Boolean = false) {
        var cached =
            PagingSnapshotPage(listOf("cached"), null, 2, 30, updatedAt, nextCursor = "old-cursor")
        val requests = mutableListOf<String?>()
        var revalidate: (suspend () -> Pair<PagingSnapshotPage<String>, Boolean>)? = null

        fun source() =
            GithubGraphQlPagingSource(
                pageSize = 30,
                identity = { it },
                mapItem = { it },
                loader = {
                    { after ->
                        requests.add(after)
                        when (after) {
                            null -> {
                                if (failRefresh) throw IOException("offline")
                                GithubGraphQlPage(listOf("latest"), "new-cursor")
                            }
                            "old-cursor" -> GithubGraphQlPage(listOf("cached", "next"), null)
                            else -> error("Unexpected cursor: $after")
                        }
                    }
                },
                ensureCurrent = {},
                firstPageCache =
                    GithubGraphQlFirstPageCache(
                        restore = { cached },
                        save = { cached = it },
                        revalidate = { _, update -> revalidate = update },
                    ),
            )
    }
}
