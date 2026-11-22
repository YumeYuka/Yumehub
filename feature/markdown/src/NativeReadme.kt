package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.yumeyuka.markdown.compose.HtmlStyles
import moe.yumeyuka.markdown.compose.NativeHtmlActions
import moe.yumeyuka.markdown.compose.NativeHtmlDocument
import moe.yumeyuka.markdown.compose.NativeHtmlImageRequest
import moe.yumeyuka.markdown.compose.NativeHtmlSlots
import moe.yumeyuka.markdown.core.GithubHtmlParser
import moe.yumeyuka.markdown.core.HtmlDocument
import moe.yumeyuka.markdown.core.HtmlParseOptions
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.loadGithubImageBitmap

/**
 * Markdown 正文的原生渲染（支持 README、Issue、评论、探索页卡片等）。
 *
 * 输入仍是数据层取回的官方 HTML。解析与样式准备在后台线程完成，首帧不阻塞组合。失败通过 Toast 提示，不回退 WebView，也不把 HTML 源码当正文。样式与浏览器参考同源，详见
 * [NativeReadmeStyles]。
 */
@Composable
internal fun NativeMarkdownDocument(
    html: String,
    pageUrl: String?,
    repositoryRootUrl: String?,
    readme: Boolean,
    ref: String?,
    scrollToAnchor: String?,
    actions: GithubMarkdownActions,
    modifier: Modifier = Modifier,
    viewerLogin: String? = null,
) {
    val context = LocalContext.current
    val colors = GlasmioTheme.colors
    val bodyFont = GlasmioTheme.typography.bodyLarge.fontFamily
    val strings = LocalGithubStrings.current.Markdown
    val state by
        produceState<NativeMarkdownState>(
            NativeMarkdownState.Loading,
            html,
            pageUrl,
            repositoryRootUrl,
            readme,
            ref,
            colors.isDark,
            bodyFont,
            strings,
            viewerLogin,
        ) {
            value = NativeMarkdownState.Loading
            value =
                withContext(Dispatchers.IO) {
                    try {
                        val document =
                            GithubHtmlParser.parse(
                                html,
                                HtmlParseOptions(
                                    pageUrl = pageUrl,
                                    repositoryRootUrl = repositoryRootUrl,
                                    readme = readme,
                                    ref = ref,
                                    viewerLogin = viewerLogin,
                                ),
                            )
                        NativeMarkdownState.Ready(
                            document,
                            NativeReadmeStyles.build(
                                context.applicationContext.assets,
                                colors,
                                strings,
                                bodyFont,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        NativeMarkdownState.Failed
                    }
                }
        }
    when (val shown = state) {
        NativeMarkdownState.Loading -> Unit
        NativeMarkdownState.Failed -> GithubLoadFailureToast(Unit, strings.RenderingFailed)
        is NativeMarkdownState.Ready ->
            NativeHtmlDocument(
                content = shown.document,
                style = shown.style,
                modifier = modifier,
                actions =
                    NativeHtmlActions(
                        onOpenLink = actions.onOpenLink,
                        onTaskChanged = actions.onTaskChanged,
                        onCommitSuggestion = actions.onCommitSuggestion,
                        onReady = actions.onReady,
                    ),
                slots =
                    remember(actions) {
                        NativeHtmlSlots(
                            image = { request, imageModifier ->
                                NativeMarkdownImage(request, imageModifier)
                            },
                        )
                    },
                scrollToAnchor = scrollToAnchor,
            )
    }
}

/** 兼容原有 README 调用别名。 */
@Composable
internal fun NativeReadme(
    html: String,
    pageUrl: String?,
    repositoryRootUrl: String?,
    ref: String?,
    scrollToAnchor: String?,
    actions: GithubMarkdownActions,
    modifier: Modifier = Modifier,
) {
    NativeMarkdownDocument(
        html = html,
        pageUrl = pageUrl,
        repositoryRootUrl = repositoryRootUrl,
        readme = true,
        ref = ref,
        scrollToAnchor = scrollToAnchor,
        actions = actions,
        modifier = modifier,
    )
}

private sealed interface NativeMarkdownState {
    data object Loading : NativeMarkdownState

    data class Ready(val document: HtmlDocument, val style: HtmlStyles) : NativeMarkdownState

    data object Failed : NativeMarkdownState
}

/** 图片走应用统一 Sketch 缓存；解码后回报原始宽高比，正文按真实尺寸重排。 */
@Composable
internal fun NativeMarkdownImage(request: NativeHtmlImageRequest, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(request.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(request.url, request.fallbackUrl) {
        val loaded =
            loadGithubImageBitmap(context, request.url)
                ?: request.fallbackUrl?.let { loadGithubImageBitmap(context, it) }
        if (loaded == null) request.onError?.invoke("图片加载失败")
        else {
            bitmap = loaded
            request.onIntrinsicSize?.invoke(loaded.width, loaded.height)
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = request.alt,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}
