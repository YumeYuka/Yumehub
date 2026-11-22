package moe.yumeyuka.yumehub.data.repository

import androidx.paging.PagingData
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.CollaborationCommentDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationReactionDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestCheckRunDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewCommentDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewDto
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CachedResource
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

data class WorkMergeMessage(val headline: String, val body: String)

/** 只缓存服务端 DTO；草稿、选区、分节及提交状态仍由页面持有。 */
class WorkDetailRepository(
    private val api: GitHubGraphQlApi,
    private val cache: CachedDataRepository,
    private val paging: PagingRepository,
    private val diffs: GithubDiffRepository,
    private val tokens: TokenStore,
) {
  fun resource(
      account: String,
      owner: String,
      repository: String,
      kind: CollaborationKind,
      number: Int,
  ): CachedResource<CollaborationItemDto> {
    val target = "${owner.lowercase(Locale.ROOT)}/${repository.lowercase(Locale.ROOT)}/$number"
    return cache.resource(
        account = account,
        key = "work-detail-graphql.v1:${kind.name}:$target",
        serializer = CollaborationItemDto.serializer(),
        maxAgeMillis = DetailMaxAgeMillis,
    )
  }

  fun detail(
      account: String,
      owner: String,
      repository: String,
      kind: CollaborationKind,
      number: Int,
  ): Detail = Detail(account, owner, repository, kind, number)

  inner class Detail
  internal constructor(
      private val account: String,
      private val owner: String,
      private val repository: String,
      private val kind: CollaborationKind,
      private val number: Int,
  ) {
    private val graph by lazy {
      WorkDetailGraphQl(
          GithubGraphQlSession(api, tokens, account),
          owner,
          repository,
          kind,
          number,
      )
    }
    private val target =
        "${owner.lowercase(Locale.ROOT)}/${repository.lowercase(Locale.ROOT)}/$number"
    private val resource: CachedResource<CollaborationItemDto> =
        this@WorkDetailRepository.resource(account, owner, repository, kind, number)
    val state = resource.state
    val reactionUpdates: GithubReactionUpdates
      get() = cache.reactionUpdates(account)
    private val commentsKey = "comments-graphql.v1:${kind.name}:$target"
    private val reviewsKey = "reviews-graphql.v1:${kind.name}:$target"
    private val timelineKey = "work-timeline-graphql.v1:${kind.name}:$target"

    private fun filesKey(head: String): String = "files-graphql.v1:${kind.name}:$target:$head"

    private fun checksKey(head: String) = "checks-graphql.v1:${kind.name}:$target:$head"

    fun accepts(item: CollaborationItemDto): Boolean =
        item.number == number &&
            when (kind) {
              CollaborationKind.Issue -> item.pullRequest == null && item.head == null
              CollaborationKind.PullRequest -> item.head != null
            }

    fun refresh(force: Boolean = false): Job {
      // 显式刷新不能合并进此前只准备读取新鲜缓存的请求。
      if (force) resource.invalidate()
      return resource.refresh(force) { load() }
    }

    fun refreshEntries() {
      paging.invalidate(account, timelineKey)
      paging.invalidate(account, commentsKey)
      if (kind == CollaborationKind.PullRequest) {
        paging.invalidate(account, reviewsKey)
        resource.cached?.head?.sha?.takeUnless(String::isBlank)?.let { head ->
          paging.invalidate(account, "work-commits.v1:$target:$head")
          paging.invalidate(account, filesKey(head))
          paging.invalidate(account, checksKey(head))
        }
      }
    }

    private suspend fun load(): CollaborationItemDto {
      val item = graph.detail()
      if (!accepts(item)) throw WorkDetailTypeMismatchException()
      return if (cache.changes(account).isMerged(WorkTarget(owner, repository, number, kind))) {
        item.copy(merged = true, state = "closed")
      } else item
    }

    suspend fun requestReview(login: String) {
      val latest = load()
      if (kind != CollaborationKind.PullRequest || latest.state != "open")
          throw WorkDetailReviewChangedException()
      try {
        graph.requestReview(requireNotNull(latest.nodeId), login)
      } finally {
        changed()
      }
    }

    suspend fun editPullRequest(snapshot: CollaborationItemDto, field: String, value: String) {
      val latest = load()
      if (
          kind != CollaborationKind.PullRequest ||
              latest.nodeId != snapshot.nodeId ||
              !latest.viewerCanEdit ||
              latest.updatedAt != snapshot.updatedAt ||
              (field == "baseRefName" && (!latest.viewerCanChangeBase || latest.state != "open"))
      )
          throw WorkDetailReviewChangedException()
      try {
        graph.editPullRequest(requireNotNull(latest.nodeId), field, value)
      } finally {
        changed()
      }
    }

    suspend fun editBody(snapshot: CollaborationItemDto, body: String) {
      val latest = load()
      if (
          latest.nodeId != snapshot.nodeId ||
              !latest.viewerCanEdit ||
              latest.updatedAt != snapshot.updatedAt
      )
          throw WorkDetailReviewChangedException()
      try {
        graph.editBody(requireNotNull(latest.nodeId), body)
      } finally {
        changed()
      }
    }

    suspend fun editTitle(snapshot: CollaborationItemDto, title: String) {
      val latest = load()
      if (
          latest.nodeId != snapshot.nodeId ||
              !latest.viewerCanEdit ||
              latest.updatedAt != snapshot.updatedAt
      )
          throw WorkDetailReviewChangedException()
      try {
        graph.editTitle(requireNotNull(latest.nodeId), title)
      } finally {
        changed()
      }
    }

    suspend fun setLocked(snapshot: CollaborationItemDto) {
      val latest = load()
      if (!latest.viewerCanModerate || latest.locked != snapshot.locked)
          throw WorkDetailReviewChangedException()
      try {
        graph.setLocked(requireNotNull(latest.nodeId), !latest.locked)
      } finally {
        changed()
      }
    }

    suspend fun closePullRequest(snapshot: CollaborationItemDto) {
      val latest = load()
      if (
          kind != CollaborationKind.PullRequest ||
              !latest.viewerCanClose ||
              latest.state != "open" ||
              latest.head?.sha != snapshot.head?.sha ||
              latest.merged
      )
          throw WorkDetailReviewChangedException()
      try {
        graph.closePullRequest(requireNotNull(latest.nodeId))
      } finally {
        changed()
      }
    }

    suspend fun closeIssue(snapshot: CollaborationItemDto) {
      val latest = load()
      if (
          kind != CollaborationKind.Issue ||
              !latest.viewerCanClose ||
              latest.state != "open" ||
              latest.updatedAt != snapshot.updatedAt
      )
          throw WorkDetailReviewChangedException()
      try {
        graph.closeIssue(requireNotNull(latest.nodeId))
      } finally {
        changed()
      }
    }

    suspend fun properties() = graph.properties()

    suspend fun linkedOptions(query: String, after: String?) = graph.linkedOptions(query, after)

    suspend fun subIssueOptions(query: String, after: String?) = graph.subIssueOptions(query, after)

    suspend fun propertyOptions(property: WorkProperty) = graph.propertyOptions(property)

    /** 页面退出后仍继续提交；失败不阻塞导航，下一次刷新会回到服务端实际值。 */
    fun savePropertiesInBackground(
        changes: Map<WorkProperty, List<WorkPropertyOption>>,
        originals: Map<WorkProperty, List<WorkPropertyOption>>,
    ) {
      cache.scope.launch {
        try {
          changes.forEach { (property, selected) ->
            graph.saveProperty(property, originals[property].orEmpty(), selected)
          }
        } catch (error: CancellationException) {
          throw error
        } catch (_: Exception) {
          // 后台保存不持有页面；失败由后续刷新呈现，不在退出时等待回调。
        } finally {
          changed()
        }
      }
    }

    fun timeline(scope: CoroutineScope): ThreadWindow<WorkTimelineEntry> =
        ThreadWindow(scope, WorkTimelineEntry::key) { direction, cursor ->
              graph.timeline(direction, cursor)
            }
            .also { window ->
              paging
                  .revisions(account, timelineKey)
                  .map { it.data }
                  .distinctUntilChanged()
                  .onEach { window.load(ThreadDirection.Latest) }
                  .launchIn(scope)
            }

    fun comments(): Flow<PagingData<CollaborationCommentDto>> =
        githubGraphQlPager(
            account,
            commentsKey,
            paging,
            { requireNotNull(it.nodeId) },
        ) {
          graph::comments
        }

    fun reviews(): Flow<PagingData<PullRequestReviewDto>> =
        githubGraphQlPager(
            account,
            reviewsKey,
            paging,
            { requireNotNull(it.nodeId) },
        ) {
          graph::reviews
        }

    fun commits(head: String) =
        githubGraphQlPager(
            account,
            "work-commits.v1:$target:$head",
            paging,
            RepositoryActivityEntry::id,
        ) {
          { after -> graph.commits(head, after) }
        }

    fun files(head: String): Flow<PagingData<PullRequestFileDto>> =
        paging.pages(account, filesKey(head)) { context ->
          PullRequestDiffPagingSource(
              context,
              diffs.createPullRequestPageLoader(account, owner, repository, number, head),
          )
        }

    fun checks(head: String): Flow<PagingData<PullRequestCheckRunDto>> =
        githubGraphQlPager(
            account,
            checksKey(head),
            paging,
            { it.id },
        ) {
          { after -> graph.checks(head, after) }
        }

    private suspend fun requireHead(
        head: String,
        force: Boolean = true,
    ): CollaborationItemDto {
      val snapshot = state.value
      val age = System.currentTimeMillis() - snapshot.updatedAt
      val cached =
          snapshot.data?.takeIf {
            !force &&
                snapshot.error == null &&
                snapshot.updatedAt > 0L &&
                age >= 0L &&
                age < DetailMaxAgeMillis &&
                accepts(it)
          }
      val latest = cached ?: resource.await(force = force) { load() }
      if (latest.head?.sha != head) {
        paging.invalidate(account, timelineKey)
        throw WorkDetailReviewChangedException()
      }
      return latest
    }

    suspend fun mergeMessage(id: String, method: String): WorkMergeMessage =
        graph.mergeMessage(id, method)

    suspend fun merge(head: String, base: String?, method: String, message: WorkMergeMessage?) {
      val latest = requireHead(head)
      val allowed =
          when (method) {
            "MERGE" -> latest.mergeCommitAllowed
            "SQUASH" -> latest.squashMergeAllowed
            "REBASE" -> latest.rebaseMergeAllowed
            else -> false
          }
      if (
          kind != CollaborationKind.PullRequest ||
              latest.baseRefName != base ||
              latest.state != "open" ||
              latest.draft ||
              !latest.viewerCanMerge ||
              !allowed ||
              latest.mergeable != "MERGEABLE" ||
              latest.mergeStateStatus != "CLEAN" ||
              latest.reviewDecision in setOf("REVIEW_REQUIRED", "CHANGES_REQUESTED")
      )
          throw WorkDetailReviewChangedException()
      try {
        graph.merge(requireNotNull(latest.nodeId), head, method, message)
      } catch (error: GithubPageException) {
        throw IOException("Unable to confirm merge result", error)
      }
      resource.cached?.let { resource.update(it.copy(merged = true, state = "closed")) }
      cache.changes(account).confirmMerged(WorkTarget(owner, repository, number, kind))
      changed()
    }

    suspend fun createComment(body: String): CollaborationCommentDto {
      val created = graph.createComment(requireNotNull(state.value.data?.nodeId), body)
      changed(commentsKey)
      return created
    }

    suspend fun updateComment(
        comment: CollaborationCommentDto,
        body: String,
    ): CollaborationCommentDto {
      requireUnchanged(comment)
      val updated = graph.updateComment(requireNotNull(comment.nodeId), body)
      changed(commentsKey)
      return updated
    }

    suspend fun toggleReaction(
        nodeId: String,
        content: String,
        reacted: Boolean,
    ): List<CollaborationReactionDto> = graph.toggleCommentReaction(nodeId, content, reacted)

    suspend fun deleteComment(comment: CollaborationCommentDto) {
      requireUnchanged(comment)
      graph.deleteComment(requireNotNull(comment.nodeId))
      changed(commentsKey)
    }

    suspend fun minimizeComment(comment: CollaborationCommentDto, classifier: String) {
      requireUnchanged(comment)
      graph.minimizeComment(requireNotNull(comment.nodeId), classifier)
      changed(commentsKey)
    }

    private suspend fun requireUnchanged(comment: CollaborationCommentDto) {
      val latest = graph.readComment(requireNotNull(comment.nodeId))
      if (latest.body != comment.body || latest.updatedAt != comment.updatedAt) {
        paging.invalidate(account, commentsKey)
        paging.invalidate(account, timelineKey)
        throw WorkDetailCommentChangedException()
      }
    }

    suspend fun createReview(
        commit: String,
        event: String,
        body: String?,
    ): PullRequestReviewDto {
      val latest = requireHead(commit)
      if (latest.state != "open" || latest.draft || latest.locked) {
        paging.invalidate(account, timelineKey)
        throw WorkDetailReviewChangedException()
      }
      val created = graph.createReview(requireNotNull(latest.nodeId), commit, event, body)
      changed(reviewsKey, checksKey(commit))
      return created
    }

    suspend fun createLineComment(
        body: String,
        commit: String,
        path: String,
        line: Int,
        side: String,
    ): PullRequestReviewCommentDto {
      val latest = requireHead(commit)
      if (latest.state != "open" || latest.locked) {
        paging.invalidate(account, timelineKey)
        throw WorkDetailReviewChangedException()
      }
      val created = graph.createLineComment(requireNotNull(latest.nodeId), body, path, line, side)
      changed(reviewsKey, filesKey(commit))
      return created
    }

    private fun changed(vararg keys: String) {
      // 失效会淘汰旧请求与旧 hydrate，之后仅新的服务端结果可以回写。
      refresh(force = true)
      paging.invalidate(account, timelineKey)
      keys.forEach { paging.invalidate(account, it) }
      cache.invalidateMatching(account) {
        it.startsWith("notification-previews.") || it.startsWith("repository-home-")
      }
      cache
          .changes(account)
          .publish(DataDomain.Work, DataDomain.Notifications, DataDomain.Repositories)
    }
  }

  private companion object {
    const val DetailMaxAgeMillis = 60_000L
  }
}

class WorkDetailTypeMismatchException : Exception()

class WorkDetailReviewChangedException : Exception()

class WorkDetailCommentChangedException : Exception()
