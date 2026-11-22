package moe.yumeyuka.yumehub.data.repository

import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsWindowTest {
    private val firstWindow =
        NotificationsSnapshot(
            notifications = listOf("a", "b", "c").map { NotificationDto(id = it) },
            loadedThroughPage = 3,
            nextCursor = "c3",
            cursorHistory = listOf("c1", "c2", "c3"),
            pageItemCounts = listOf(2, 0, 1),
        )

    @Test
    fun rollingWindowDropsOnlyTheOldestPageAndKeepsTheNewCursor() {
        val appended = page("d")
        val result = firstWindow.appendWindow(appended, firstPage = 2)

        assertEquals(listOf("c", "d"), result.notifications.map { it.id })
        assertEquals(listOf(0, 1, 1), result.pageItemCounts)
        assertEquals(2, result.loadedFromPage)
        assertEquals(4, result.loadedThroughPage)
        assertEquals(appended.cursorHistory, result.cursorHistory)
        assertEquals("c4", result.nextCursor)
        assertTrue(result.canReuseWindow())
    }

    @Test
    fun duplicateAcrossPagesRequiresRebuildingBeforeTheNextWindowShift() {
        val result = firstWindow.appendWindow(page("c"), firstPage = 2)

        assertEquals(listOf("c"), result.notifications.map { it.id })
        assertFalse(result.canReuseWindow())
    }

    @Test
    fun missingBoundariesPausedPagingOrMissingCursorCannotReuseWindow() {
        assertFalse(firstWindow.copy(pageItemCounts = emptyList()).canReuseWindow())
        assertFalse(firstWindow.copy(pagingPaused = true).canReuseWindow())
        assertFalse(firstWindow.copy(cursorHistory = listOf("c1")).canReuseWindow())
    }

    private fun page(id: String) =
        NotificationsSnapshot(
            notifications = listOf(NotificationDto(id = id)),
            loadedFromPage = 4,
            loadedThroughPage = 4,
            nextCursor = "c4",
            cursorHistory = listOf("c1", "c2", "c3", "c4"),
            pageItemCounts = listOf(1),
        )
}
