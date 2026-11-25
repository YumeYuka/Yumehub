package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationReviewFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationSortOrder
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.AssigneeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.data.api.dto.IssueTemplateOptionDto
import moe.yumeyuka.yumehub.data.api.dto.IssueTypeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.MilestoneOptionDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.api.dto.workTarget
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

class WorkListRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val paging: PagingRepository,
    private val cache: CachedDataRepository,
) {
  private val filterOptionsCache = FilterOptionsCache()

  suspend fun createIssue(
      account: String,
      owner: String,
      repository: String,
      title: String,
      body: String,
      issueTypeId: String? = null,
      milestoneId: String? = null,
      labelIds: List<String> = emptyList(),
      assigneeIds: List<String> = emptyList(),
      issueTemplate: String? = null,
  ): Int {
    require(title.isNotBlank())
    val session = GithubGraphQlSession(api, tokens, account)
    val repositoryId = repositoryId(session, owner, repository)
    return session
        .execute(
            """
            mutation CreateIssue(${'$'}createIssueInput: CreateIssueInput!) {
                createIssue(input: ${'$'}createIssueInput) { issue { number } }
            }
            """
                .trimIndent(),
            buildJsonObject {
              put(
                  "createIssueInput",
                  buildJsonObject {
                    put("repositoryId", repositoryId)
                    put("title", title)
                    put("body", body)
                    issueTemplate?.takeIf(String::isNotBlank)?.let {
                      put("issueTemplate", it)
                    }
                    issueTypeId?.takeIf(String::isNotBlank)?.let { put("issueTypeId", it) }
                    milestoneId?.takeIf(String::isNotBlank)?.let { put("milestoneId", it) }
                    if (labelIds.isNotEmpty()) {
                      put("labelIds", buildJsonArray { labelIds.forEach(::add) })
                    }
                    if (assigneeIds.isNotEmpty()) {
                      put("assigneeIds", buildJsonArray { assigneeIds.forEach(::add) })
                    }
                  },
              )
            },
            write = true,
        )
        .graphObject("createIssue")
        .graphObject("issue")
        .get("number")
        ?.jsonPrimitive
        ?.intOrNull
        ?.takeIf { it > 0 } ?: throw GithubPageException()
  }

  suspend fun searchAssignableUsers(
      account: String,
      owner: String,
      repository: String,
      query: String,
      after: String? = null,
  ): GithubGraphQlPage<AssigneeOptionDto> {
    val session = GithubGraphQlSession(api, tokens, account)
    val data =
        session.execute(
            AssignableUsersQuery,
            buildJsonObject {
              put("owner", owner)
              put("repo", repository)
              put("query", query)
              put("after", after?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
    val connection = data.graphObject("repository").graphObject("assignableUsers")
    val users =
        connection.safeNodes().mapNotNull { node ->
          val id = node.graphText("id") ?: return@mapNotNull null
          val login = node.graphText("login") ?: return@mapNotNull null
          AssigneeOptionDto(id, login, node.graphText("name"))
        }
    return GithubGraphQlPage(users, connection.graphCursor(after)).also {
      session.ensureCurrent()
    }
  }

  suspend fun createPullRequest(
      account: String,
      owner: String,
      repository: String,
      base: String,
      head: String,
      title: String,
      body: String,
  ): Int {
    require(title.isNotBlank() && base.isNotBlank() && head.isNotBlank() && base != head)
    val session = GithubGraphQlSession(api, tokens, account)
    val repositoryId = repositoryId(session, owner, repository)
    return session
        .execute(
            """
            mutation CreatePullRequest(
                ${'$'}repositoryId: ID!, ${'$'}baseRefName: String!, ${'$'}headRefName: String!,
                ${'$'}title: String!, ${'$'}body: String
            ) {
                createPullRequest(input: {
                    repositoryId: ${'$'}repositoryId
                    baseRefName: ${'$'}baseRefName
                    headRefName: ${'$'}headRefName
                    title: ${'$'}title
                    body: ${'$'}body
                }) { pullRequest { number } }
            }
            """
                .trimIndent(),
            buildJsonObject {
              put("repositoryId", repositoryId)
              put("baseRefName", base)
              put("headRefName", head)
              put("title", title)
              put("body", body)
            },
            write = true,
        )
        .graphObject("createPullRequest")
        .graphObject("pullRequest")
        .get("number")
        ?.jsonPrimitive
        ?.intOrNull
        ?.takeIf { it > 0 } ?: throw GithubPageException()
  }

  private suspend fun repositoryId(
      session: GithubGraphQlSession,
      owner: String,
      repository: String,
  ): String =
      session
          .execute(
              """
              query WorkCreationRepository(${'$'}owner: String!, ${'$'}name: String!) {
                  repository(owner: ${'$'}owner, name: ${'$'}name) { id }
              }
              """
                  .trimIndent(),
              buildJsonObject {
                put("owner", owner)
                put("name", repository)
              },
          )
          .graphObject("repository")
          .graphRequiredText("id")

  fun personalItems(
      account: String,
      kind: CollaborationKind,
      filter: CollaborationListFilter,
  ) =
      when {
        kind == CollaborationKind.Issue &&
            filter ==
                CollaborationListFilter(
                    state = CollaborationStateFilter.All,
                    scope = CollaborationScopeFilter.AssignedToMe,
                ) -> viewerIssues(account, "assignee")
        kind == CollaborationKind.Issue &&
            filter ==
                CollaborationListFilter(
                    state = CollaborationStateFilter.All,
                    scope = CollaborationScopeFilter.CreatedByMe,
                ) -> viewerIssues(account, "createdBy")
        else ->
            personalSearch(
                account,
                workSearchQuery(account, kind, filter, repository = null),
                filter.state,
            )
      }

  /** 未叠加筛选的个人议题可直接读取 viewer 连接。 */
  private fun viewerIssues(
      account: String,
      filterField: String,
  ) =
      githubGraphQlPager(
          account,
          "work-viewer-issues-graphql.v1:$filterField",
          paging,
          identity = { it: CollaborationItemDto -> requireNotNull(it.nodeId) },
          serializer = CollaborationItemDto.serializer(),
      ) {
        val session = GithubGraphQlSession(api, tokens, account)
        val query =
            """
                query WorkViewerIssues(${'$'}login: String!, ${'$'}after: String) {
                    viewer {
                        issues(first: 30, after: ${'$'}after, states: [OPEN, CLOSED],
                            orderBy: {field: UPDATED_AT, direction: DESC},
                            filterBy: {$filterField: ${'$'}login}) {
                            pageInfo { hasNextPage endCursor }
                            nodes { $WorkIssueListItemFields }
                        }
                    }
                }
            """
                .trimIndent()
        val load: suspend (String?) -> GithubGraphQlPage<CollaborationItemDto> = { after ->
          val data =
              session.execute(
                  query,
                  buildJsonObject {
                    put("login", account)
                    put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                  },
              )
          val connection = data.graphObject("viewer").graphObject("issues")
          val items =
              connection.graphNodes().map { node ->
                node.toCollaborationItem(CollaborationKind.Issue) ?: throw GithubPageException()
              }
          GithubGraphQlPage(
              items,
              connection.graphCursor(after),
          )
        }
        load
      }

  private fun personalSearch(
      account: String,
      query: String,
      state: CollaborationStateFilter,
  ) =
      githubGraphQlPager(
          account,
          "work-search-graphql.v1:$query",
          paging,
          identity = { it: CollaborationItemDto -> requireNotNull(it.nodeId) },
          serializer = CollaborationItemDto.serializer(),
          mapItem = { item ->
            val merged = item.workTarget()?.let { cache.changes(account).isMerged(it) } == true
            when {
              !merged -> item
              state == CollaborationStateFilter.Open -> null
              else -> item.copy(merged = true, state = "closed")
            }
          },
      ) {
        val session = GithubGraphQlSession(api, tokens, account)
        val load: suspend (String?) -> GithubGraphQlPage<CollaborationItemDto> = { after ->
          val variables = buildJsonObject {
            put("query", query)
            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
          }
          val data = session.execute(WorkSearchQuery, variables)
          val search = data.graphObject("search")
          val items =
              search.safeNodes().mapNotNull { it.toCollaborationItem() }
          GithubGraphQlPage(items, search.graphCursor(after))
        }
        load
      }

  /** 仓库议题／拉取请求列表统一走 GraphQL `search`，这样状态、范围和排序都只由一个请求 完成，不必为每种筛选组合维护并行连接。 */
  fun repositoryItems(
      account: String,
      owner: String,
      name: String,
      kind: CollaborationKind,
      filter: CollaborationListFilter,
  ) =
      githubGraphQlPager(
          account,
          "repository-work-search.v2:$owner/$name:${kind.name}:$filter",
          paging,
          identity = { it: CollaborationItemDto -> requireNotNull(it.nodeId) },
          serializer = CollaborationItemDto.serializer(),
          mapItem = { item ->
            val merged = cache.changes(account).isMerged(WorkTarget(owner, name, item.number, kind))
            when {
              !merged -> item
              filter.state == CollaborationStateFilter.Open -> null
              else -> item.copy(merged = true, state = "closed")
            }
          },
      ) {
        val session = GithubGraphQlSession(api, tokens, account)
        val query = workSearchQuery(account, kind, filter, repository = "$owner/$name")
        val load: suspend (String?) -> GithubGraphQlPage<CollaborationItemDto> = { after ->
          val variables = buildJsonObject {
            put("query", query)
            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
          }
          val data = session.execute(WorkSearchQuery, variables)
          val search = data.graphObject("search")
          val items =
              search.safeNodes().mapNotNull { it.toCollaborationItem(kind) }
          GithubGraphQlPage(items, search.graphCursor(after))
        }
        load
      }

  /** 筛选菜单按账号与仓库短时缓存；读取失败由调用方保留空选项。 */
  suspend fun filterOptions(
      account: String,
      owner: String,
      name: String,
  ): CollaborationFilterOptions {
    val session = GithubGraphQlSession(api, tokens, account)
    return filterOptionsCache
        .get(account, owner, name, tokens.sessionGeneration.value) {
          val data =
              session.execute(
                  FilterOptionsQuery,
                  buildJsonObject {
                    put("owner", owner)
                    put("name", name)
                  },
              )
          val repository = data.graphObject("repository")
          val labels =
              (repository["labels"] as? JsonObject)
                  ?.safeNodes()
                  ?.mapNotNull { node ->
                    val id = node.graphText("id") ?: return@mapNotNull null
                    val name = node.graphText("name") ?: return@mapNotNull null
                    CollaborationLabelDto(name, node.graphText("color"), id)
                  }
                  .orEmpty()
          val milestones =
              (repository["milestones"] as? JsonObject)
                  ?.safeNodes()
                  ?.mapNotNull { node ->
                    val id = node.graphText("id") ?: return@mapNotNull null
                    val title = node.graphText("title") ?: return@mapNotNull null
                    MilestoneOptionDto(id, title)
                  }
                  .orEmpty()
          val issueTypes =
              (repository["issueTypes"] as? JsonObject)
                  ?.safeNodes()
                  ?.mapNotNull { node ->
                    val id = node.graphText("id") ?: return@mapNotNull null
                    val name = node.graphText("name") ?: return@mapNotNull null
                    IssueTypeOptionDto(id, name, node.graphText("color"))
                  }
                  .orEmpty()
          val issueTemplates =
              (repository["issueTemplates"] as? JsonArray)
                  ?.mapNotNull { value ->
                    val node = value as? JsonObject ?: return@mapNotNull null
                    val templateName = node.graphText("name") ?: return@mapNotNull null
                    val labels =
                        (node["labels"] as? JsonObject)
                            ?.safeNodes()
                            ?.mapNotNull { label ->
                              val id = label.graphText("id") ?: return@mapNotNull null
                              val labelName = label.graphText("name") ?: return@mapNotNull null
                              CollaborationLabelDto(
                                  labelName,
                                  label.graphText("color"),
                                  id,
                              )
                            }
                            .orEmpty()
                    val assignees =
                        (node["assignees"] as? JsonObject)
                            ?.safeNodes()
                            ?.mapNotNull { user ->
                              val id = user.graphText("id") ?: return@mapNotNull null
                              val login = user.graphText("login") ?: return@mapNotNull null
                              AssigneeOptionDto(id, login, user.graphText("name"))
                            }
                            .orEmpty()
                    IssueTemplateOptionDto(
                        name = templateName,
                        title = node.graphText("title"),
                        body = node.graphText("body"),
                        labels = labels,
                        assignees = assignees,
                        issueTypeId = (node["type"] as? JsonObject)?.graphText("id"),
                    )
                  }
                  .orEmpty()
          CollaborationFilterOptions(
              labels = labels,
              milestones = milestones.map(MilestoneOptionDto::title),
              issueTypes = issueTypes.map { CollaborationLabelDto(it.name, it.color) },
              issueTypeOptions = issueTypes,
              milestoneOptions = milestones,
              issueTemplates = issueTemplates,
          )
        }
        .also { session.ensureCurrent() }
  }
}

private fun workSearchQuery(
    account: String,
    kind: CollaborationKind,
    filter: CollaborationListFilter,
    repository: String?,
): String =
    buildList {
          repository?.let { add("repo:$it") }
          add(if (kind == CollaborationKind.Issue) "is:issue" else "is:pr")
          filter.searchQuery.trim().takeIf(String::isNotEmpty)?.let {
            add("in:title,body ${workSearchQuote(it)}")
          }
          when (filter.state) {
            CollaborationStateFilter.Open -> add("is:open")
            CollaborationStateFilter.Closed -> add("is:closed")
            CollaborationStateFilter.All -> Unit
          }
          when (filter.scope) {
            CollaborationScopeFilter.All -> if (repository == null) add("involves:$account")
            CollaborationScopeFilter.CreatedByMe -> add("author:$account")
            CollaborationScopeFilter.AssignedToMe -> add("assignee:$account")
            CollaborationScopeFilter.ReviewRequested -> add("review-requested:$account")
            CollaborationScopeFilter.Commented -> add("commenter:$account")
            CollaborationScopeFilter.Mentioned -> add("mentions:$account")
            CollaborationScopeFilter.Involved -> add("involves:$account")
          }
          if (kind == CollaborationKind.PullRequest && filter.draftOnly) add("is:draft")
          filter.label?.let { add("label:${workSearchQuote(it)}") }
          if (filter.unlabeledOnly) add("no:label")
          if (filter.unassignedOnly) add("no:assignee")
          filter.milestone?.let { add("milestone:${workSearchQuote(it)}") }
          if (kind == CollaborationKind.Issue) {
            filter.issueType?.let { add("type:${workSearchQuote(it)}") }
          }
          if (kind == CollaborationKind.PullRequest) {
            when (filter.review) {
              CollaborationReviewFilter.Any -> Unit
              CollaborationReviewFilter.NoReviews -> add("review:none")
              CollaborationReviewFilter.Required -> add("review:required")
              CollaborationReviewFilter.Approved -> add("review:approved")
              CollaborationReviewFilter.ChangesRequested -> add("review:changes-requested")
              CollaborationReviewFilter.ReviewedByMe -> add("reviewed-by:@me")
              CollaborationReviewFilter.NotReviewedByMe -> add("-reviewed-by:@me")
              CollaborationReviewFilter.AwaitingMyTeamReview -> add("review-requested:@me")
              CollaborationReviewFilter.AwaitingMyReview -> add("user-review-requested:@me")
            }
          }
          add(
              when (filter.sort) {
                CollaborationSortOrder.Updated -> "sort:updated-desc"
                CollaborationSortOrder.Created -> "sort:created-desc"
                CollaborationSortOrder.Comments -> "sort:comments-desc"
              },
          )
        }
        .joinToString(" ")

private fun workSearchQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun JsonObject.safeNodes(): List<JsonObject> =
    (get("nodes") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.count(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.connectionCount(key: String): Int? =
    (get(key) as? JsonObject)?.count("totalCount")

private fun JsonObject.author(): UserDto? =
    (get("author") as? JsonObject)?.let {
      UserDto(
          login = it.graphText("login"),
          avatarUrl = it.graphText("avatarUrl"),
          type = it.graphText("__typename"),
      )
    }

private fun JsonObject.labels(): List<CollaborationLabelDto> =
    (get("labels") as? JsonObject)
        ?.safeNodes()
        ?.mapNotNull { node ->
          node.graphText("name")?.let { name ->
            CollaborationLabelDto(name = name, color = node.graphText("color"))
          }
        }
        .orEmpty()

private fun JsonObject.assignedActors(): List<String> =
    (get("assignedActors") as? JsonObject)
        ?.safeNodes()
        ?.mapNotNull { it.graphText("login") }
        .orEmpty()

internal fun JsonObject.workCardParticipants(): List<UserDto> =
    (get("participants") as? JsonObject)
        ?.safeNodes()
        ?.mapNotNull { actor ->
          val login = actor.graphText("login") ?: return@mapNotNull null
          UserDto(login = login, avatarUrl = actor.graphText("avatarUrl"))
        }
        ?.take(3)
        .orEmpty()

internal fun JsonObject.workCardBodyPreview(): String? = graphText("bodyText")?.trim()?.take(480)

internal const val WorkCardCheckFields =
    "commits(last: 1) { nodes { commit { statusCheckRollup { state } } } }"

internal fun JsonObject.workCardCheckState(): String? =
    (get("commits") as? JsonObject)
        ?.safeNodes()
        ?.firstOrNull()
        ?.let { it["commit"] as? JsonObject }
        ?.let { it["statusCheckRollup"] as? JsonObject }
        ?.graphText("state")

private fun JsonObject.toCollaborationItem(
    expected: CollaborationKind? = null,
): CollaborationItemDto? {
  val type = graphRequiredText("__typename")
  val pull = type == "PullRequest"
  if (type != "Issue" && !pull) return null
  if (expected != null && ((expected == CollaborationKind.Issue) == pull)) return null
  val state =
      (graphText("issueState") ?: graphText("pullRequestState") ?: graphText("state"))?.lowercase(
          Locale.ROOT,
      ) ?: return null
  val issueType = get("issueType") as? JsonObject
  val subIssues = get("subIssuesSummary") as? JsonObject
  return CollaborationItemDto(
      number = count("number").takeIf { it > 0 } ?: return null,
      title = graphText("title") ?: return null,
      state = state,
      nodeId = graphText("id"),
      htmlUrl = graphText("url"),
      createdAt = graphText("createdAt"),
      updatedAt = graphText("updatedAt"),
      user = author(),
      bodyPreview = workCardBodyPreview(),
      labels = labels(),
      commentsCount = if (pull) count("totalCommentsCount") else connectionCount("comments") ?: 0,
      participantsCount = connectionCount("participants"),
      participantAvatars = workCardParticipants(),
      repositoryName = (get("repository") as? JsonObject)?.graphText("nameWithOwner"),
      assignedActors = assignedActors(),
      stateReason = graphText("stateReason"),
      issueType = issueType?.graphText("name"),
      issueTypeColor = issueType?.graphText("color"),
      subIssuesTotal = subIssues?.count("total"),
      subIssuesCompleted = subIssues?.count("completed"),
      linkedPullRequests = connectionCount("closedByPullRequestsReferences"),
      closingIssues = connectionCount("closingIssuesReferences"),
      headRefName = if (pull) graphText("headRefName") else null,
      baseRefName = if (pull) graphText("baseRefName") else null,
      checkState = workCardCheckState(),
      additions = get("additions")?.jsonPrimitive?.intOrNull,
      deletions = get("deletions")?.jsonPrimitive?.intOrNull,
      draft = pull && (get("isDraft")?.jsonPrimitive?.booleanOrNull ?: false),
      merged = pull && (get("merged")?.jsonPrimitive?.booleanOrNull ?: false),
      reviewDecision = if (pull) graphText("reviewDecision") else null,
  )
}

/** `assignedActors` 的节点是 Assignee union，字段必须放进 Actor 片段内。 */
private const val WorkIssueListItemFields =
    """
    __typename
    id number title bodyText issueState: state url createdAt updatedAt
    author { login avatarUrl __typename }
    repository { nameWithOwner }
    labels(first: 6) { nodes { name color } }
    comments { totalCount }
    participants(first: 3) { totalCount nodes { login avatarUrl } }
    assignedActors(first: 10) {
        nodes { __typename ... on Actor { login avatarUrl } }
    }
    issueType { name color }
    subIssuesSummary { total completed }
    stateReason
    closedByPullRequestsReferences { totalCount }
"""

private const val WorkPullListItemFields =
    """
    __typename
    id number title bodyText pullRequestState: state url createdAt updatedAt
    isDraft merged reviewDecision headRefName baseRefName totalCommentsCount additions deletions
    author { login avatarUrl __typename }
    repository { nameWithOwner }
    labels(first: 6) { nodes { name color } }
    participants(first: 3) { totalCount nodes { login avatarUrl } }
    assignedActors(first: 10) {
        nodes { __typename ... on Actor { login avatarUrl } }
    }
    closingIssuesReferences { totalCount }
    $WorkCardCheckFields
"""

private val WorkSearchQuery =
    """
    query RepositoryWorkSearch(${'$'}query: String!, ${'$'}after: String) {
        search(query: ${'$'}query, type: ISSUE, first: 30, after: ${'$'}after) {
            issueCount
            pageInfo { hasNextPage endCursor }
            nodes {
                __typename
                ... on Issue {
                    $WorkIssueListItemFields
                }
                ... on PullRequest {
                    $WorkPullListItemFields
                }
            }
        }
    }
"""
        .trimIndent()

private val FilterOptionsQuery =
    """
    query WorkFilterOptions(${'$'}owner: String!, ${'$'}name: String!) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            labels(first: 100) { nodes { id name color } }
            milestones(first: 100, states: [OPEN, CLOSED]) { nodes { id title } }
            issueTypes(first: 50) { nodes { id name color } }
            issueTemplates {
                name title body
                assignees(first: 30) { nodes { id login name } }
                labels(first: 30) { nodes { id name color } }
                type { id }
            }
        }
    }
    """
        .trimIndent()

private val AssignableUsersQuery =
    """
    query RepositoryAssignableUsers(
        ${'$'}owner: String!, ${'$'}repo: String!, ${'$'}query: String, ${'$'}after: String
    ) {
        repository(owner: ${'$'}owner, name: ${'$'}repo) {
            assignableUsers(first: 50, query: ${'$'}query, after: ${'$'}after) {
                pageInfo { hasNextPage endCursor }
                nodes { id login name }
            }
        }
    }
    """
        .trimIndent()
