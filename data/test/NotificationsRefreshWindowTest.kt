package moe.yumeyuka.yumehub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationsRefreshWindowTest {
    @Test
    fun invalidatedSnapshotRebuildsItsWindowWithoutTheOldCursor() {
        val invalidated = snapshot.copy(pagingPaused = true)

        assertEquals(
            NotificationRefreshWindow(fromPage = 2, throughPage = 3, previous = null),
            invalidated.refreshWindow(updatedAt = 1L),
        )
    }

    @Test
    fun staleButValidSnapshotKeepsItsDisplayedWindow() {
        assertEquals(
            NotificationRefreshWindow(fromPage = 2, throughPage = 3, previous = snapshot),
            snapshot.refreshWindow(updatedAt = 1L),
        )
        assertNull(snapshot.refreshWindow(updatedAt = 0L).previous)
    }

    private companion object {
        val snapshot =
            NotificationsSnapshot(
                notifications = emptyList(),
                loadedFromPage = 2,
                loadedThroughPage = 3,
                nextCursor = "cursor-3",
                cursorHistory = listOf("cursor-1", "cursor-2", "cursor-3"),
                pageItemCounts = listOf(0, 0),
            )
    }
}
