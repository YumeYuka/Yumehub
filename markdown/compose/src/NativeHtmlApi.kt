package moe.yumeyuka.markdown.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.Dp

/** 原生内容只发送业务意图，账号、网络和导航仍由宿主拥有。 */
data class NativeHtmlActions(
    val onOpenLink: (String) -> Unit = {},
    val onTaskChanged: ((position: Int, checked: Boolean) -> Unit)? = null,
    val onCommitSuggestion: ((suggestionId: String, html: String) -> Unit)? = null,
    val onReady: (() -> Unit)? = null,
    val onError: ((HtmlRenderIssue) -> Unit)? = null,
)

enum class HtmlIssueSeverity {
    INFO,
    WARNING,
    ERROR,
    FATAL,
}

enum class HtmlIssueScope {
    NODE,
    DOCUMENT,
}

enum class HtmlIssueRecovery {
    NONE,
    RETRY_NODE,
    RELOAD_DOCUMENT,
}

/** 默认错误仅影响当前节点；整篇不可用必须由产生处明确声明，不按错误码猜测。 */
data class HtmlRenderIssue(
    val nodeKey: String,
    val code: String,
    val message: String,
    val severity: HtmlIssueSeverity = HtmlIssueSeverity.ERROR,
    val scope: HtmlIssueScope = HtmlIssueScope.NODE,
    val recovery: HtmlIssueRecovery = HtmlIssueRecovery.NONE,
) {
    val isDocumentFatal: Boolean
        get() = severity == HtmlIssueSeverity.FATAL && scope == HtmlIssueScope.DOCUMENT
}

/** 绘制矩形相对真实 Canvas 内容原点；引擎尺寸包含可能超出行内占位的下降部。 */
data class NativeHtmlPaintMetrics(
    val nodeKey: String,
    val paintBoundsLocalPx: Rect,
    val baselinePx: Float? = null,
    val engineWidthPx: Float? = null,
    val engineHeightPx: Float? = null,
)

val NativeHtmlPaintMetricsKey =
    SemanticsPropertyKey<NativeHtmlPaintMetrics>("NativeHtmlPaintMetrics")

/** 图片尺寸使用 HTML 的 CSS px；未指定尺寸时宿主应保留图片原始宽高比。 */
data class NativeHtmlImageRequest(
    val url: String,
    val fallbackUrl: String? = null,
    val alt: String? = null,
    val title: String? = null,
    val width: Float? = null,
    val height: Float? = null,
    val inline: Boolean = false,
    val svg: String? = null,
    val onIntrinsicSize: ((width: Int, height: Int) -> Unit)? = null,
    val onError: ((message: String) -> Unit)? = null,
    val nodeKey: String = "",
)

data class NativeHtmlMediaRequest(
    val url: String,
    val posterUrl: String? = null,
    val mimeType: String? = null,
    val audio: Boolean = false,
    val controls: Boolean = true,
    val loop: Boolean = false,
    val muted: Boolean = false,
    val onError: ((message: String) -> Unit)? = null,
    val nodeKey: String = "",
)

data class NativeHtmlIconRequest(
    val name: String,
    val classes: Set<String> = emptySet(),
    val contentDescription: String? = null,
    val tint: Color,
    val size: Dp,
)

enum class NativeHtmlActionKind {
    COPY_CODE,
    APPLY_SUGGESTION,
}

data class NativeHtmlActionRequest(
    val kind: NativeHtmlActionKind,
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/** 所有宿主视觉依赖均通过槽位注入，库不依赖具体应用的主题或图片客户端。 */
class NativeHtmlSlots(
    val image: (@Composable (NativeHtmlImageRequest, Modifier) -> Unit)? = null,
    val media: (@Composable (NativeHtmlMediaRequest, Modifier) -> Unit)? = null,
    val icon: (@Composable (NativeHtmlIconRequest, Modifier) -> Unit)? = null,
    val actionButton: (@Composable (NativeHtmlActionRequest, Modifier) -> Unit)? = null,
)
