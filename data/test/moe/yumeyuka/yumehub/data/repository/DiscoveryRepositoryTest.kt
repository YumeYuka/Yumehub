package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryRepositoryTest {
    @Test
    fun defaultAndCombinedFiltersUseIndependentVariables() {
        assertEquals(Json.parseToJsonElement("""{"period":"DAILY"}"""), TrendingFilter().variables())
        val filter = TrendingFilter(TrendingPeriod.WEEKLY, "Kotlin", "zh")
        assertEquals(
            Json.parseToJsonElement("""{"period":"WEEKLY","language":"Kotlin","spokenLanguageCode":"zh"}"""),
            filter.variables(),
        )
        assertEquals(
            Json.parseToJsonElement("""{"period":"MONTHLY","spokenLanguageCode":"zh"}"""),
            filter.copy(period = TrendingPeriod.MONTHLY, language = null).variables(),
        )
    }

    @Test
    fun rankingKeepsServerOrderAndOgInsteadOfSortingByLifetimeStars() {
        val page = Json.parseToJsonElement(
            """
            {"trendingRepositories":[
              {"id":"r2","name":"second","owner":{"login":"org"},"stargazerCount":1,
               "openGraphImageUrl":"https://opengraph.githubassets.com/hash/org/second"},
              {"id":"r1","name":"first","owner":{"login":"org"},"stargazerCount":500}
            ]}
            """.trimIndent(),
        ).jsonObject.trendingPage()
        assertEquals(listOf("org/second", "org/first"), page.items.map { it.fullName })
        assertEquals("https://opengraph.githubassets.com/hash/org/second", page.items.first().openGraphImageUrl)
        assertNull(page.items.last().openGraphImageUrl)
        assertNull(page.nextCursor)
    }

    @Test(expected = GithubPageException::class)
    fun missingRankingIsNotAnEmptyResult() {
        Json.parseToJsonElement("{}").jsonObject.trendingPage()
    }
}
