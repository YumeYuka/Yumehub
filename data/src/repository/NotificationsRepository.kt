package moe.yumeyuka.yumehub.data.repository

import java.lang.ref.WeakReference
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationPreviewDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CachedResource
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationRejectedException
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class NotificationThreadState(val unread: Boolean, val done: Boolean)

data class NotificationsQuery(
    val includeRead: Boolean = false,
    val done: Boolean = false,
    val repository: String? = null,
)

enum class NotificationThreadAction(internal val operation: String, internal val field: String) {
    MarkRead("MarkNotificationAsRead", "markNotificationAsRead"),
    MarkUnread("MarkNotificationAsUnread", "markNotificationAsUnread"),
    MarkDone("MarkNotificationAsDone", "markNotificationAsDone"),
    MarkUndone("MarkNotificationAsUndone", "markNotificationAsUndone");

    internal val inverse: NotificationThreadAction
        get() =
            when (this) {
                MarkRead -> MarkUnread
                MarkUnread -> MarkRead
                MarkDone -> MarkUndone
                MarkUndone -> MarkDone
            }
}

/** 页码只用于本地窗口；网络请求使用其对应的服务端游标。 */
@Serializable
data class NotificationsSnapshot(
    val notifications: List<NotificationDto> = emptyList(),
    val repositories: List<String> = emptyList(),
    val loadedFromPage: Int = 1,
    val loadedThroughPage: Int = 0,
    val nextCursor: String? = null,
    val cursorHistory: List<String> = emptyList(),
    val pagingPaused: Boolean = false,
    val pageItemCounts: List<Int> = emptyList(),
)

