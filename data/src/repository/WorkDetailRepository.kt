package moe.yumeyuka.yumehub.data.repository

import androidx.paging.PagingData
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.CollaborationCommentDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.IssueField
import moe.yumeyuka.yumehub.data.api.dto.IssueFieldValue
import moe.yumeyuka.yumehub.data.api.dto.IssueFields
import moe.yumeyuka.yumehub.data.api.dto.IssuePropertyChoices
import moe.yumeyuka.yumehub.data.api.dto.PullRequestCheckRunDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewCommentDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewDto
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.api.dto.canMerge
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CachedResource
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.NullableValue
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.cache.workCacheTag
import moe.yumeyuka.yumehub.data.cache.workListCacheTag
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
        tags = setOf(workCacheTag(kind.name, owner, repository, number)),
    )
  }

  fun detail(
      account: String,
      owner: String,
      repository: String,
      kind: CollaborationKind,
      number: Int,
  ): Detail = Detail(account, owner, repository, kind, number)

  suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

  fun rowProjection(account: String, target: WorkTarget): Flow<WorkRowProjection> {
    val base = resource(account, target.owner, target.repository, target.kind, target.number)
    val confirmed = channelFlow {
      // Replay disk state independently of live writes and never fetch full detail from a row.
      if (base.cached == null) launch { base.restore() }
      base.state.collect { send(it.data) }
    }.distinctUntilChanged()
    return workRowProjection(cache, account, target, confirmed)
  }

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
    val workTarget: String = "${kind.name.lowercase(Locale.ROOT)}:$target"
    val keys = WorkMutationKeys(workTarget)
    val mutationTags =
        setOf(
            workCacheTag(kind.name, owner, repository, number),
            repositoryCacheTag(owner, repository),
            workListCacheTag(owner, repository),
            moe.yumeyuka.yumehub.data.cache.CacheTag(DataDomain.Notifications, null),
        )
    private val workTag = workCacheTag(kind.name, owner, repository, number)
    private val resource: CachedResource<CollaborationItemDto> =
        this@WorkDetailRepository.resource(account, owner, repository, kind, number)
    val state = resource.state
    private val commentsKey = "comments-graphql.v1:${kind.name}:$target"
    private val reviewsKey = "reviews-graphql.v1:${kind.name}:$target"
    private val timelineKey = "work-timeline-graphql.v1:${kind.name}:$target"

    fun mutations(): MutationCoordinator = cache.mutations(account)

    suspend fun readDetail(): CollaborationItemDto = load()

    suspend fun readComment(commentNodeId: String): CollaborationCommentDto =
        graph.readComment(commentNodeId)

    fun commentWrites(id: String): Flow<WorkCommentWrite> {
      val coord = mutations()
      return combine(coord.watch(keys.comment(id)), coord.watchCommand(keys.deleteComment(id)), coord.pending) { edit, deletion, pending ->
        val marker = if (edit.phase == WritePhase.UNCERTAIN) pending.firstOrNull { it.kind == MutationKind.COMMENT && it.target == id } else null
        WorkCommentWrite(edit, deletion, marker?.operationId)
      }
    }

    val reviewsPagingKey: String
        get() = reviewsKey

    fun commitsPagingKey(head: String): String = "work-commits.v1:$target:$head"

    fun filesPagingKey(head: String): String = filesKey(head)

    fun checksPagingKey(head: String): String = checksKey(head)

    fun revalidation(key: String) = paging.revalidation(account, key)

    fun retryRevalidation(key: String) = paging.retryRevalidation(account, key)

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
      return resource.refresh(force) { readForDisplay() }
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

    private suspend fun readForDisplay(): CollaborationItemDto {
      val coord = mutations()
      val title = coord.beginRead(keys.title)
      val body = coord.beginRead(keys.body)
      val base = coord.beginRead(keys.base)
      val locked = coord.beginRead(keys.locked)
      val state = coord.beginRead(keys.state)
      return load().also { item ->
        coord.publishConfirmed(keys.title, title, item.title)
        coord.publishConfirmed(keys.body, body, item.body.orEmpty())
        item.baseRefName?.let { coord.publishConfirmed(keys.base, base, it) }
        coord.publishConfirmed(keys.locked, locked, item.locked)
        coord.publishConfirmed(keys.state, state, item.state)
      }
    }

    private fun patchConfirmed(transform: (CollaborationItemDto) -> CollaborationItemDto) {
      cache.locked {
        val old = resource.cached ?: return@locked
        val next = transform(old)
        if (old != next) resource.update(next)
      }
    }

    private suspend fun load(): CollaborationItemDto {
      val item = graph.detail()
      if (!accepts(item)) throw WorkDetailTypeMismatchException()
      return if (cache.changes(account).isMerged(WorkTarget(owner, repository, number, kind))) {
        item.copy(merged = true, state = "closed")
      } else item
    }

    suspend fun editPullRequest(snapshot: CollaborationItemDto, field: String, value: String): String {
      val latest = load()
      if (
          kind != CollaborationKind.PullRequest ||
              latest.nodeId != snapshot.nodeId ||
              !latest.viewerCanEdit ||
              latest.updatedAt != snapshot.updatedAt ||
              (field == "baseRefName" && (!latest.viewerCanChangeBase || latest.state != "open"))
      ) {
        changed()
        throw WorkDetailReviewChangedException()
      }
      return graph.editPullRequest(requireNotNull(latest.nodeId), field, value).also { actual ->
        patchConfirmed { if (field == "title") it.copy(title = actual) else it.copy(baseRefName = actual) }
        changed()
      }
    }

    suspend fun editBody(snapshot: CollaborationItemDto, body: String): String {
      val latest = load()
      if (
          latest.nodeId != snapshot.nodeId ||
              !latest.viewerCanEdit ||
              latest.updatedAt != snapshot.updatedAt
      ) {
        changed()
        throw WorkDetailReviewChangedException()
      }
      return graph.editBody(requireNotNull(latest.nodeId), body).also { actual ->
        patchConfirmed { it.copy(body = actual, bodyHtml = null) }
        changed()
      }
    }

    suspend fun editTitle(snapshot: CollaborationItemDto, title: String): String {
      val latest = load()
      if (
          latest.nodeId != snapshot.nodeId ||
              !latest.viewerCanEdit ||
              latest.updatedAt != snapshot.updatedAt
      ) {
        changed()
        throw WorkDetailReviewChangedException()
      }
      return graph.editTitle(requireNotNull(latest.nodeId), title).also { actual ->
        patchConfirmed { it.copy(title = actual) }
        changed()
      }
    }

    suspend fun setLocked(snapshot: CollaborationItemDto, locked: Boolean): Boolean {
      val latest = load()
      if (latest.nodeId != snapshot.nodeId || !latest.viewerCanModerate || latest.locked != snapshot.locked) {
        changed()
        throw WorkDetailReviewChangedException()
      }
      return graph.setLocked(requireNotNull(latest.nodeId), locked).also { actual ->
        patchConfirmed { it.copy(locked = actual) }
        changed()
      }
    }

    suspend fun closePullRequest(snapshot: CollaborationItemDto): String {
      val latest = load()
      if (
          kind != CollaborationKind.PullRequest ||
              !latest.viewerCanClose ||
              latest.state != "open" ||
              latest.head?.sha != snapshot.head?.sha ||
              latest.merged
      ) {
        changed()
        throw WorkDetailReviewChangedException()
      }
      return graph.closePullRequest(requireNotNull(latest.nodeId)).also { actual ->
        patchConfirmed { it.copy(state = actual) }
        changed()
      }
    }

    suspend fun closeIssue(snapshot: CollaborationItemDto): String {
      val latest = load()
      if (
          kind != CollaborationKind.Issue ||
              !latest.viewerCanClose ||
              latest.state != "open" ||
              latest.updatedAt != snapshot.updatedAt
      ) {
        changed()
        throw WorkDetailReviewChangedException()
      }
      return graph.closeIssue(requireNotNull(latest.nodeId)).also { actual ->
        patchConfirmed { it.copy(state = actual) }
        changed()
      }
    }

    suspend fun properties() = mutations().let { coord ->
      val stamps = WorkProperty.entries.associateWith { coord.beginRead(keys.property(it)) }
      graph.properties().also { props -> props.values.forEach { (property, options) ->
        coord.publishConfirmed(keys.property(property), stamps.getValue(property), options)
      } }
    }

    fun submitProperty(property: WorkProperty, original: List<WorkPropertyOption>, selected: List<WorkPropertyOption>, issueId: String? = null): Boolean {
      val coord = mutations()
      val key = keys.property(property)
      return coord.submit(key, original, selected, mutationTags, send = { desired ->
        val candidates by lazy(LazyThreadSafetyMode.NONE) { (original + desired).associateBy { it.id } }
        graph.saveProperty(property, original, desired, expectedIssueId = issueId) { ids ->
          val partial = ids.map(candidates::getValue)
          coord.confirmStep(key, partial)
          patchConfirmed { it.withProperty(property, partial) }
        }
        afterWorkWrite { graph.propertyValues(property) }.also { actual ->
          patchConfirmed { it.withProperty(property, actual) }
        }
      }, readBack = { readProperty(property) })
    }

    suspend fun readProperty(property: WorkProperty) = graph.propertyValues(property)

    fun projectProperties(base: CollaborationItemDto): CollaborationItemDto {
      val coord = mutations()
      var item = base
      for (property in WorkProperty.entries) {
        val write = coord.current(keys.property(property))
        if (write.phase == WritePhase.PENDING || write.phase == WritePhase.UNCERTAIN) {
          write.visible?.let { item = item.withProperty(property, it) }
        }
      }
      val type = coord.current(keys.issueType)
      if (type.phase == WritePhase.PENDING || type.phase == WritePhase.UNCERTAIN) type.visible?.let {
        item = item.copy(issueType = it.value?.title, issueTypeColor = it.value?.color)
      }
      return item
    }

    suspend fun crossReferences() = graph.crossReferences()


    suspend fun linkedOptions(query: String, after: String?) = graph.linkedOptions(query, after)

    suspend fun subIssueOptions(query: String, after: String?) = graph.subIssueOptions(query, after)

    suspend fun propertyOptions(property: WorkProperty) = graph.propertyOptions(property)

    suspend fun reviewRequests() = mutations().let { coord ->
      val key = keys.property(WorkProperty.Reviewers)
      val stamp = coord.beginRead(key)
      graph.reviewRequests().also { coord.publishConfirmed(key, stamp, it.requested) }
    }

    suspend fun reviewerCandidates(query: String, after: String?) = graph.reviewerCandidates(query, after)

    suspend fun issueTypes() = mutations().let { coord ->
      val stamp = coord.beginRead(keys.issueType)
      graph.issueMetadata().types().also { coord.publishConfirmed(keys.issueType, stamp, NullableValue(it.selected.singleOrNull())) }
    }

    suspend fun issueFields(): IssueFields {
      val coord = mutations()
      val stamps = coord.beginKnownReads(MutationKind.ISSUE_FIELD, keys.fieldPrefix)
      return graph.issueMetadata().fields().also { result -> result.fields.forEach { field ->
        val key = keys.field(field.id)
        stamps[key.target]?.let { coord.publishConfirmed(key, it, NullableValue(field.value)) }
      } }
    }

    fun projectedFields(base: IssueFields): Flow<IssueFields> {
      if (base.fields.isEmpty()) return flowOf(base)
      val coord = mutations()
      return combine(base.fields.map { field -> coord.observe(keys.field(field.id)) }) { writes ->
        base.copy(fields = base.fields.mapIndexed { index, field ->
          val shown = writes[index].visible
          if (shown == null || shown.value == field.value) field else field.copy(value = shown.value)
        })
      }
    }

    suspend fun issueAssignees() = mutations().let { coord ->
      val key = keys.property(WorkProperty.Assignees)
      val stamp = coord.beginRead(key)
      graph.issueAssignees().also { coord.publishConfirmed(key, stamp, it.selected) }
    }

    suspend fun saveIssueType(baseline: IssuePropertyChoices, selected: WorkPropertyOption?): NullableValue<WorkPropertyOption> {
      graph.issueMetadata().saveType(baseline.issueId, baseline.selected.singleOrNull()?.id, selected?.id)
      patchConfirmed { it.copy(issueType = selected?.title, issueTypeColor = selected?.color) }
      return NullableValue(selected)
    }

    suspend fun saveIssueField(issueId: String, field: IssueField, value: IssueFieldValue?): IssueFieldValue? {
      graph.issueMetadata().saveField(issueId, field, value)
      return afterWorkWrite { readFieldValue(issueId, field.id) }
    }

    suspend fun readFieldValue(issueId: String, fieldId: String) = graph.issueMetadata().fieldValue(issueId, fieldId)

    suspend fun readIssueType() = NullableValue(graph.issueMetadata().types().selected.singleOrNull())

    // A denied verification read does not prove that the preceding successful mutation was denied.
    private suspend fun <T> afterWorkWrite(read: suspend () -> T): T = try {
      read()
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (error: Exception) { throw java.io.IOException("Could not confirm the updated work item", error) }

    fun reactionUpdates(): GithubReactionUpdates =
        this@WorkDetailRepository.cache.reactionUpdates(account)

    suspend fun headerProjects() {
      val key = keys.property(WorkProperty.Projects)
      val coord = mutations()
      val stamp = coord.beginRead(key)
      try {
        coord.publishConfirmed(key, stamp, graph.propertyValues(WorkProperty.Projects))
      } catch (error: kotlinx.coroutines.CancellationException) { throw error }
      catch (_: Exception) { /* Optional permission: retain the last known projects. */ }
    }

    suspend fun toggleReaction(
        subjectId: String,
        content: GithubReactionContent,
        reacted: Boolean,
    ): List<GithubReactionGroup> = graph.toggleReaction(subjectId, content, reacted)

    fun timeline(scope: CoroutineScope): ThreadWindow<WorkTimelineEntry> =
        ThreadWindow(scope, WorkTimelineEntry::key) { direction, cursor ->
              graph.timeline(direction, cursor)
            }
            .also { window ->
              paging
                  .revisions(account, timelineKey)
                  .onEach { window.load(ThreadDirection.Latest) }
                  .launchIn(scope)
            }

    fun comments(): Flow<PagingData<CollaborationCommentDto>> =
        githubGraphQlPager(
            account,
            commentsKey,
            paging,
            { requireNotNull(it.nodeId) },
            serializer = CollaborationCommentDto.serializer(),
            tags = setOf(workTag),
        ) {
          graph::comments
        }

    fun reviews(): Flow<PagingData<PullRequestReviewDto>> =
        githubGraphQlPager(
            account,
            reviewsKey,
            paging,
            { requireNotNull(it.nodeId) },
            serializer = PullRequestReviewDto.serializer(),
            tags = setOf(workTag),
        ) {
          graph::reviews
        }

    fun commits(head: String) =
        githubGraphQlPager(
            account,
            "work-commits.v1:$target:$head",
            paging,
            RepositoryActivityEntry::id,
            serializer = RepositoryActivityEntry.serializer(),
            tags = setOf(workTag),
        ) {
          { after -> graph.commits(head, after) }
        }

    fun files(head: String): Flow<PagingData<PullRequestFileDto>> =
        paging.pages(account, filesKey(head), tags = setOf(workTag)) { context ->
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
            serializer = PullRequestCheckRunDto.serializer(),
            tags = setOf(workTag),
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
      val latest = cached ?: resource.await(force = force) { readForDisplay() }
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
              !latest.canMerge() ||
              !allowed
      )
          throw WorkDetailReviewChangedException()
      graph.merge(requireNotNull(latest.nodeId), head, method, message)
      confirmMergedLocal()
    }

    suspend fun checkMerge(): moe.yumeyuka.yumehub.data.cache.CommandAccepted? {
      if (!readDetail().merged) return null
      confirmMergedLocal()
      return moe.yumeyuka.yumehub.data.cache.CommandAccepted
    }

    private fun confirmMergedLocal() {
      patchConfirmed { it.copy(merged = true, state = "closed") }
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
      cache.invalidateAndPersist(
          account,
          setOf(repositoryCacheTag(owner, repository)),
      )
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
