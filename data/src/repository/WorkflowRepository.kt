package moe.yumeyuka.yumehub.data.repository

import java.time.Instant
import java.util.PriorityQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.RemoteFetch
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import okhttp3.OkHttpClient

data class WorkflowOption(val id: String, val name: String, val active: Boolean)

data class WorkflowCatalog(
    val options: List<WorkflowOption>,
    val branch: String?,
    val canWrite: Boolean,
)

data class WorkflowJobs(
    val suite: String,
    val url: String,
    val canRerunFailed: Boolean,
    val jobs: List<RepositoryActivityEntry>,
    val next: String?,
    val workflowName: String,
    val runNumber: Int,
)

data class WorkflowInput(
    val key: String,
    val description: String?,
    val type: String,
    val required: Boolean,
    val default: String?,
    val choices: List<String>,
)

internal const val WorkflowRunFields =
    """
    id title runNumber eventType createdAt url workflow { name }
    checkSuite { id status conclusion branch { name } creator { login } }
"""

internal fun JsonObject.workflowRun(): RepositoryActivityEntry {
    val suite = get("checkSuite") as? JsonObject
    val workflow = graphObject("workflow").graphRequiredText("name")
    return RepositoryActivityEntry(
        id = graphRequiredText("id"),
        title = graphText("title")?.takeIf { it.isNotBlank() } ?: workflow,
        workflowName = workflow,
        targetId = suite?.graphText("id"),
        url = graphText("url"),
        subtitle = (suite?.get("branch") as? JsonObject)?.graphText("name"),
        timestamp = graphRequiredText("createdAt"),
        state = suite?.graphText("conclusion") ?: suite?.graphText("status"),
        number = get("runNumber")?.jsonPrimitive?.intOrNull,
        event = graphText("eventType"),
        login = (suite?.get("creator") as? JsonObject)?.graphText("login"),
    )
}

private const val PageInfo = "pageInfo { hasNextPage endCursor }"
private const val Seed = "runs(first: 8) { $PageInfo nodes { $WorkflowRunFields } }"
private const val NextRuns =
    """
    query NextRuns(${'$'}id: ID!, ${'$'}after: String!) {
      node(id: ${'$'}id) { ... on Workflow {
        runs(first: 30, after: ${'$'}after) { $PageInfo nodes { $WorkflowRunFields } }
      } }
    }
"""

internal data class RunStream(
    val id: String,
    val rows: List<RepositoryActivityEntry>,
    val next: String?,
)

// 每条流的首项都是该工作流未展示的最新运行。补齐耗尽的流后才选下一项。
internal suspend fun mergeRuns(
    streams: List<RunStream>,
    size: Int,
    stopBeforeRefill: Boolean = false,
    refill: suspend (String, String) -> RunStream,
): Pair<List<RepositoryActivityEntry>, List<RunStream>> {
    require(size >= 0)
    if (size == 0)
        return emptyList<RepositoryActivityEntry>() to
            streams.filter { it.rows.isNotEmpty() || it.next != null }
    val active = streams.mapIndexed { index, stream -> RunMergeCursor(stream, index) }
    val pending = active.filter { it.exhausted && it.stream.next != null }.toMutableList()
    val ready =
        PriorityQueue(
            compareByDescending<RunMergeCursor> { it.timestamp }
                .thenByDescending { it.stream.id }
                .thenBy { it.order },
        )
    fun schedule(cursor: RunMergeCursor) {
        cursor.timestamp = Instant.parse(cursor.stream.rows[cursor.offset].timestamp)
        ready.add(cursor)
    }
    active.filterNot { it.exhausted }.forEach(::schedule)
    val result = mutableListOf<RepositoryActivityEntry>()
    while (result.size < size) {
        if (stopBeforeRefill && result.isNotEmpty() && pending.isNotEmpty()) break
        for (cursor in pending) {
            val seen = mutableSetOf<String>()
            while (cursor.exhausted) {
                val next = cursor.stream.next ?: break
                if (!seen.add(next)) throw GithubPageException()
                cursor.stream = refill(cursor.stream.id, next)
                cursor.offset = 0
            }
            if (!cursor.exhausted) schedule(cursor)
        }
        pending.clear()
        val cursor = ready.poll() ?: break
        result += cursor.stream.rows[cursor.offset++]
        if (cursor.exhausted) {
            if (cursor.stream.next != null) pending.add(cursor)
        } else if (result.size < size) {
            schedule(cursor)
        }
    }
    return result to
        active
            .filter { !it.exhausted || it.stream.next != null }
            .map { cursor ->
                // 每批只复制一次剩余窗口，避免逐行 drop 和重复解析其他工作流的时间。
                if (cursor.offset == 0) cursor.stream
                else cursor.stream.copy(rows = cursor.stream.rows.drop(cursor.offset))
            }
}

