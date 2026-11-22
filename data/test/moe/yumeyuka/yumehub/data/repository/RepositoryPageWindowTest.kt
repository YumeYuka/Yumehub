package moe.yumeyuka.yumehub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryPageWindowTest {
    @Test
    fun pagesReadOnlyTheirWindowAndDoNotRetainTheSourceList() {
        val values = (0 until 205).toMutableList()
        val reads = mutableListOf<Int>()
        val source =
            object : AbstractList<Int>() {
                override val size
                    get() = values.size

                override fun get(index: Int): Int {
                    reads += index
                    return values[index]
                }
            }
        val middle = source.repositoryPage(2, 100)
        val tail = source.repositoryPage(3, 100)
        val beyond = source.repositoryPage(Int.MAX_VALUE, 100)
        assertEquals((100 until 205).toList(), reads)
        values.clear()
        assertEquals((100 until 200).toList(), middle)
        assertEquals((200 until 205).toList(), tail)
        assertEquals(emptyList<Int>(), beyond)
    }
}
