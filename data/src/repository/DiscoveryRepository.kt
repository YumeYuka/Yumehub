package moe.yumeyuka.yumehub.data.repository

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

enum class TrendingPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
}

data class TrendingFilter(
    val period: TrendingPeriod = TrendingPeriod.DAILY,
    val language: String? = null,
    val spokenLanguageCode: String? = null,
)

@Serializable
data class DiscoveryLanguages(val programming: List<String>, val spoken: Map<String, String>)

class DiscoveryRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val paging: PagingRepository,
    private val cache: CachedDataRepository,
) {
    fun trendingKey(filter: TrendingFilter): String = "explore:trending:$filter"

    fun featuredKey(query: String, language: String?, recentlyUpdated: Boolean): String =
        "explore:awesome:$recentlyUpdated:$language:$query"

    fun revalidation(account: String, key: String) = paging.revalidation(account, key)

    fun retryRevalidation(account: String, key: String) = paging.retryRevalidation(account, key)

    fun trending(account: String, filter: TrendingFilter): Flow<PagingData<RepositoryDto>> =
        githubGraphQlPager(
            account,
            trendingKey(filter),
            paging,
            { it.nodeId.orEmpty() },
            serializer = RepositoryDto.serializer(),
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val load: suspend (String?) -> GithubGraphQlPage<RepositoryDto> = {
                session.execute(TrendingQuery, filter.variables()).trendingPage()
            }
            load
        }

    fun featured(
        account: String,
        query: String,
        language: String?,
        recentlyUpdated: Boolean,
    ): Flow<PagingData<RepositoryDto>> =
        githubGraphQlPager(
            account,
            featuredKey(query, language, recentlyUpdated),
            paging,
            { it.nodeId.orEmpty() },
            serializer = RepositoryDto.serializer(),
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val search = query.isNotBlank() || language != null
            val load: suspend (String?) -> GithubGraphQlPage<RepositoryDto> = { after ->
                val variables = buildJsonObject {
                    after?.let { put("after", it) }
                    if (search) {
                        put(
                            "query",
                            buildString {
                                if (query.isNotBlank()) append(query).append(' ')
                                language?.let { append("language:\"").append(it).append("\" ") }
                                append("topic:awesome sort:")
                                append(if (recentlyUpdated) "updated-desc" else "stars-desc")
                            },
                        )
                    } else put("order", if (recentlyUpdated) "UPDATED_AT" else "STARGAZERS")
                }
                val page =
                    if (search) session.execute(FeaturedSearch, variables).graphObject("search")
                    else
                        session
                            .execute(FeaturedQuery, variables)
                            .graphObject("topic")
                            .graphObject("repositories")
                GithubGraphQlPage(page.graphNodes().map(::repositoryItem), page.graphCursor(after))
            }
            load
        }

    suspend fun languages(account: String, includeSpoken: Boolean): DiscoveryLanguages =
        cache
            .resource(
                account,
                "discovery.languages.v1:$includeSpoken",
                DiscoveryLanguages.serializer(),
                maxAgeMillis = 24 * 60 * 60_000L,
                tags = setOf(CacheTag(DataDomain.Content, null)),
            )
            .await { loadLanguages(account, includeSpoken) }

    private suspend fun loadLanguages(account: String, includeSpoken: Boolean): DiscoveryLanguages {
        val session = GithubGraphQlSession(api, tokens, account)
        val data = session.execute(LanguageQuery, buildJsonObject { put("spoken", includeSpoken) })
        return DiscoveryLanguages(
            data
                .objects("programmingLanguages")
                .map { it.graphRequiredText("name") }
                .distinct()
                .sorted(),
            if (includeSpoken)
                data.objects("spokenLanguages").associate {
                    it.graphRequiredText("code") to it.graphRequiredText("name")
                }
            else emptyMap(),
        )
    }
}

internal fun TrendingFilter.variables() = buildJsonObject {
    put("period", period.name)
    language?.let { put("language", it) }
    spokenLanguageCode?.let { put("spokenLanguageCode", it) }
}

internal fun JsonObject.trendingPage() =
    GithubGraphQlPage(objects("trendingRepositories").map(::repositoryItem), null)

private fun JsonObject.objects(key: String): List<JsonObject> =
    (get(key) as? JsonArray ?: throw GithubPageException()).map {
        it as? JsonObject ?: throw GithubPageException()
    }

private val TrendingQuery =
    """
    query ExploreTrending(${'$'}language: String, ${'$'}spokenLanguageCode: String, ${'$'}period: TrendingPeriod) {
      trendingRepositories(language: ${'$'}language, spokenLanguageCode: ${'$'}spokenLanguageCode, period: ${'$'}period, mobileSortOrder: true) {
        $RepositoryCardFields
      }
    }
    """
        .trimIndent()

private val FeaturedQuery =
    """
    query ExploreAwesomeTopics(${'$'}after: String, ${'$'}order: RepositoryOrderField!) {
      topic(name: "awesome") {
        repositories(first: 30, after: ${'$'}after, orderBy: {field: ${'$'}order, direction: DESC}) {
          pageInfo { hasNextPage endCursor }
          nodes { $RepositoryCardFields }
        }
      }
    }
    """
        .trimIndent()

private val FeaturedSearch =
    """
    query SearchAwesomeTopics(${'$'}query: String!, ${'$'}after: String) {
      search(query: ${'$'}query, type: REPOSITORY, first: 30, after: ${'$'}after) {
        pageInfo { hasNextPage endCursor }
        nodes { ... on Repository { $RepositoryCardFields } }
      }
    }
    """
        .trimIndent()

private val LanguageQuery =
    """
    query ExploreFilterLanguages(${'$'}spoken: Boolean!) {
      programmingLanguages(suggested: true) { name }
      spokenLanguages @include(if: ${'$'}spoken) { code name }
    }
    """
        .trimIndent()
