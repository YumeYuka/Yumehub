package moe.yumeyuka.yumehub.ui.component

import androidx.compose.ui.layout.PinnableContainer
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownCardRetentionTest {
    @Test
    fun revisitingKeepsTheSameItemAndEvictionReleasesOnlyTheOldest() {
        val retention = MarkdownCardRetention()
        val items = List(5) { Item() }
        for (index in 0..3) retention.visit(index, items[index])
        retention.visit(0, items[0])
        retention.visit(4, items[4])
        assertEquals(listOf(1, 0, 1, 1, 1), items.map { it.pins - it.releases })
        assertEquals(1, items[0].pins)
        items.indices.forEach(retention::remove)
        assertEquals(items.map { it.pins }, items.map { it.releases })
    }

    private class Item : PinnableContainer {
        var pins = 0
        var releases = 0

        override fun pin(): PinnableContainer.PinnedHandle {
            pins++
            return PinnableContainer.PinnedHandle { releases++ }
        }
    }
}
