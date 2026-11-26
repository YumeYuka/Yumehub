package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.workCacheTag
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

@Serializable
data class GithubDiscussionComment(
    val id: String,
    val bodyHtml: String,
    val author: String?,
    val avatarUrl: String?,
    val createdAt: String,
    val replies: Int,
    val replyAuthors: List<UserDto> = emptyList(),
    val isAnswer: Boolean = false,
    val canMarkAsAnswer: Boolean = false,
    val canUnmarkAsAnswer: Boolean = false,
    val canReact: Boolean = false,
    val reactions: List<GithubReactionGroup> = emptyList(),
)

@Serializable
data class GithubDiscussionDetail(
    val id: String,
    val title: String,
    val url: String,
    val bodyHtml: String,
    val author: String?,
    val category: String,
    val closed: Boolean,
    val parent: GithubDiscussionComment? = null,
    val upvoted: Boolean = false,
    val canUpvote: Boolean = false,
    val upvotes: Int = 0,
    val poll: GithubDiscussionPoll? = null,
    val canReact: Boolean = false,
    val reactions: List<GithubReactionGroup> = emptyList(),
    val avatarUrl: String? = null,
    val createdAt: String? = null,
    val authorAssociation: String? = null,
    val labels: List<String> = emptyList(),
    val commentsCount: Int = 0,
    val answerId: String?,
)

@Serializable
data class GithubDiscussionPoll(
    val id: String,
    val question: String,
    val viewerHasVoted: Boolean,
    val totalVoteCount: Int,
    val viewerCanVote: Boolean,
    val options: List<GithubDiscussionPollOption>,
)

@Serializable
data class GithubDiscussionPollOption(
    val id: String,
    val option: String,
    val viewerHasVoted: Boolean,
    val totalVoteCount: Int,
)

class DiscussionRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val cache: CachedDataRepository,
) {
  fun mutations(account: String): MutationCoordinator = cache.mutations(account)
  suspend fun addComment(account: String, discussionId: String, parentId: String?, body: String) {
    val session = GithubGraphQlSession(api, tokens, account)
    val data =
        session.execute(
            if (parentId == null) AddDiscussionComment else AddDiscussionReply,
            buildJsonObject {
              put("discussionId", discussionId)
              put("body", body)
              parentId?.let { put("parentCommentId", it) }
            },
            write = true,
        )
    val result = data["addDiscussionComment"] as? JsonObject
    val comment = result?.get("comment") as? JsonObject
    if (comment?.graphText("id").isNullOrBlank())
        throw SerializationException("Missing discussion comment confirmation")
    session.ensureCurrent()
    cache.changes(account).publish(DataDomain.Work)
  }

  suspend fun setUpvote(
      account: String,
      discussionId: String,
      targetUpvoted: Boolean,
  ): Pair<Boolean, Int> = toggleUpvote(account, discussionId, !targetUpvoted)

  suspend fun toggleUpvote(
      account: String,
      discussionId: String,
      upvoted: Boolean,
  ): Pair<Boolean, Int> {
    val session = GithubGraphQlSession(api, tokens, account)
    val field = if (upvoted) "removeUpvote" else "addUpvote"
    val operation = if (upvoted) RemoveDiscussionUpvote else AddDiscussionUpvote
    val subject =
        session
            .execute(
                operation,
                buildJsonObject { put("id", discussionId) },
                write = true,
            )
            .graphObject(field)
            .graphObject("subject")
    if (subject.graphRequiredText("id") != discussionId)
        throw SerializationException("Discussion upvote target did not match")
    session.ensureCurrent()
    cache.changes(account).publish(DataDomain.Work)
    return Pair(
        subject.graphRequiredText("viewerHasUpvoted").toBooleanStrict(),
        subject.graphRequiredText("upvoteCount").toIntOrNull() ?: throw GithubPageException(),
    )
  }

  suspend fun setAnswer(account: String, discussionId: String, commentId: String, answer: Boolean): String? {
    val session = GithubGraphQlSession(api, tokens, account)
    val field = if (answer) "markDiscussionCommentAsAnswer" else "unmarkDiscussionCommentAsAnswer"
    val operation = if (answer) MarkDiscussionCommentAsAnswer else UnmarkDiscussionCommentAsAnswer
    val discussion =
        session
            .execute(operation, buildJsonObject { put("id", commentId) }, write = true)
            .graphObject(field)
            .graphObject("discussion")
    if (discussion.graphRequiredText("id") != discussionId) throw GithubPageException()
    val answerId = discussion.answerId()
    session.ensureCurrent()
    return answerId
  }

  suspend fun votePoll(
      account: String,
      pollId: String,
      optionId: String,
  ): GithubDiscussionPoll {
    val session = GithubGraphQlSession(api, tokens, account)
    val pollOption =
        session
            .execute(
                AddDiscussionPollVote,
                buildJsonObject { put("option_id", optionId) },
                write = true,
            )
            .graphObject("addDiscussionPollVote")
            .graphObject("pollOption")
    if (pollOption.graphRequiredText("id") != optionId)
        throw SerializationException("Discussion poll option did not match")
    val poll = pollOption.graphObject("poll")
    if (poll.graphRequiredText("id") != pollId)
        throw SerializationException("Discussion poll target did not match")
    val result = poll.discussionPoll()
    if (!result.viewerHasVoted || result.options.none { it.id == optionId && it.viewerHasVoted })
        throw SerializationException("Discussion poll vote was not confirmed")
    session.ensureCurrent()
    cache.changes(account).publish(DataDomain.Work)
    return result
  }

  fun reactionUpdates(account: String): GithubReactionUpdates = cache.reactionUpdates(account)

  suspend fun toggleReaction(
      account: String,
      subjectId: String,
      content: GithubReactionContent,
      reacted: Boolean,
  ): List<GithubReactionGroup> =
      GithubGraphQlSession(api, tokens, account).toggleGithubReaction(subjectId, content, reacted)

  suspend fun reactions(account: String, subjectId: String): List<GithubReactionGroup> {
    val node = GithubGraphQlSession(api, tokens, account).execute(
        "query DiscussionReactions(\u0024id: ID!) { node(id: \u0024id) { id ... on Reactable { $ReactionFragment } } }",
        buildJsonObject { put("id", subjectId) },
    ).graphObject("node")
    if (node.graphRequiredText("id") != subjectId) throw GithubPageException()
    return node.reactionGroups()
  }

  fun resource(account: String, owner: String, name: String, number: Int, parentId: String?) =
      cache.resource(account, "discussion-detail.v6:${owner.lowercase()}/${name.lowercase()}:$number:${parentId.orEmpty()}",
          GithubDiscussionDetail.serializer(), tags = setOf(workCacheTag("Discussion", owner, name, number)))

  suspend fun load(account: String, owner: String, name: String, number: Int, parentId: String?, force: Boolean = false): GithubDiscussionDetail =
      resource(account, owner, name, number, parentId).await(force) { loadNetwork(account, owner, name, number, parentId) }

  private suspend fun loadNetwork(
      account: String,
      owner: String,
      name: String,
      number: Int,
      parentId: String?,
  ): GithubDiscussionDetail {
    val session = GithubGraphQlSession(api, tokens, account)
    val parentQuery =
        if (parentId != null)
            """
            node(id: ${'$'}parent) { ... on DiscussionComment {
                $CommentFields replies { totalCount } discussion { id }
            } }
        """
        else ""
    val data =
        session.execute(
            """
            query DiscussionDetail(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!${if (parentId != null) ", ${'$'}parent: ID!" else ""}) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    discussion(number: ${'$'}number) {
                        id title url bodyHTML(unfurlReferences: true) closed createdAt authorAssociation
                        answer { id }
                        viewerCanUpvote viewerHasUpvoted upvoteCount
                        viewerCanReact
                        reactionGroups {
                            content viewerHasReacted reactors(first: 1) { totalCount }
                        }
                        poll {
                            id question viewerHasVoted totalVoteCount viewerCanVote
                            options(first: 8) { nodes { id option viewerHasVoted totalVoteCount } }
                        }
                        author { login avatarUrl } category { name }
                        labels(first: 3) { nodes { name } }
                        comments { totalCount }
                    }
                }
                $parentQuery
            }
        """
                .trimIndent(),
            buildJsonObject {
              put("owner", owner)
              put("name", name)
              put("number", number)
              parentId?.let { put("parent", it) }
            },
        )
    val discussion = data.graphObject("repository").graphObject("discussion")
    val parent =
        if (parentId == null) null
        else
            data
                .graphObject("node")
                .also {
                  if (
                      it.graphObject("discussion").graphRequiredText("id") !=
                          discussion.graphRequiredText("id")
                  ) {
                    throw GithubPageException()
                  }
                }
                .let { it.comment(it.graphObject("replies").replyCount()) }
    return GithubDiscussionDetail(
        discussion.graphRequiredText("id"),
        discussion.graphRequiredText("title"),
        discussion.graphRequiredText("url"),
        discussion.graphText("bodyHTML").orEmpty(),
        (discussion["author"] as? JsonObject)?.graphText("login"),
        discussion.graphObject("category").graphRequiredText("name"),
        discussion["closed"]?.jsonPrimitive?.booleanOrNull ?: throw GithubPageException(),
        parent,
        discussion.graphRequiredText("viewerHasUpvoted").toBooleanStrict(),
        discussion.graphRequiredText("viewerCanUpvote").toBooleanStrict(),
        discussion.graphRequiredText("upvoteCount").toIntOrNull() ?: throw GithubPageException(),
        (discussion["poll"] as? JsonObject)?.discussionPoll(),
        discussion.graphRequiredText("viewerCanReact").toBooleanStrict(),
        discussion.reactionGroups(),
        avatarUrl = (discussion["author"] as? JsonObject)?.graphText("avatarUrl"),
        createdAt = discussion.graphText("createdAt"),
        authorAssociation = discussion.graphText("authorAssociation"),
        labels =
            (discussion["labels"] as? JsonObject)?.graphNodes().orEmpty().mapNotNull {
              it.graphText("name")
            },
        commentsCount = discussion.graphObject("comments").replyCount(),
        answerId = discussion.answerId(),
    )
  }

  fun comments(
      scope: CoroutineScope,
      account: String,
      id: String,
      replies: Boolean,
  ): ThreadWindow<GithubDiscussionComment> {
    val session = GithubGraphQlSession(api, tokens, account)
    val type = if (replies) "DiscussionComment" else "Discussion"
    val field = if (replies) "replies" else "comments"
    val replySummary =
        if (replies) "" else "replies(last: 3) { totalCount nodes { author { login avatarUrl } } }"
    return ThreadWindow(scope, GithubDiscussionComment::id) { _, before ->
      val data =
          session.execute(
              """
                query DiscussionThread(${'$'}id: ID!, ${'$'}before: String) {
                    node(id: ${'$'}id) { ... on $type {
                        $field(last: 30, before: ${'$'}before) {
                            pageInfo { hasPreviousPage startCursor } nodes { $CommentFields $replySummary }
                        }
                    } }
                }
            """
                  .trimIndent(),
              buildJsonObject {
                put("id", id)
                before?.let { put("before", it) }
              },
          )
      val connection = data.graphObject("node").graphObject(field)
      ThreadPage(
          connection.graphNodes().map {
            if (replies) it.comment() else it.commentWithReplySummary()
          },
          before = connection.graphObject("pageInfo").threadCursor(previous = true),
          after = null,
      )
    }
  }
}

