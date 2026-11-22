package moe.yumeyuka.yumehub.data.repository

import moe.yumeyuka.yumehub.data.api.dto.WorkTaskProgressDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkTaskProgressTest {
    @Test
    fun countsCheckedTasksInRenderedBody() {
        val html =
            """
            <ul>
                <li><input type="checkbox" class="task-list-item-checkbox" disabled checked>Done</li>
                <li><input type="checkbox" class="task-list-item-checkbox" disabled>Pending</li>
                <li><input type="checkbox" class="tlb-checkbox" disabled checked>Legacy</li>
            </ul>
            <input type="checkbox" checked>
            """.trimIndent()

        assertEquals(WorkTaskProgressDto(completed = 2, total = 3), html.workTaskProgress())
    }

    @Test
    fun ignoresEscapedMarkupAndUnrelatedInputs() {
        val html =
            """
            <pre><code>&lt;input class="task-list-item-checkbox" checked&gt;</code></pre>
            <input type="checkbox" checked>
            """.trimIndent()

        assertNull(html.workTaskProgress())
    }
}
