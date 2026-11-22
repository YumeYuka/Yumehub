package moe.yumeyuka.yumehub.data.repository

internal val DashboardUserCardQuery =
    """
    query FeedUserCard(${'$'}login: String!) {
        target: repositoryOwner(login: ${'$'}login) {
            id login avatarUrl url __typename
            ... on User {
                name bio viewerIsFollowing
                repositories(ownerAffiliations: [OWNER]) { totalCount }
                followers { totalCount }
            }
            ... on Organization { name description viewerIsFollowing }
        }
    }
    """
        .trimIndent()

internal val DashboardRepositoryCardQuery =
    """
    query FeedRepositoryCard(${'$'}owner: String!, ${'$'}name: String!) {
        target: repository(owner: ${'$'}owner, name: ${'$'}name) {
            id name url description stargazerCount viewerHasStarred openGraphImageUrl
            primaryLanguage { name }
            owner { login avatarUrl __typename }
        }
    }
    """
        .trimIndent()
