package moe.yumeyuka.markdown.compose

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import moe.yumeyuka.markdown.core.HtmlDocument
import moe.yumeyuka.markdown.core.HtmlElement

/** 列表长文用 [NativeHtmlSelectionMode.Inherited]，由宿主拥有固定视口选择与纵向滚动。 */
@Composable
fun NativeHtmlDocument(
    content: HtmlDocument,
    style: HtmlStyles,
    modifier: Modifier = Modifier,
    actions: NativeHtmlActions = NativeHtmlActions(),
    slots: NativeHtmlSlots = NativeHtmlSlots(),
    scrollToAnchor: String? = null,
    maxViewportHeightPx: Int = 120_000,
    documentRevision: Any? = null,
    selectionMode: NativeHtmlSelectionMode = NativeHtmlSelectionMode.Own,
) {
    val documentModifier =
        if (selectionMode == NativeHtmlSelectionMode.Own) {
            require(maxViewportHeightPx > 0)
            val density = LocalDensity.current
            val maxHeight = with(density) { maxViewportHeightPx.toDp() }
            modifier.heightIn(max = maxHeight)
        } else modifier
    NativeHtmlContainer(
        content = content,
        style = style,
        modifier = documentModifier,
        actions = actions,
        slots = slots,
        scrollToAnchor = scrollToAnchor,
        contentPadding = PaddingValues(),
        documentRevision = documentRevision,
        fixedSelectionViewport = false,
        selectionMode = selectionMode,
    )
}

/** 预览的高度由宿主固定，正文在视口内滚动，内边距不改变文档语义。 */
@Composable
fun NativeHtmlViewport(
    content: HtmlDocument,
    style: HtmlStyles,
    modifier: Modifier = Modifier,
    actions: NativeHtmlActions = NativeHtmlActions(),
    slots: NativeHtmlSlots = NativeHtmlSlots(),
    contentPadding: PaddingValues = PaddingValues(),
    scrollToAnchor: String? = null,
    documentRevision: Any? = null,
) {
    NativeHtmlContainer(
        content,
        style,
        modifier,
        actions,
        slots,
        scrollToAnchor,
        contentPadding,
        documentRevision,
        fixedSelectionViewport = true,
        selectionMode = NativeHtmlSelectionMode.Own,
    )
}

@Composable
private fun NativeHtmlContainer(
    content: HtmlDocument,
    style: HtmlStyles,
    modifier: Modifier,
    actions: NativeHtmlActions,
    slots: NativeHtmlSlots,
    scrollToAnchor: String?,
    contentPadding: PaddingValues,
    documentRevision: Any?,
    fixedSelectionViewport: Boolean,
    selectionMode: NativeHtmlSelectionMode,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val requesters = remember { mutableMapOf<String, BringIntoViewRequester>() }
    val scrollState =
        if (selectionMode == NativeHtmlSelectionMode.Own) rememberScrollState() else null
    val density = LocalDensity.current
    val cssTypography =
        remember(style, density.density, density.fontScale) { HtmlCssTypography(style, density) }
    val minimumHeight = with(density) { 1.toDp() }
    val latestActions by rememberUpdatedState(actions)
    var ready by remember(content, documentRevision) { mutableStateOf(false) }
    var requestedAnchor by remember { mutableStateOf<String?>(null) }
    var anchorRequestVersion by remember { mutableStateOf(0) }
    val openAnchor: (String) -> Unit = { anchor ->
        requestedAnchor = anchor
        anchorRequestVersion++
    }
    LaunchedEffect(scrollToAnchor) {
        requestedAnchor = scrollToAnchor
        anchorRequestVersion++
    }
    LaunchedEffect(content, requestedAnchor, anchorRequestVersion) {
        val target = requestedAnchor?.let(content::resolveAnchor) ?: return@LaunchedEffect
        // 锚点位于折叠内容时先展开祖先；等实际布局完成后再向父容器请求可见。
        target.ancestorKeys.forEach { expanded[it] = true }
        withFrameNanos {}
        withFrameNanos {}
        requesters[target.nodeKey]?.bringIntoView()
    }
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = minimumHeight)
                .then(
                    if (!fixedSelectionViewport && scrollState != null)
                        Modifier.verticalScroll(scrollState)
                    else Modifier,
                )
                .onGloballyPositioned { coordinates ->
                    if (!ready && coordinates.size.width > 0 && coordinates.size.height > 0) {
                        ready = true
                        latestActions.onReady?.invoke()
                    }
                },
        propagateMinConstraints = true,
    ) {
        val context =
            NativeHtmlRenderContext(
                style,
                actions,
                slots,
                expanded,
                requesters,
                cssTypography.bodyStyle(),
                openAnchor,
                viewportWidthCssPx = maxWidth.value,
                cssTypography = cssTypography,
            )
        val documentContent: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth()
                    .then(
                        if (fixedSelectionViewport)
                            Modifier.verticalScroll(checkNotNull(scrollState))
                                .testTag("native-html:viewport-scroll")
                        else Modifier,
                    )
                    .padding(contentPadding),
            ) {
                NativeHtmlChildren(content.children, context)
            }
        }
        if (selectionMode == NativeHtmlSelectionMode.Inherited) {
            documentContent()
        } else {
            SelectionContainer(
                modifier = if (fixedSelectionViewport) Modifier.fillMaxSize() else Modifier,
                content = documentContent,
            )
        }
    }
}

