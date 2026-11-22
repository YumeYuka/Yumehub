package moe.yumeyuka.yumehub.feature.workspaces

import moe.yumeyuka.yumehub.core.model.GithubGitObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceMarkdownLinksTest {
    @Test
    fun resolvesMarkdownFileOnCurrentSlashBranchAndPreservesAnchor() {
        assertEquals(
            GithubGitObject("refs/heads/feature/docs", "guides/start.md", "intro"),
            resolveMarkdownEditorLink(
                "https://github.com/octo/repo/blob/feature/docs/guides/start.md#intro",
                owner = "octo",
                name = "repo",
                ref = "refs/heads/feature/docs",
            ),
        )
    }

    @Test
    fun rejectsOtherRepositoriesRefsDirectoriesAndNonMarkdownFiles() {
        val owner = "octo"
        val name = "repo"
        val ref = "refs/heads/main"

        assertNull(
            resolveMarkdownEditorLink(
                "https://github.com/other/repo/blob/main/README.md",
                owner,
                name,
                ref,
            ),
        )
        assertNull(
            resolveMarkdownEditorLink(
                "https://github.com/octo/repo/blob/feature/docs/README.md",
                owner,
                name,
                ref,
            ),
        )
        assertNull(
            resolveMarkdownEditorLink(
                "https://github.com/octo/repo/tree/main/docs",
                owner,
                name,
                ref,
            ),
        )
        assertNull(
            resolveMarkdownEditorLink(
                "https://github.com/octo/repo/blob/main/src/Main.kt",
                owner,
                name,
                ref,
            ),
        )
    }
}
