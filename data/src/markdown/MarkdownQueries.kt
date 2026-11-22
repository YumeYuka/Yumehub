package moe.yumeyuka.yumehub.data.markdown

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest

// 来源：官方 APK 1.283.0 的 znx / o2k / ams 与 CommentFragment
// 这些私有字段及参数不保证向第三方令牌开放；不支持时由仓库显式报错
private const val CommentHtml =
    """
    bodyHTML(
        hideCodeBlobs: true
        renderSuggestedChangesAsText: false
        includeSuggestedChangesId: true
        unfurlReferences: true
        scrubVideo: false
    )
"""

private const val MobileBodyHtml =
    """
    bodyHtml: bodyHTML(
        hideCodeBlobs: true
        renderSuggestedChangesAsText: false
        includeSuggestedChangesId: true
        unfurlReferences: true
        scrubVideo: false
        renderMobileTasklistBlocks: true
    )
"""

internal fun readmeRequest(repository: MarkdownRepositoryUrl, ref: String?): GraphQlRequest =
    GraphQlRequest(
        query =
            """
            query RepositoryReadme(${'$'}owner: String!, ${'$'}name: String!, ${'$'}ref: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    readme(refName: ${'$'}ref) { contentHTML path }
                }
            }
            """
                .trimIndent(),
        variables =
            buildJsonObject {
                put("owner", repository.owner)
                put("name", repository.name)
                put("ref", ref)
            },
    )

internal fun nodeBodyRequest(nodeId: String): GraphQlRequest =
    GraphQlRequest(
        query =
            """
        query MarkdownNodeBody(${'$'}id: ID!) {
            node(id: ${'$'}id) {
                __typename
                ... on Comment { $CommentHtml }
                ... on Issue { $MobileBodyHtml }
                ... on PullRequest { $MobileBodyHtml }
            }
        }
    """
                .trimIndent(),
        variables = buildJsonObject { put("id", nodeId) },
    )

internal fun repositoryBodyRequest(repository: MarkdownRepositoryUrl): GraphQlRequest {
    val field = if (repository.workType == "pull") "pullRequest" else "issue"
    return GraphQlRequest(
        query =
            """
            query MarkdownRepositoryBody(
                ${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!
            ) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    work: $field(number: ${'$'}number) { $MobileBodyHtml }
                }
            }
        """
                .trimIndent(),
        variables =
            buildJsonObject {
                put("owner", repository.owner)
                put("name", repository.name)
                put("number", requireNotNull(repository.workNumber))
            },
    )
}
