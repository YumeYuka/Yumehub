package moe.yumeyuka.markdown.core

import org.junit.Assert.assertThrows
import org.junit.Test

class HtmlByteLimitTest {
    @Test
    fun limitUsesEncodedBytesIncludingUnpairedSurrogates() {
        for (text in listOf("ascii", "à", "中", "\uD83D\uDE00", "x\uD800", "\uDC00x", "a中à")) {
            val bytes = text.toByteArray(Charsets.UTF_8).size
            GithubHtmlParser.parse(text, HtmlParseOptions(maxHtmlBytes = bytes))
            assertThrows(HtmlParseException::class.java) {
                GithubHtmlParser.parse(text, HtmlParseOptions(maxHtmlBytes = bytes - 1))
            }
        }
    }
}
