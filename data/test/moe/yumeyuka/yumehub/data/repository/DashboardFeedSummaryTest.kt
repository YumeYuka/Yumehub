package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardFeedSummaryTest {
    private val repository = """{"name":"YumeBox","owner":{"login":"owner"}}"""

    @Test
    fun releaseSummaryDecodesEntitiesAndOmitsMarkup() {
        val node = Json.parseToJsonElement(
            """{"__typename":"PublishedReleaseFeedItem","identifier":"release",
                "release":{"name":"v1","repository":$repository,
                "shortDescriptionHTML":"<p>Fix <b>tabs</b> &amp; lists.</p><p>Keep position.</p>"}}"""
        ).jsonObject
        val release = requireNotNull(dashboardFeedEvent(node)?.payload?.release)
        assertEquals("v1", release.name)
        assertEquals("Fix tabs & lists. Keep position.", release.body)
    }

    @Test
    fun pullRequestUsesBoundedBodyTextAndTheAuthorNotTheMerger() {
        val text = "x".repeat(600)
        val node = Json.parseToJsonElement(
            """{"__typename":"MergedPullRequestFeedItem","identifier":"pull",
                "actor":{"login":"merger"},"pullRequest":{"repository":$repository,
                "author":{"login":"contributor"},"bodyText":"$text","bodyHTML":"<p>other</p>"}}"""
        ).jsonObject
        val work = requireNotNull(dashboardFeedEvent(node)?.payload?.pullRequest)
        assertEquals("contributor", work.author?.login)
        assertEquals(text.take(480), work.body)
    }
}