// GitHub Android 1.283.0-beta (10351): defpackage/gj0.java:45, defpackage/du0.java:46.
private const val AddDiscussionComment =
    """
    mutation AddDiscussionComment(${'$'}discussionId: ID!, ${'$'}body: String!) {
        addDiscussionComment(input: { discussionId: ${'$'}discussionId, body: ${'$'}body }) {
            comment { id }
        }
    }
"""

private const val AddDiscussionReply =
    """
    mutation AddReplyToDiscussionComment(
        ${'$'}discussionId: ID!, ${'$'}body: String!, ${'$'}parentCommentId: ID!
    ) {
        addDiscussionComment(input: {
            discussionId: ${'$'}discussionId, body: ${'$'}body, replyToId: ${'$'}parentCommentId
        }) {
            comment { id }
        }
    }
"""

// GitHub Android 1.283.0-beta (10351): defpackage/p41.java:41, defpackage/vuu.java:41.
private const val AddDiscussionUpvote =
    """
    mutation AddUpvoteDiscussion(${'$'}id: ID!) {
        addUpvote(input: { subjectId: ${'$'}id }) {
            subject { ... on Discussion { id viewerHasUpvoted upvoteCount } }
        }
    }
"""

private const val RemoveDiscussionUpvote =
    """
    mutation RemoveUpvoteDiscussion(${'$'}id: ID!) {
        removeUpvote(input: { subjectId: ${'$'}id }) {
            subject { ... on Discussion { id viewerHasUpvoted upvoteCount } }
        }
    }
"""

