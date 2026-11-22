package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.yumeyuka.markdown.core.GithubHtmlParser
import moe.yumeyuka.markdown.core.HtmlDocument
import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlNode
import moe.yumeyuka.markdown.core.HtmlParseOptions
import moe.yumeyuka.yumehub.core.model.GithubLinkTarget
import moe.yumeyuka.yumehub.core.model.parseGithubLink
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent

/** 引用身份区分 A → B → A 的内容更新，不把相同原文误认为同一轮请求。 */
internal class NativeMarkdownRevision

internal data class NativeMarkdownDocument(
    val request: GithubMarkdownContent,
    val revision: NativeMarkdownRevision,
    val html: String,
    val content: HtmlDocument,
    val viewerLogin: String? = null,
)

internal enum class NativeMarkdownFailure {
    FETCH,
    PARSE,
}

internal data class NativeMarkdownDocumentState(
    val request: GithubMarkdownContent,
    val revision: NativeMarkdownRevision,
    val document: NativeMarkdownDocument?,
    val loading: Boolean,
    val failure: NativeMarkdownFailure?,
    val requestEnabled: Boolean,
    val retry: () -> Unit,
) {
    fun owns(document: NativeMarkdownDocument): Boolean =
        this.document === document && revision === document.revision && request == document.request

    fun allowsInteraction(document: NativeMarkdownDocument): Boolean =
        requestEnabled && owns(document) && !loading && failure == null
}

private const val MaxRetainedMarkdownDocuments = 6
private const val MaxRetainedMarkdownHtmlCharacters = 4 * 1024 * 1024

private data class NativeMarkdownDocumentCacheKey(
    val request: GithubMarkdownContent,
    val viewerLogin: String?,
)

internal data class CachedNativeMarkdownDocument(
    val html: String,
    val content: HtmlDocument,
    val viewerLogin: String?,
    val webHeightDp: Float = 1f,
)

/** 路由仍存活时保留已解析正文，避免 LazyColumn 回收阅读 item 后再次请求和解析。 */
internal class NativeMarkdownDocumentCacheViewModel : ViewModel() {
    private val documents =
        LinkedHashMap<NativeMarkdownDocumentCacheKey, CachedNativeMarkdownDocument>(
            MaxRetainedMarkdownDocuments,
            0.75f,
        )
    private var cachedLogin: String? = null
    private var cachedSession: Long? = null
    private var cachedHtmlCharacters = 0

    @Synchronized
    fun bindSession(session: Long) {
        if (cachedSession == session) return
        documents.clear()
        cachedHtmlCharacters = 0
        cachedSession = session
    }

    @Synchronized
    fun get(request: GithubMarkdownContent, viewerLogin: String?): CachedNativeMarkdownDocument? {
        clearForAccount(viewerLogin)
        return documents[NativeMarkdownDocumentCacheKey(request, viewerLogin)]
    }

    @Synchronized
    fun put(
        request: GithubMarkdownContent,
        viewerLogin: String?,
        html: String,
        content: HtmlDocument,
    ) {
        clearForAccount(viewerLogin)
        val key = NativeMarkdownDocumentCacheKey(request, viewerLogin)
        val previous = documents.remove(key)
        previous?.let { cachedHtmlCharacters -= it.html.length }
        if (html.length <= MaxRetainedMarkdownHtmlCharacters) {
            documents[key] =
                CachedNativeMarkdownDocument(
                    html,
                    content,
                    viewerLogin,
                    previous?.takeIf { it.html == html }?.webHeightDp ?: 1f,
                )
            cachedHtmlCharacters += html.length
        }
        evictOldDocuments()
    }

    /** 高度随路由正文保留，不依赖临时渲染会话的 saveable key。旧账号与旧 HTML 不得写回。 */
    @Synchronized
    fun updateWebHeight(
        request: GithubMarkdownContent,
        viewerLogin: String?,
        html: String,
        heightDp: Float,
    ) {
        if (cachedLogin != viewerLogin || !heightDp.isFinite() || heightDp <= 0f) return
        val key = NativeMarkdownDocumentCacheKey(request, viewerLogin)
        val cached = documents[key]?.takeIf { it.html == html } ?: return
        documents[key] = cached.copy(webHeightDp = heightDp)
    }

    private fun clearForAccount(viewerLogin: String?) {
        if (cachedLogin == viewerLogin) return
        documents.clear()
        cachedHtmlCharacters = 0
        cachedLogin = viewerLogin
    }

    private fun evictOldDocuments() {
        while (
            documents.size > MaxRetainedMarkdownDocuments ||
                cachedHtmlCharacters > MaxRetainedMarkdownHtmlCharacters
        ) {
            val oldest = documents.entries.iterator()
            val entry = oldest.next()
            cachedHtmlCharacters -= entry.value.html.length
            oldest.remove()
        }
    }
}

