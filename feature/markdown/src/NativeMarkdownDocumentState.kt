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
import moe.yumeyuka.yumehub.ui.component.MarkdownContent
import moe.yumeyuka.yumehub.ui.component.placeholders.DocumentGeometry

/** 引用身份区分 A → B → A 的内容更新，不把相同原文误认为同一轮请求。 */
internal class NativeMarkdownRevision

internal data class NativeMarkdownDocument(
    val request: MarkdownContent,
    val revision: NativeMarkdownRevision,
    val html: String,
    val content: HtmlDocument,
    val viewerLogin: String? = null,
    val geometry: DocumentGeometry,
)

internal enum class NativeMarkdownFailure {
    FETCH,
    PARSE,
}

internal data class NativeMarkdownDocumentState(
    val request: MarkdownContent,
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
    val request: MarkdownContent,
    val viewerLogin: String?,
)

internal data class CachedNativeMarkdownDocument(
    val html: String,
    val content: HtmlDocument,
    val viewerLogin: String?,
    val geometry: DocumentGeometry = DocumentGeometry(),
)

/** 路由仍存活时保留已解析正文，避免 LazyColumn 回收阅读 item 后再次请求和解析。 */
internal class NativeMarkdownDocumentCacheViewModel : ViewModel() {
    private val documents =
        LinkedHashMap<NativeMarkdownDocumentCacheKey, CachedNativeMarkdownDocument>(
            MaxRetainedMarkdownDocuments,
            0.75f, true,
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
    fun get(request: MarkdownContent, viewerLogin: String?): CachedNativeMarkdownDocument? {
        clearForAccount(viewerLogin)
        return documents[NativeMarkdownDocumentCacheKey(request, viewerLogin)]
    }

    @Synchronized
    fun put(
        request: MarkdownContent,
        viewerLogin: String?,
        html: String,
        content: HtmlDocument,
    ): CachedNativeMarkdownDocument {
        clearForAccount(viewerLogin)
        val key = NativeMarkdownDocumentCacheKey(request, viewerLogin)
        val previous = documents.remove(key)
        previous?.let { cachedHtmlCharacters -= it.html.length }
        val cached = CachedNativeMarkdownDocument(html, content, viewerLogin,
            previous?.takeIf { it.html == html }?.geometry ?: DocumentGeometry())
        if (html.length <= MaxRetainedMarkdownHtmlCharacters) {
            documents[key] = cached
            cachedHtmlCharacters += html.length
        }
        evictOldDocuments()
        return cached
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
    content: MarkdownContent,
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
        remember(revision) {
            mutableStateOf(
                cachedDocument?.let {
                    NativeMarkdownDocument(request, revision, it.html, it.content, it.viewerLogin, it.geometry)
                },
            )
        }
    var loading by remember(request, retry, viewerLogin) { mutableStateOf(cachedDocument == null) }
    var failure by remember(revision) { mutableStateOf<NativeMarkdownFailure?>(null) }
    var finished by remember(revision) { mutableStateOf(cachedDocument != null) }

    LaunchedEffect(revision, requestEnabled) {
        if (!requestEnabled) return@LaunchedEffect
        if (finished) return@LaunchedEffect
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
                val cached = documentCache.put(request, viewerLogin, html, parsed)
                document = NativeMarkdownDocument(request, revision, html, parsed, viewerLogin, cached.geometry)
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
                finished = true
            }
        }
    }
    return NativeMarkdownDocumentState(
        request = request,
        revision = revision,
        document = document,
        loading = loading,
        failure = failure,
        requestEnabled = requestEnabled,
        retry = { retry++ },
    )
}

internal fun shouldUnfurlNativeMarkdownReferences(
    request: MarkdownContent,
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