// GitHub Android 1.283.0-beta (10351): defpackage/x9m.java:42, defpackage/u150.java:42.
private const val MarkDiscussionCommentAsAnswer =
    """
    mutation MarkDiscussionCommentAsAnswer(${'$'}id: ID!) {
        markDiscussionCommentAsAnswer(input: { id: ${'$'}id }) { discussion { id answer { id } } }
    }
"""

private const val UnmarkDiscussionCommentAsAnswer =
    """
    mutation UnmarkDiscussionCommentAsAnswer(${'$'}id: ID!) {
        unmarkDiscussionCommentAsAnswer(input: { id: ${'$'}id }) { discussion { id answer { id } } }
    }
"""

private fun JsonObject.answerId(): String? = when (val answer = this["answer"]) {
    JsonNull -> null
    is JsonObject -> answer.graphRequiredText("id")
    else -> throw SerializationException("Missing discussion answer field")
}

private fun JsonObject.replyCount() =
    this["totalCount"]?.jsonPrimitive?.intOrNull ?: throw GithubPageException()

private fun JsonObject.discussionPoll() =
    GithubDiscussionPoll(
        id = graphRequiredText("id"),
        question = graphRequiredText("question"),
        viewerHasVoted = graphRequiredText("viewerHasVoted").toBooleanStrict(),
        totalVoteCount =
            graphRequiredText("totalVoteCount").toIntOrNull() ?: throw GithubPageException(),
        viewerCanVote = graphRequiredText("viewerCanVote").toBooleanStrict(),
        options =
            graphObject("options").graphNodes().map {
              GithubDiscussionPollOption(
                  id = it.graphRequiredText("id"),
                  option = it.graphRequiredText("option"),
                  viewerHasVoted = it.graphRequiredText("viewerHasVoted").toBooleanStrict(),
                  totalVoteCount =
                      it.graphRequiredText("totalVoteCount").toIntOrNull()
                          ?: throw GithubPageException(),
              )
            },
    )

private const val AddDiscussionPollVote =
    """
    mutation AddDiscussionPollVote(${'$'}option_id: ID!) {
        addDiscussionPollVote(input: { pollOptionId: ${'$'}option_id }) {
            pollOption {
                id
                poll {
                    id question viewerHasVoted totalVoteCount viewerCanVote
                    options(first: 8) { nodes { id option viewerHasVoted totalVoteCount } }
                }
            }
        }
    }
"""

private fun JsonObject.commentWithReplySummary(): GithubDiscussionComment {
  val replies = graphObject("replies")
  val authors = replies.graphNodes().mapNotNull { reply ->
    val author = reply["author"] as? JsonObject ?: return@mapNotNull null
    UserDto(login = author.graphRequiredText("login"), avatarUrl = author.graphText("avatarUrl"))
  }
  return comment(replies.replyCount(), authors)
}

private fun JsonObject.comment(
    count: Int = 0,
    replyAuthors: List<UserDto> = emptyList(),
): GithubDiscussionComment {
  val author = get("author") as? JsonObject
  return GithubDiscussionComment(
      graphRequiredText("id"),
      graphText("bodyHTML").orEmpty(),
      author?.graphText("login"),
      author?.graphText("avatarUrl"),
      graphRequiredText("createdAt"),
      count,
      replyAuthors,
      graphRequiredText("isAnswer").toBooleanStrict(),
      graphRequiredText("viewerCanMarkAsAnswer").toBooleanStrict(),
      graphRequiredText("viewerCanUnmarkAsAnswer").toBooleanStrict(),
      graphRequiredText("viewerCanReact").toBooleanStrict(),
      reactionGroups(),
  )
}

private const val CommentFields =
    """
    id bodyHTML(unfurlReferences: true) createdAt
    isAnswer viewerCanMarkAsAnswer viewerCanUnmarkAsAnswer
    viewerCanReact $ReactionFragment
    author { login avatarUrl }
"""