private class RunMergeCursor(var stream: RunStream, val order: Int) {
    var offset = 0
    lateinit var timestamp: Instant
    val exhausted: Boolean
        get() = offset == stream.rows.size
}

class WorkflowRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val paging: PagingRepository,
    private val client: OkHttpClient,
    private val cache: CachedDataRepository,
) {
    private fun session(account: String) = GithubGraphQlSession(api, tokens, account)

    private val artifactClient = client.newBuilder().followRedirects(false).build()

    suspend fun artifacts(
        account: String,
        owner: String,
        name: String,
        run: Long,
        page: Int,
    ): WorkflowArtifacts =
        when (val fetched = listWorkflowArtifacts(artifactClient, tokens, account, owner, name, run, page)) {
            is RemoteFetch.Fresh -> fetched.value
            RemoteFetch.NotModified -> error("An unconditional artifact request cannot return 304")
        }

    /** 第一页工件列表：收集时先出缓存，再以 ETag 后台校验；取消收集会取消仅由它发起的请求。 */
    fun observeFirstArtifactPage(
        account: String,
        owner: String,
        name: String,
        run: Long,
    ): Flow<CachedDataState<WorkflowArtifacts>> =
        cache
            .resource(
                account,
                "workflow.artifacts.v1:$owner/$name/$run",
                WorkflowArtifacts.serializer(),
                tags = setOf(CacheTag(DataDomain.Work, null)),
            )
            .observe { validator ->
                listWorkflowArtifacts(artifactClient, tokens, account, owner, name, run, 1, validator)
            }

    suspend fun artifactDownload(account: String, owner: String, name: String, id: Long) =
        workflowArtifactDownload(artifactClient, tokens, account, owner, name, id)

    private suspend fun GithubGraphQlSession.workflows(
        owner: String,
        name: String,
        fields: String,
        visit: (JsonObject, List<JsonObject>) -> Unit,
    ) {
        val query =
            """
            query WorkflowCatalog(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
              repository(owner: ${'$'}owner, name: ${'$'}name) { viewerPermission defaultBranchRef { name }
                workflows(first: 30, after: ${'$'}after, orderBy: {field: NAME, direction: ASC}) {
                  $PageInfo nodes { id $fields }
                }
              }
            }
        """
        var after: String? = null
        val seen = mutableSetOf<String>()
        do {
            val repo =
                execute(
                        query,
                        buildJsonObject {
                            put("owner", owner)
                            put("name", name)
                            put("after", after)
                        },
                    )
                    .graphObject("repository")
            val connection = repo.graphObject("workflows")
            visit(repo, connection.graphNodes())
            after = connection.graphCursor(after)
            if (after != null && !seen.add(after)) throw GithubPageException()
        } while (after != null)
    }

    suspend fun catalog(account: String, owner: String, name: String): WorkflowCatalog {
        var branch: String? = null
        var writable = false
        val options = mutableListOf<WorkflowOption>()
        session(account).workflows(owner, name, "name state") { repo, nodes ->
            branch = (repo["defaultBranchRef"] as? JsonObject)?.graphText("name")
            writable = repo.graphText("viewerPermission") in setOf("ADMIN", "MAINTAIN", "WRITE")
            options += nodes.map {
                WorkflowOption(
                    it.graphRequiredText("id"),
                    it.graphRequiredText("name"),
                    it.graphText("state") == "ACTIVE",
                )
            }
        }
        return WorkflowCatalog(options.distinctBy { it.id }, branch, writable)
    }

    fun historyKey(owner: String, name: String): String = "actions:all:$owner/$name"

    fun revalidation(account: String, key: String) = paging.revalidation(account, key)

    fun retryRevalidation(account: String, key: String) = paging.retryRevalidation(account, key)

    fun history(account: String, owner: String, name: String) =
        // 本地游标只索引当前 loader 的多路合并检查点，不能脱离该 loader 恢复。
        githubGraphQlPager(
            account,
            historyKey(owner, name),
            paging,
            RepositoryActivityEntry::id,
            config =
                androidx.paging.PagingConfig(
                    pageSize = 30,
                    prefetchDistance = 1,
                    enablePlaceholders = false,
                ),
            tags = setOf(CacheTag(DataDomain.Work, null)),
        ) {
            val session = session(account)
            val checkpoints = mutableMapOf<String, List<RunStream>>()
            fun stream(id: String, runs: JsonObject, after: String?) =
                RunStream(id, runs.graphNodes().map { it.workflowRun() }, runs.graphCursor(after))
            val load: suspend (String?) -> GithubGraphQlPage<RepositoryActivityEntry> = { after ->
                val streams =
                    if (after == null) {
                        val seeds = mutableListOf<RunStream>()
                        session.workflows(owner, name, Seed) { _, nodes ->
                            seeds += nodes.map {
                                stream(it.graphRequiredText("id"), it.graphObject("runs"), null)
                            }
                        }
                        seeds.distinctBy { it.id }
                    } else checkpoints.getValue(after)
                val (rows, pending) =
                    mergeRuns(streams, 30, stopBeforeRefill = after == null) { id, cursor ->
                        val node =
                            session
                                .execute(
                                    NextRuns,
                                    buildJsonObject {
                                        put("id", id)
                                        put("after", cursor)
                                    },
                                )
                                .graphObject("node")
                        stream(id, node.graphObject("runs"), cursor)
                    }
                val next = if (pending.isEmpty()) null else ((after?.toInt() ?: 0) + 1).toString()
                if (next != null) checkpoints[next] = pending
                GithubGraphQlPage(rows, next)
            }
            load
        }

    suspend fun runDocument(
        account: String,
        owner: String,
        name: String,
        run: Long,
    ): WorkflowRunDocument {
        val session = session(account)
        return loadRunDocument(client, owner, name, run).also { session.ensureCurrent() }
    }

    suspend fun runJobs(
        account: String,
        owner: String,
        name: String,
        run: Long?,
        suiteId: String?,
        after: String?,
    ): WorkflowJobs {
        val fields =
            """
            id status conclusion rerunnable repository { viewerPermission }
            workflowRun { url runNumber workflow { name } }
            checkRuns(first: 30, after: ${'$'}after) { $PageInfo nodes { id name status conclusion startedAt } }
        """
        val byUrl = suiteId == null
        val target =
            if (byUrl) "resource(url: ${'$'}key) { ... on WorkflowRun { checkSuite { $fields } } }"
            else "node(id: ${'$'}key) { ... on CheckSuite { $fields } }"
        val type = if (byUrl) "URI!" else "ID!"
        val data =
            session(account)
                .execute(
                    "query WorkflowJobs(${'$'}key: $type, ${'$'}after: String) { $target }",
                    buildJsonObject {
                        put(
                            "key",
                            suiteId ?: runWebUrl(owner, name, requireNotNull(run)).toString(),
                        )
                        put("after", after)
                    },
                )
        val suite =
            if (byUrl) data.graphObject("resource").graphObject("checkSuite")
            else data.graphObject("node")
        val jobs = suite.graphObject("checkRuns")
        val canRerun =
            suite.graphText("status") == "COMPLETED" &&
                suite.graphText("conclusion") in
                    setOf("FAILURE", "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED") &&
                suite.graphText("rerunnable") == "true" &&
                suite.graphObject("repository").graphText("viewerPermission") in
                    setOf("WRITE", "MAINTAIN", "ADMIN")
        val workflowRun = suite.graphObject("workflowRun")
        return WorkflowJobs(
            suite.graphRequiredText("id"),
            workflowRun.graphRequiredText("url"),
            canRerun,
            jobs.graphNodes().map { node ->
                RepositoryActivityEntry(
                    id = node.graphRequiredText("id"),
                    title = node.graphRequiredText("name"),
                    state = node.graphText("conclusion") ?: node.graphText("status"),
                    timestamp = node.graphText("startedAt"),
                )
            },
            jobs.graphCursor(after),
            workflowRun.graphObject("workflow").graphRequiredText("name"),
            workflowRun.graphRequiredText("runNumber").toInt(),
        )
    }

    suspend fun rerunFailed(account: String, suiteId: String, debug: Boolean) {
        val result =
            session(account)
                .execute(
                    """
            mutation ReRunCheckSuite(${'$'}id: ID!, ${'$'}debug: Boolean!) {
              rerunCheckSuiteMobile(input: {checkSuiteId: ${'$'}id, enableDebugLogging: ${'$'}debug,
                onlyFailedCheckRuns: true}) { checkSuite { id } }
            }
        """,
                    buildJsonObject {
                        put("id", suiteId)
                        put("debug", debug)
                    },
                    write = true,
                )
        if (
            result
                .graphObject("rerunCheckSuiteMobile")
                .graphObject("checkSuite")
                .graphRequiredText("id") != suiteId
        )
            throw GithubPageException()
        cache.invalidateAndPersist(account, setOf(CacheTag(DataDomain.Work, null)))
        cache.changes(account).publish(DataDomain.Work)
    }

    fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    suspend fun inputs(account: String, id: String, branch: String): List<WorkflowInput>? {
        val node =
            session(account)
                .execute(
                    """
            query WorkflowForm(${'$'}id: ID!, ${'$'}branch: String!) { node(id: ${'$'}id) { ... on Workflow {
              hasWorkflowDispatchTriggerForBranch(branchRef: ${'$'}branch)
              inputs(branchRef: ${'$'}branch) { choices description required type defaultValue titleId }
            } } }
        """,
                    buildJsonObject {
                        put("id", id)
                        put("branch", branch)
                    },
                )
                .graphObject("node")
        if (!node.graphRequiredText("hasWorkflowDispatchTriggerForBranch").toBooleanStrict())
            return null
        return parseWorkflowInputs(node)
    }

    suspend fun dispatch(account: String, id: String, branch: String, values: Map<String, String>) {
        val result =
            session(account)
                .execute(
                    """
            mutation DispatchWorkflowRun(${'$'}input: DispatchWorkflowRunInput!) {
              dispatchWorkflowRun(input: ${'$'}input) { clientMutationId }
            }
        """,
                    buildJsonObject {
                        putJsonObject("input") {
                            put("workflowId", id)
                            put("branch", branch)
                            putJsonArray("dispatchInputs") {
                                values.forEach { (key, value) ->
                                    addJsonObject {
                                        put("titleId", key)
                                        put("value", value)
                                    }
                                }
                            }
                        }
                    },
                    write = true,
                )
        result.graphObject("dispatchWorkflowRun")
        cache.invalidateAndPersist(account, setOf(CacheTag(DataDomain.Work, null)))
        cache.changes(account).publish(DataDomain.Work)
    }
}

internal fun parseWorkflowInputs(node: JsonObject): List<WorkflowInput> {
    val inputs = node["inputs"] ?: throw GithubPageException()
    if (inputs == JsonNull) return emptyList()
    return inputs.jsonArray.map {
        val field = it.jsonObject
        WorkflowInput(
            field.graphRequiredText("titleId"),
            field.graphText("description"),
            field.graphRequiredText("type").uppercase(),
            field.graphRequiredText("required").toBooleanStrict(),
            field.graphText("defaultValue"),
            (field["choices"] as? JsonArray)?.map { v -> v.jsonPrimitive.content }.orEmpty(),
        )
    }
}
