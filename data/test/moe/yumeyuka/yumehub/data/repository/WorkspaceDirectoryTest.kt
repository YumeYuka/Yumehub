package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkspaceDirectoryTest {
    @Test
    fun directoryOrderRemainsStableAndFirstDuplicateWins() {
        val entries =
            Json.parseToJsonElement(
                    """[
              {"name":"A","type":"blob"},{"name":"a","type":"blob"},
              {"name":"dir","type":"tree"},null,{"type":"tree"},
              {"name":"A","type":"tree"},{"name":"sub","submodule":{"gitUrl":"source"}}
            ]""",
                )
                .jsonArray

        val rows = parseWorkspaceDirectory(entries, "src")

        assertEquals(listOf("src/dir", "src/A", "src/a", "src/sub"), rows.map { it.path })
        assertFalse(rows[1].directory)
        assertEquals(WorkspaceEntry("sub", "src/sub", false, true, "source"), rows.last())
    }
}