class NotificationsRepository(
    private val cache: CachedDataRepository,
    private val tokenStore: TokenStore,
    private val graphQlApi: GitHubGraphQlApi,
) {
    val account: String
        get() = tokenStore.accountLogin.value

    fun changes(account: String) = cache.changes(account)

    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    fun confirmedPreview(
        account: String,
        notification: NotificationDto,
        preview: NotificationPreviewDto,
    ): NotificationPreviewDto =
        if (notification.workTarget()?.let { cache.changes(account).isMerged(it) } == true) {
            preview.copy(merged = true, state = "MERGED", draft = false)
        } else preview

    fun resource(
        account: String,
        query: NotificationsQuery,
    ): CachedResource<NotificationsSnapshot> {
        val runtime = runtime(account)
        val resources = runtime.resources
        val identity = ResourceKey(account.lowercase(Locale.ROOT), query.normalized())
        return synchronized(resources) {
            runtime.reap()
            cache
                .resource(
                    account,
                    key = identity.query.cacheKey(),
                    serializer = NotificationsSnapshot.serializer(),
                    // 可见页面负责刷新节流；不要在网络完成后再叠加 60 秒 TTL。
                    maxAgeMillis = 0L,
                    tags = setOf(CacheTag(DataDomain.Notifications, null)),
                )
                .also { resources[identity] = WeakReference(it) }
        }
    }

    fun refresh(account: String, query: NotificationsQuery): Job =
        runtime(account).let { runtime ->
            val normalized = query.normalized()
            runtime.refresh(normalized) {
                runtime.mutex(normalized).withLock {
                    val resource = resource(account, query)
                    resource.refresh {
                        val cached = resource.state.value
                        val window = cached.data.refreshWindow(cached.updatedAt)
                        loadWindow(
                            account,
                            query,
                            window.fromPage,
                            window.throughPage,
                            window.previous,
                        )
                    }.join()
                }
            }
        }

    suspend fun loadPages(
        account: String,
        query: NotificationsQuery,
        fromPage: Int,
        throughPage: Int = fromPage,
        replace: Boolean = false,
    ): NotificationsSnapshot {
        require(fromPage > 0 && throughPage >= fromPage && throughPage - fromPage < 10)
        val runtime = runtime(account)
        // 不同翻页操作不能合并成同一个 key 下的另一种请求；等待者销毁不取消仓库操作。
        return runtime.scope
            .async {
                runtime.mutex(query.normalized()).withLock {
                    val resource = resource(account, query)
                    resource.await(force = true) {
                        val previous =
                            resource.cached?.takeIf {
                                !it.pagingPaused && resource.state.value.updatedAt > 0L
                            }
                        val next = previous?.loadedThroughPage?.plus(1)
                        val rolling =
                            replace && previous != null && next == throughPage &&
                                fromPage in (previous.loadedFromPage + 1)..previous.loadedThroughPage &&
                                previous.canReuseWindow()
                        val page =
                            loadWindow(
                                account,
                                query,
                                if (rolling) throughPage else fromPage,
                                throughPage,
                                previous,
                            )
                        if (rolling) {
                            return@await previous.appendWindow(page, fromPage)
                        }
                        if (replace || fromPage == 1 || previous == null) {
                            page
                        } else if (next == fromPage && previous.canReuseWindow()) {
                            previous.appendWindow(page, previous.loadedFromPage)
                        } else {
                            page.copy(
                                notifications =
                                    (previous.notifications + page.notifications).distinctRecords(),
                                repositories =
                                    (previous.repositories + page.repositories)
                                        .distinctRepositories(),
                                loadedFromPage = previous.loadedFromPage,
                                pageItemCounts = emptyList(),
                            )
                        }
                    }
                }
            }
            .await()
    }

    suspend fun markRead(account: String, threadId: String) =
        markThread(account, threadId, NotificationThreadAction.MarkRead)

    suspend fun markThread(account: String, threadId: String, action: NotificationThreadAction) =
        markThread(account, session(account), threadId, action)

    /** 仅发送服务端写请求；仅在确认成功后再更新已确认快照与失效筛选。 */
    suspend fun sendThreadAction(
        account: String,
        threadId: String,
        action: NotificationThreadAction,
    ) {
        require(threadId.isNotBlank())
        val session = session(account)
        val affectedRuntime = runtime(account)
        val field = action.field
        val result =
            session
                .execute(
                    "mutation ${action.operation}(${'$'}id: ID!) { $field(input: {id: ${'$'}id}) { success } }",
                    buildJsonObject { put("id", threadId) },
                    write = true,
                )
                .graphObject(field)
        if (result["success"] != JsonPrimitive(true)) {
            throw MutationRejectedException("Notification action rejected by GitHub")
        }
        currentCoroutineContext().ensureActive()
        session.ensureCurrent()
        updateThreads(account, threadId, action, affectedRuntime, invalidate = true)
    }

    private suspend fun markThread(
        account: String,
        session: GithubGraphQlSession,
        threadId: String,
        action: NotificationThreadAction,
    ) {
        require(threadId.isNotBlank())
        val affectedRuntime = runtime(account)
        val field = action.field
        // 点击只负责本地投影；网络结果稍后确认或回滚。
        updateThreads(account, threadId, action, affectedRuntime, invalidate = false)
        val result =
            try {
                session
                    .execute(
                        "mutation ${action.operation}(${'$'}id: ID!) { $field(input: {id: ${'$'}id}) { success } }",
                        buildJsonObject { put("id", threadId) },
                        write = true,
                    )
                    .graphObject(field)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 网络结果未知时保留投影，交由下一次窗口重读核对。
                throw error
            }
        if (result["success"] != JsonPrimitive(true)) {
            // 服务端明确未应用：保留列表结构，随后重读窗口核对真实状态。
            updateThreads(account, threadId, action.inverse, affectedRuntime, invalidate = true)
            throw GithubPageException(definiteRejection = true)
        }
        currentCoroutineContext().ensureActive()
        session.ensureCurrent()
        updateThreads(account, threadId, action, affectedRuntime)
    }

    suspend fun resolveWorkThreadId(account: String, target: WorkTarget): String {
        val session = session(account)
        val expectedRepository = "${target.owner}/${target.repository}"
        require(validRepository(expectedRepository) && target.number > 0)
        val runtime = runtime(account)
        val identity = target.normalized()
        synchronized(runtime.resources) { runtime.workThreadIds[identity] }?.let { return it }
        val query = NotificationsQuery(includeRead = true, repository = expectedRepository)
        val seen = mutableSetOf<String>()
        var after: String? = null
        for (page in 1..10) {
            val result = loadNotificationPage(session, query, after)
            val notification = result.items.firstOrNull { it.workTarget() == identity }
            notification?.id?.takeIf(String::isNotBlank)?.let { threadId ->
                synchronized(runtime.resources) { runtime.workThreadIds[identity] = threadId }
                return threadId
            }
            val next = result.nextCursor ?: break
            if (!seen.add(next)) throw GithubPageException()
            after = next
        }
        throw WorkNotificationUnavailableException()
    }

    suspend fun markWorkDone(account: String, target: WorkTarget) {
        val session = session(account)
        val expectedRepository = "${target.owner}/${target.repository}"
        require(validRepository(expectedRepository) && target.number > 0)
        val runtime = runtime(account)
        val identity = target.normalized()
        val knownThreadId = synchronized(runtime.resources) { runtime.workThreadIds[identity] }
        if (knownThreadId != null) {
            markThread(account, session, knownThreadId, NotificationThreadAction.MarkDone)
            return
        }
        // 工作项编号和工作项的 GraphQL ID 都不能当作通知线程 ID。
        val query = NotificationsQuery(includeRead = true, repository = expectedRepository)
        val seen = mutableSetOf<String>()
        var after: String? = null
        for (page in 1..10) {
            val result = loadNotificationPage(session, query, after)
            val notification = result.items.firstOrNull { it.workTarget() == identity }
            notification?.id?.let { threadId ->
                markThread(account, session, threadId, NotificationThreadAction.MarkDone)
                return
            }
            val next = result.nextCursor ?: break
            if (!seen.add(next)) throw GithubPageException()
            after = next
        }
        throw WorkNotificationUnavailableException()
    }

    /** 页面返回后仍由会话级仓库完成通知写入，不让详情页的锁阻塞返回。 */
    fun enqueueWorkDone(account: String, target: WorkTarget): Job =
        runtime(account).scope.launch {
            try {
                val threadId = resolveWorkThreadId(account, target)
                val coord = cache.mutations(account)
                coord.submit(
                    moe.yumeyuka.yumehub.data.cache.MutationKey(
                        moe.yumeyuka.yumehub.data.cache.MutationKind.NOTIFICATION,
                        threadId,
                        NotificationThreadState.serializer(),
                    ),
                    confirmed = NotificationThreadState(unread = false, done = false),
                    desired = NotificationThreadState(unread = false, done = true),
                    tags = setOf(CacheTag(DataDomain.Notifications, null)),
                    send = {
                        sendThreadAction(account, threadId, NotificationThreadAction.MarkDone)
                        NotificationThreadState(unread = false, done = true)
                    },
                    readBack = {
                        NotificationThreadState(unread = false, done = false)
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 详情页已经离开组合；下一次进入通知列表时由服务端状态和刷新流程核对。
            }
        }

    fun rememberOpenedWork(account: String, notification: NotificationDto) {
        checkAccount(account)
        val target = notification.workTarget() ?: return
        val threadId = notification.id?.takeIf(String::isNotBlank) ?: return
        val runtime = runtime(account)
        synchronized(runtime.resources) {
            runtime.workThreadIds[target] = threadId
            while (runtime.workThreadIds.size > 64) {
                runtime.workThreadIds.remove(runtime.workThreadIds.keys.first())
            }
        }
    }

    private fun NotificationDto.workTarget(): WorkTarget? {
        val subject = subject ?: return null
        val kind =
            when (subject.type) {
                "Issue" -> CollaborationKind.Issue
                "PullRequest" -> CollaborationKind.PullRequest
                else -> return null
            }
        val url = subject.url?.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments
        val route = if (kind == CollaborationKind.Issue) "issues" else "pull"
        if (
            !url.isHttps ||
                url.host != "github.com" ||
                url.port != 443 ||
                url.encodedUsername.isNotEmpty() ||
                url.encodedPassword.isNotEmpty() ||
                segments.size != 4 ||
                segments[2] != route
        )
            return null
        if (!validRepository("${segments[0]}/${segments[1]}")) return null
        val number = segments[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return WorkTarget(segments[0], segments[1], number, kind).normalized()
    }

    private fun WorkTarget.normalized() =
        copy(
            owner = owner.lowercase(Locale.ROOT),
            repository = repository.lowercase(Locale.ROOT),
        )

    fun pausePaging(account: String, query: NotificationsQuery) {
        val resources = runtime(account).resources
        synchronized(resources) {
            val resource = resource(account, query)
            resource.cached?.let { resource.update(it.copy(pagingPaused = true)) }
            resource.invalidate()
        }
    }

    private suspend fun loadWindow(
        account: String,
        query: NotificationsQuery,
        fromPage: Int,
        throughPage: Int,
        previous: NotificationsSnapshot? = null,
    ): NotificationsSnapshot {
        require(
            throughPage <= MaxNotificationPages &&
                (query.repository == null || validRepository(query.repository)),
        )
        val session = session(account)
        val history = previous?.cursorHistory.orEmpty().take(fromPage - 1).toMutableList()
        val seen = history.toMutableSet()
        var after = history.lastOrNull()
        val records = mutableListOf<NotificationDto>()
        val recordIds = mutableSetOf<Any>()
        val counts = mutableListOf<Int>()
        var overlaps = false
        var firstPage = fromPage
        var lastPage = firstPage
        for (page in (history.size + 1)..throughPage) {
            val result = loadNotificationPage(session, query, after)
            if (page >= fromPage || result.nextCursor == null) {
                firstPage = minOf(firstPage, page)
                val start = records.size
                result.items.filterTo(records) { recordIds.add(it.recordKey()) }
                val count = records.size - start
                overlaps = overlaps || count != result.items.size
                counts.add(count)
            }
            lastPage = page
            after = result.nextCursor ?: break
            if (!seen.add(after) || page == MaxNotificationPages) throw GithubPageException()
            history.add(after)
        }
        return NotificationsSnapshot(
            notifications = records,
            repositories = records.repositoryNames(),
            loadedFromPage = firstPage,
            loadedThroughPage = lastPage,
            nextCursor = history.getOrNull(lastPage - 1),
            cursorHistory = history,
            // 去重后无法还原重复项原属哪一页，窗口换页时必须回退到服务端重读。
            pageItemCounts = if (overlaps) emptyList() else counts,
        )
    }

    private fun updateThreads(
        account: String,
        threadId: String,
        action: NotificationThreadAction,
        runtime: Runtime,
        invalidate: Boolean = true,
    ) {
        val resources = runtime.resources
        synchronized(resources) {
            runtime.reap()
            val normalizedAccount = account.lowercase(Locale.ROOT)
            val affected =
                resources
                    .filterKeys { it.account == normalizedAccount }
                    .values
                    .mapNotNull { it.get() }
            val repositories =
                affected
                    .flatMap { resource ->
                        resource.cached
                            ?.notifications
                            .orEmpty()
                            .filter { it.id == threadId }
                            .mapNotNull { it.repository?.fullName }
                            .filter(::validRepository)
                    }
                    .distinctRepositories()
            val affectedRepositories = repositories.map { it.lowercase(Locale.ROOT) }.toSet()
            // 尚未驻留内存的筛选也要标记磁盘快照过期，防止之后命中新鲜的旧页码。
            for (repository in listOf(null) + repositories) {
                for ((includeRead, isDone) in listOf(false to false, true to false, true to true)) {
                    resource(account, NotificationsQuery(includeRead, isDone, repository))
                }
            }
            resources
                .filterKeys {
                    it.account == normalizedAccount &&
                        (it.query.repository == null || it.query.repository in affectedRepositories)
                }
                .forEach { (key, reference) ->
                    val resource = reference.get() ?: return@forEach
                    resource.cached?.let { snapshot ->
                        val records =
                            snapshot.notifications.mapNotNull { notification ->
                                if (notification.id != threadId) notification
                                else
                                    when (action) {
                                        NotificationThreadAction.MarkDone ->
                                            notification.takeIf { key.query.done }
                                        NotificationThreadAction.MarkUndone ->
                                            notification.takeIf {
                                                !key.query.done &&
                                                    (key.query.includeRead || it.unread)
                                            }
                                        NotificationThreadAction.MarkRead ->
                                            notification
                                                .takeIf { key.query.includeRead }
                                                ?.copy(unread = false)
                                        NotificationThreadAction.MarkUnread ->
                                            notification.copy(unread = true)
                                    }
                            }
                        resource.update(snapshot.copy(notifications = records, pagingPaused = true))
                    }
                    // 线程状态改变后从首个游标重读窗口，不继续使用旧位置。
                    if (invalidate) {
                        resource.invalidate()
                        runtime.cancelRefresh(key.query)
                    }
                }
        }
        cache.changes(account).publish(DataDomain.Notifications)
    }

    private fun checkAccount(expected: String) {
        if (expected.isBlank() || !account.equals(expected, ignoreCase = true)) {
            throw CancellationException("Notifications account changed")
        }
    }

    private fun session(account: String) = GithubGraphQlSession(graphQlApi, tokenStore, account)

    private fun NotificationsQuery.normalized(): NotificationsQuery =
        copy(repository = repository?.lowercase(Locale.ROOT))

    private fun NotificationsQuery.cacheKey(): String =
        "notifications.v2:$includeRead:$done:${repository.orEmpty()}"

    private data class ResourceKey(val account: String, val query: NotificationsQuery)

    private fun runtime(account: String): Runtime =
        cache.sessionResource(account, "notifications-registry-v1") { Runtime(it) }

    private class Runtime(val scope: CoroutineScope) {
        val workThreadIds = LinkedHashMap<WorkTarget, String>(16, 0.75f, true)
        val resources =
            mutableMapOf<ResourceKey, WeakReference<CachedResource<NotificationsSnapshot>>>()
        private val mutexes = mutableMapOf<NotificationsQuery, WeakReference<Mutex>>()
        private val refreshes = mutableMapOf<NotificationsQuery, Job>()

        fun refresh(query: NotificationsQuery, load: suspend () -> Unit): Job {
            val job = synchronized(resources) {
                refreshes[query]?.takeUnless { it.isCompleted } ?: run {
                    val next = scope.launch(start = CoroutineStart.LAZY) { load() }
                    refreshes[query] = next
                    next.invokeOnCompletion {
                        synchronized(resources) {
                            if (refreshes[query] === next) refreshes.remove(query)
                        }
                    }
                    next
                }
            }
            job.start()
            return job
        }

        // 调用方已持有 resources 锁；变更信号不能加入刚失效的校验。
        fun cancelRefresh(query: NotificationsQuery) {
            refreshes.remove(query)?.cancel()
        }

        fun mutex(query: NotificationsQuery): Mutex =
            synchronized(resources) {
                reap()
                mutexes[query]?.get() ?: Mutex().also { mutexes[query] = WeakReference(it) }
            }

        // 仅在持有 resources 锁时调用；活跃页面与 core LRU 负责资源的强引用。
        fun reap() {
            resources.entries.removeAll { it.value.get() == null }
            mutexes.entries.removeAll { it.value.get() == null }
        }
    }

    private companion object {
        const val MaxNotificationPages = 1000
    }
}

private val RepositorySegment = Regex("[A-Za-z0-9_.-]+")

private fun validRepository(fullName: String): Boolean {
    val segments = fullName.split('/')
    return segments.size == 2 &&
        segments.all { it != "." && it != ".." && RepositorySegment.matches(it) }
}

private fun NotificationDto.recordKey(): Any = id?.takeIf(String::isNotBlank) ?: this

private fun List<NotificationDto>.distinctRecords() = distinctBy { it.recordKey() }

private fun List<String>.distinctRepositories() =
    distinctBy { it.lowercase(Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)

internal data class NotificationRefreshWindow(
    val fromPage: Int,
    val throughPage: Int,
    val previous: NotificationsSnapshot?,
)

internal fun NotificationsSnapshot?.refreshWindow(updatedAt: Long): NotificationRefreshWindow {
    val from = this?.loadedFromPage ?: 1
    val through = this?.loadedThroughPage?.coerceAtLeast(from) ?: from
    val prefix = this?.takeIf { !it.pagingPaused && updatedAt > 0L }
    return NotificationRefreshWindow(from, through, prefix)
}

internal fun NotificationsSnapshot.canReuseWindow(): Boolean =
    !pagingPaused &&
        loadedFromPage in 1..loadedThroughPage &&
        nextCursor != null &&
        cursorHistory.getOrNull(loadedThroughPage - 1) == nextCursor &&
        pageItemCounts.size == loadedThroughPage - loadedFromPage + 1 &&
        pageItemCounts.all { it >= 0 } &&
        pageItemCounts.sumOf { it.toLong() } == notifications.size.toLong()

/** 保留窗口内已有页，只从服务端追加下一页，滚动窗口按已记录的边界丢弃旧页。 */
internal fun NotificationsSnapshot.appendWindow(
    appended: NotificationsSnapshot,
    firstPage: Int,
): NotificationsSnapshot {
    val dropped = firstPage - loadedFromPage
    val records = notifications.drop(pageItemCounts.take(dropped).sum()).toMutableList()
    val counts = pageItemCounts.drop(dropped).toMutableList()
    val seen = records.mapTo(mutableSetOf()) { it.recordKey() }
    var reusable = appended.pageItemCounts.isNotEmpty()
    var offset = 0
    for (count in appended.pageItemCounts.ifEmpty { listOf(appended.notifications.size) }) {
        val start = records.size
        appended.notifications.subList(offset, offset + count).filterTo(records) {
            seen.add(it.recordKey())
        }
        val added = records.size - start
        reusable = reusable && added == count
        counts.add(added)
        offset += count
    }
    return appended.copy(
        notifications = records,
        repositories = records.repositoryNames(),
        loadedFromPage = firstPage,
        pageItemCounts = if (reusable) counts else emptyList(),
    )
}

private fun List<NotificationDto>.repositoryNames(): List<String> =
    mapNotNull { it.repository?.fullName }.filter(::validRepository).distinctRepositories()

class WorkNotificationUnavailableException : Exception()