/** HTML 获取沿用数据仓库；解析在 Default 上执行，状态和回调不进入可复用库。 */
@Composable
internal fun rememberNativeMarkdownDocument(
    content: GithubMarkdownContent,
    repository: GithubMarkdownRepository,
    isCurrentAccount: () -> Boolean,
    requestEnabled: Boolean = true,
): NativeMarkdownDocumentState {
    val request = content.copy(scrollToAnchor = null, selectionHosted = false)
    val viewerLogin = repository.viewerLogin
    val documentCache: NativeMarkdownDocumentCacheViewModel =
        viewModel(key = "github-markdown-document-cache")
    val session = repository.sessionRevision.value
    var retry by remember(request) { mutableIntStateOf(0) }
    val revision = remember(request, retry, viewerLogin) { NativeMarkdownRevision() }
    val cachedDocument =
        remember(request, viewerLogin, retry, documentCache, session) {
            documentCache.bindSession(session)
            if (retry == 0) documentCache.get(request, viewerLogin) else null
        }
    val latestRevision = rememberUpdatedState(revision)
    val latestAccount = rememberUpdatedState(isCurrentAccount)
    var document by
        remember(viewerLogin) {
            mutableStateOf(
                cachedDocument?.let {
                    NativeMarkdownDocument(request, revision, it.html, it.content, it.viewerLogin)
                },
            )
        }
    var loading by remember(request, retry, viewerLogin) { mutableStateOf(cachedDocument == null) }
    var failure by remember { mutableStateOf<NativeMarkdownFailure?>(null) }
    var finishedRevision by remember { mutableStateOf<NativeMarkdownRevision?>(null) }

    LaunchedEffect(revision, requestEnabled) {
        if (!requestEnabled) return@LaunchedEffect
        if (document?.revision !== revision && cachedDocument != null) {
            document =
                NativeMarkdownDocument(
                    request = request,
                    revision = revision,
                    html = cachedDocument.html,
                    content = cachedDocument.content,
                    viewerLogin = cachedDocument.viewerLogin,
                )
        }
        if (document?.revision === revision) {
            loading = false
            failure = null
            finishedRevision = revision
            return@LaunchedEffect
        }
        if (finishedRevision === revision) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        failure = null
        var stage = NativeMarkdownFailure.FETCH
        try {
            var html =
                request.html?.takeUnless { request.preview }
                    ?: repository.render(
                        text = request.text,
                        pageUrl = request.pageUrl,
                        repositoryRootUrl = request.repositoryRootUrl,
                        nodeId = request.nodeId,
                        readme = request.readme,
                        ref = request.ref,
                        preview = request.preview,
                        forceRefresh = retry > 0,
                    )
            ensureActive()
            stage = NativeMarkdownFailure.PARSE
            suspend fun parse(value: String) =
                withContext(Dispatchers.Default) {
                    GithubHtmlParser.parse(
                        value,
                        HtmlParseOptions(
                            pageUrl = request.pageUrl,
                            repositoryRootUrl = request.repositoryRootUrl,
                            readme = request.readme,
                            ref = request.ref,
                            viewerLogin = viewerLogin,
                        ),
                    )
                }
            var parsed = parse(html)
            if (shouldUnfurlNativeMarkdownReferences(request, parsed)) {
                // REST body_html 只有 #编号；使用现有官方字段取得标题与实时状态图标。
                stage = NativeMarkdownFailure.FETCH
                html =
                    repository.render(
                        text = request.text,
                        pageUrl = request.pageUrl,
                        repositoryRootUrl = request.repositoryRootUrl,
                        nodeId = request.nodeId,
                        forceRefresh = retry > 0,
                    )
                ensureActive()
                stage = NativeMarkdownFailure.PARSE
                parsed = parse(html)
            }
            ensureActive()
            if (latestAccount.value() && latestRevision.value === revision) {
                document = NativeMarkdownDocument(request, revision, html, parsed, viewerLogin)
                documentCache.put(request, viewerLogin, html, parsed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (latestAccount.value() && latestRevision.value === revision) failure = stage
        } finally {
            if (
                currentCoroutineContext().isActive &&
                    latestAccount.value() &&
                    latestRevision.value === revision
            ) {
                loading = false
                finishedRevision = revision
            }
        }
    }
    return NativeMarkdownDocumentState(
        request = request,
        revision = revision,
        document = document,
        loading = loading || document?.revision !== revision,
        failure = failure,
        requestEnabled = requestEnabled,
        retry = { retry++ },
    )
}

internal fun shouldUnfurlNativeMarkdownReferences(
    request: GithubMarkdownContent,
    document: HtmlDocument,
): Boolean {
    if (request.html == null || request.preview || request.readme) return false
    // 评论调用方也会传父议题 URL；没有对象 ID 时不能用父正文替换评论。
    if (request.nodeId.isNullOrBlank()) return false
    // Release 的 descriptionHTML 无展开参数，不能用工作项正文查询替换。
    if (request.pageUrl?.let(::parseGithubLink) is GithubLinkTarget.Releases) return false
    fun hasExpandedReference(nodes: List<HtmlNode>): Boolean =
        nodes.filterIsInstance<HtmlElement>().any {
            it.tag == "svg" || it.hasClass("issue-shorthand") || hasExpandedReference(it.children)
        }
    fun needsUnfurl(nodes: List<HtmlNode>): Boolean =
        nodes.filterIsInstance<HtmlElement>().any { element ->
            (element.tag == "a" &&
                element.hasClass("issue-link") &&
                !hasExpandedReference(element.children)) || needsUnfurl(element.children)
        }
    return needsUnfurl(document.children)
}
