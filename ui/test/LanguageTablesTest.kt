package moe.yumeyuka.yumehub.ui.component

import moe.yumeyuka.yumehub.ui.theme.githubLanguageArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageTablesTest {
    @Test
    fun editorResolvesNamedFilesExtensionsAndEmbeddedGrammars() {
        assertEquals("dockerfile", githubCodeLanguageForFilename("ci/Dockerfile.debug"))
        assertEquals("cpp", githubCodeLanguageForFilename("src/legacy.C"))
        assertEquals("typescript", githubCodeLanguageForFilename("types/index.d.ts"))
        assertEquals(
            "source.cpp.embedded.macro",
            GithubGrammarIndex.forScope("source.cpp.embedded.macro")?.scope,
        )
        assertNull(githubCodeLanguageForFilename("license.unknown-extension"))
    }

    @Test
    fun repositoryColorsPreserveCaseAndUnknownLanguageFallback() {
        assertEquals(0xffa97bff.toInt(), githubLanguageArgb("Kotlin"))
        assertEquals(0, githubLanguageArgb("kotlin"))
    }
}
