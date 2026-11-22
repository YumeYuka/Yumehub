package moe.yumeyuka.yumehub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardFavoritesOrderTest {
    @Test
    fun reorderKeepsOtherPinTypesInTheirSlots() {
        assertEquals(
            listOf("b", "other", "a"),
            mergeDashboardFavorites(listOf("a", "other", "b"), listOf("a", "b"), listOf("b", "a")),
        )
    }

    @Test
    fun reorderedDraftDoesNotRestoreRemovedPinsOrDropConcurrentAdditions() {
        assertEquals(
            listOf("new", "external", "b", "a"),
            mergeDashboardFavorites(
                current = listOf("a", "external", "b"),
                original = listOf("a", "b", "gone"),
                selected = listOf("new", "gone", "b", "a"),
            ),
        )
    }
}