internal data class NativeHtmlRenderContext(
    val style: HtmlStyles,
    val actions: NativeHtmlActions,
    val slots: NativeHtmlSlots,
    val expandedDetails: MutableMap<String, Boolean>,
    val anchorRequesters: MutableMap<String, BringIntoViewRequester>,
    val textStyle: TextStyle,
    val onOpenAnchor: (String) -> Unit,
    val viewportWidthCssPx: Float,
    val cssTypography: HtmlCssTypography,
    val ancestorClasses: List<Set<String>> = emptyList(),
    val alertKind: String? = null,
    val listDepth: Int = 0,
    val inCode: Boolean = false,
    val inHeading: Boolean = false,
    val excludedImageKeys: Set<String> = emptySet(),
    val textExclusion: NativeHtmlTextExclusion? = null,
)

@Composable
internal fun nativeHtmlNodeModifier(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
): Modifier {
    val requester = remember(element.key) { BringIntoViewRequester() }
    DisposableEffect(element.key, context.anchorRequesters) {
        context.anchorRequesters[element.key] = requester
        onDispose {
            if (context.anchorRequesters[element.key] === requester) {
                context.anchorRequesters.remove(element.key)
            }
        }
    }
    val id = element.attr("data-fixture-id") ?: element.attr("id") ?: element.key
    return modifier.bringIntoViewRequester(requester).testTag("html:$id")
}

internal fun HtmlElement.classes(): Set<String> =
    attr("class")?.splitToSequence(' ', '\t', '\r', '\n')?.filter(String::isNotEmpty)?.toSet()
        ?: emptySet()

internal fun HtmlElement.cssProperty(name: String): String? =
    attr("style")
        ?.split(';')
        ?.mapNotNull { declaration ->
            val separator = declaration.indexOf(':')
            if (separator < 0) null
            else
                declaration.take(separator).trim().lowercase() to
                    declaration.drop(separator + 1).trim()
        }
        ?.lastOrNull { it.first == name.lowercase() }
        ?.second

internal fun HtmlElement.cssDimension(name: String): Float? =
    (attr(name) ?: cssProperty(name))?.trim()?.removeSuffix("px")?.toFloatOrNull()

internal fun HtmlElement.isHidden(dark: Boolean): Boolean {
    if (attr("hidden") != null) return true
    if (cssProperty("display") == "none" || cssProperty("visibility") == "hidden") return true
    if (
        classes().any {
            it == "anchor" || it == "email-hidden-toggle" || it == "email-hidden-reply"
        }
    ) {
        return true
    }
    val target = attr("href") ?: attr("src")
    if (dark && target?.endsWith("#gh-light-mode-only") == true) return true
    if (!dark && target?.endsWith("#gh-dark-mode-only") == true) return true
    return false
}
