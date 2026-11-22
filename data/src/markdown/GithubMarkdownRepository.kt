package moe.yumeyuka.yumehub.data.markdown

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.yumeyuka.yumehub.data.api.GitHubApi
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.network.useStreamingBody
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import okhttp3.ResponseBody
import retrofit2.HttpException

/**
 * 读取官方 HTML，可从主线程调用。preview 总是使用 POST /markdown；其余依次使用 README、 nodeId、Issue / PR 正文查询或 POST
 * /markdown。没有本地渲染、原文降级或跨账号缓存。
 *
 * 私有 GraphQL schema 不可用、无权限、对象不存在和响应不完整均抛出中文可重试异常。 nodeId / README / 正文查询返回的是已保存内容；编辑中的文本必须传
 * preview = true。成功结果短期保存在当前会话内；retry / refresh 应传 forceRefresh = true。
 */
class GithubMarkdownRepository(
    private val gitHubApi: GitHubApi,
    private val graphQlApi: GitHubGraphQlApi,
    private val tokenStore: TokenStore,
) {
    val viewerLogin: String
        get() = tokenStore.accountLogin.value

    private val htmlCache =
        MarkdownHtmlCache(
            tokenStore.accountLogin.value,
            tokenStore.accessToken.value,
            tokenStore.sessionGeneration.value,
        )

    val sessionRevision = htmlCache.generation

    // 与 DI 中 repository 单例同寿命；持续观察空闲期间的 A→B→A，不持有网络任务。
    // Unconfined 从写入通知直接更新代际，避免主线程排队丢失快速切换。
    private val sessionObserver = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        sessionObserver.launch(start = CoroutineStart.UNDISPATCHED) {
            tokenStore.accountLogin.state.collect { htmlCache.observeLogin(it) }
        }
        sessionObserver.launch(start = CoroutineStart.UNDISPATCHED) {
            tokenStore.accessToken.state.collect { htmlCache.observeToken(it) }
        }
        sessionObserver.launch(start = CoroutineStart.UNDISPATCHED) {
            tokenStore.sessionGeneration.collect { htmlCache.observeSession(it) }
        }
    }

    private val requestLocks = MarkdownRequestLocks()

    /** forceRefresh 用于 retry / refresh：等待同键请求结束后移除旧项并重新请求。 */
    suspend fun render(
        text: String,
        pageUrl: String? = null,
        repositoryRootUrl: String? = null,
        nodeId: String? = null,
        readme: Boolean = false,
        ref: String? = null,
        preview: Boolean = false,
        forceRefresh: Boolean = false,
    ): String = withSession { generation ->
        try {
            withContext(Dispatchers.IO) {
                val page = parseMarkdownRepositoryUrl(pageUrl)
                val repository = page ?: parseMarkdownRepositoryUrl(repositoryRootUrl)
                val key =
                    MarkdownHtmlKey(
                        generation = generation,
                        sourceVersion = markdownSourceVersion(text),
                        page = page,
                        context = repository?.context,
                        nodeId = nodeId,
                        readme = readme,
                        ref = ref,
                        preview = preview,
                    )
                requestLocks.withKey(key) {
                    ensureActive()
                    if (!htmlCache.isCurrent(generation)) {
                        throw CancellationException("登录会话已变化，已取消 Markdown 请求")
                    }
                    if (forceRefresh) htmlCache.remove(key)
                    else
                        htmlCache.get(key)?.let {
                            return@withKey it
                        }
                    val html =
                        when {
                            preview -> renderText(text, repository?.context)
                            readme -> {
                                val source =
                                    repository
                                        ?: throw GithubMarkdownException(
                                            "无法识别 GitHub README 所属仓库，请检查链接后重试",
                                        )
                                val data = execute(readmeRequest(source, ref))
                                data
                                    .requiredObject("repository")
                                    .requiredObject("readme")
                                    .requiredHtml("contentHTML")
                            }
                            nodeId != null -> {
                                if (nodeId.isBlank()) throw GithubMarkdownException("正文标识为空，请重试")
                                val node = execute(nodeBodyRequest(nodeId)).requiredObject("node")
                                val type = (node["__typename"] as? JsonPrimitive)?.content
                                node.requiredHtml(
                                    if (type == "Issue" || type == "PullRequest") "bodyHtml"
                                    else "bodyHTML",
                                )
                            }
                            page?.workNumber != null -> {
                                execute(repositoryBodyRequest(page))
                                    .requiredObject("repository")
                                    .requiredObject("work")
                                    .requiredHtml("bodyHtml")
                            }
                            else -> renderText(text, repository?.context)
                        }
                    ensureActive()
                    if (!htmlCache.put(key, html)) {
                        throw CancellationException("登录会话已变化，已取消 Markdown 请求")
                    }
                    html
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GithubMarkdownException) {
            throw failure
        } catch (failure: HttpException) {
            failure.response()?.errorBody()?.close()
            throw GithubMarkdownException("GitHub HTML 请求失败，请检查登录状态和权限后重试", failure)
        } catch (failure: Exception) {
            // 不回显服务器错误、请求正文或令牌
            throw GithubMarkdownException("暂时无法获取 GitHub Markdown HTML，请重试", failure)
        }
    }

    private suspend fun execute(request: GraphQlRequest): JsonObject {
        val response = graphQlApi.execute(request)
        if (response.errors.isNotEmpty()) {
            throw GithubMarkdownException(
                "GitHub HTML 查询失败，可能缺少权限或不支持官方私有字段，请检查后重试",
            )
        }
        return response.data as? JsonObject
            ?: throw GithubMarkdownException("GitHub HTML 响应缺少数据，请重试")
    }

    private suspend fun renderText(text: String, context: String?): String =
        gitHubApi
            .renderMarkdown(GithubMarkdownRequest(text, mode = "gfm", context = context))
            .useStreamingBody(ResponseBody::readLimitedHtml)

    private suspend fun <T> withSession(block: suspend (Long) -> T): T = coroutineScope {
        fun currentGeneration(): Long =
            synchronized(tokenStore.responseCacheSession.lock) {
                htmlCache.observeLogin(tokenStore.accountLogin.value)
                htmlCache.observeToken(tokenStore.accessToken.value)
                htmlCache.observeSession(tokenStore.sessionGeneration.value)
                sessionRevision.value
            }
        val generation = currentGeneration()
        val scope = this
        val watcher =
            launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                sessionRevision.collect {
                    if (it != generation)
                        scope.cancel(CancellationException("登录会话已变化，已取消 Markdown 请求"))
                }
            }
        try {
            ensureActive()
            val result = block(generation)
            if (currentGeneration() != generation)
                throw CancellationException("登录会话已变化，已取消 Markdown 请求")
            ensureActive()
            result
        } finally {
            watcher.cancel()
        }
    }
}

class GithubMarkdownException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal const val MaxHtmlBytes = 4 * 1024 * 1024

internal fun oversizedHtml() = GithubMarkdownException("GitHub HTML 超过 4 MiB 限制，无法显示，请缩小内容后重试")

private fun JsonObject.requiredObject(key: String): JsonObject =
    this[key] as? JsonObject ?: throw GithubMarkdownException("GitHub HTML 对象不存在或无权访问，请检查后重试")

private fun JsonObject.requiredHtml(key: String): String {
    val value = this[key] as? JsonPrimitive
    if (value == null || !value.isString) {
        throw GithubMarkdownException("GitHub 响应缺少 HTML 字段，请重试")
    }
    // 空字符串是合法的空正文；只有明确存在的字符串字段才能返回空结果
    return value.content
}

internal fun ResponseBody.readLimitedHtml(): String {
    val input = source()
    if (contentLength() > MaxHtmlBytes || input.request(MaxHtmlBytes + 1L)) throw oversizedHtml()
    return input.readString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}
