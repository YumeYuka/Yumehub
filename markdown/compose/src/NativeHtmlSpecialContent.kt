package moe.yumeyuka.markdown.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.yumeyuka.markdown.core.HtmlElement

/**
 * 公式、Mermaid 图与内联 SVG 的原生引擎（RaTeX / Merman / AndroidSVG）已移除。
 *
 * 这里只报告该节点无法渲染，与库里其他失败路径保持同一种文案和诊断，不回落源码文本、 不打开外部浏览器，也不以占位冒充已支持。恢复能力需要在宿主侧重新评估引擎，而不是在库中 藏一条静默降级路径。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun NativeHtmlMath(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
    onLayout: ((widthPx: Int, heightPx: Int, baselinePx: Float) -> Unit)? = null,
) {
    NativeHtmlFailure(
        element.key,
        "MATH_RENDERER_UNAVAILABLE",
        "Math renderer removed",
        context,
        modifier,
    )
}

@Composable
internal fun NativeHtmlDiagram(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
) {
    NativeHtmlFailure(
        element.key,
        "DIAGRAM_RENDERER_UNAVAILABLE",
        "Diagram renderer removed",
        context,
        modifier,
    )
}

@Composable
internal fun NativeHtmlSvg(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
) {
    NativeHtmlFailure(
        element.key,
        "SVG_RENDERER_UNAVAILABLE",
        "SVG renderer removed",
        context,
        modifier,
    )
}

@Composable
internal fun NativeHtmlFailure(
    nodeKey: String,
    code: String,
    message: String,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(nodeKey, code, message) {
        context.actions.onError?.invoke(HtmlRenderIssue(nodeKey, code, message))
    }
    DisableSelection {
        BasicText(
            context.style.strings.renderingFailed.ifBlank {
                context.style.strings.unsupportedContent
            },
            modifier
                .heightIn(min = 1.dp)
                .background(context.style.palette.codeBackground)
                .testTag("html-engine:$nodeKey:failed"),
            style = context.textStyle.copy(color = context.style.palette.textSecondary),
        )
    }
}
