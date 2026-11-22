package moe.yumeyuka.yumehub.feature.workdetail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingDraftWritesTest {
    @Test
    fun closingAnOldPageDoesNotOverwriteANewerDraftOrRecreateSubmittedDrafts() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val writes = mutableListOf<Pair<String, String>>()
        val pending = PendingDraftWrites(scope) { key, text -> writes += key to text }
        try {
            pending.flushAll()
            pending.schedule("comment", "first")
            pending.schedule("comment", "last")
            pending.schedule("review", "sent")
            pending.discard("review")
            pending.schedule("edit/7", "")
            pending.flush("edit/7")
            writes += "edit/7" to "updated by another page"
            scope.cancel()
            pending.flushAll()
            pending.flushAll()
            assertEquals(
                listOf("edit/7" to "", "edit/7" to "updated by another page", "comment" to "last"),
                writes,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun savedDraftIsNotWrittenAgainWhenLeaving() = runBlocking {
        val writes = mutableListOf<String>()
        val pending = PendingDraftWrites(this) { _, text -> writes += text }
        pending.schedule("comment", "draft")
        coroutineContext[Job]!!.children.toList().joinAll()
        pending.flushAll()
        assertEquals(listOf("draft"), writes)
    }
}
