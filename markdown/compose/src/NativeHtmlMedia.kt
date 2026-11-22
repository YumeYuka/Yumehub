package moe.yumeyuka.markdown.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import moe.yumeyuka.markdown.core.HtmlElement

@Composable
internal fun NativeHtmlImage(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    onIntrinsicSize: ((width: Int, height: Int) -> Unit)? = null,
    picture: HtmlElement? = null,
) {
    if (element.tag == "svg") {
        NativeHtmlSvg(element, context, modifier)
        return
    }
    val image = context.slots.image
    val media =
        selectNativeHtmlImageSource(
            element,
            picture,
            context.style.palette.isDark,
            LocalDensity.current.density,
            context.viewportWidthCssPx,
        )
    val url = media?.url ?: element.attr("src")
    if (image == null || url.isNullOrBlank()) {
        NativeHtmlFailure(
            element.key,
            if (image == null) "IMAGE_SLOT_REQUIRED" else "IMAGE_URL_MISSING",
            "The host must provide a native image loader and a resolved image URL",
            context,
            modifier,
        )
        return
    }
    var decodedSize by remember(element.key, url) { mutableStateOf<IntSize?>(null) }
    val imageDensity = media?.density ?: 1.0
    val naturalSize = decodedSize?.let {
        IntSize(
            (it.width / imageDensity).roundToInt().coerceAtLeast(1),
            (it.height / imageDensity).roundToInt().coerceAtLeast(1),
        )
    }
    LaunchedEffect(naturalSize, onIntrinsicSize) {
        naturalSize?.let { onIntrinsicSize?.invoke(it.width, it.height) }
    }
    val requestedWidth = element.cssDimension("width")?.takeIf { it > 0f }
    val requestedHeight = element.cssDimension("height")?.takeIf { it > 0f }
    val request =
        NativeHtmlImageRequest(
            url = url,
            fallbackUrl = media?.fallbackUrl,
            alt = element.attr("alt"),
            title = element.attr("title"),
            width = requestedWidth,
            height = requestedHeight,
            inline = inline,
            nodeKey = element.key,
            onIntrinsicSize = { width, height ->
                if (width > 0 && height > 0) decodedSize = IntSize(width, height)
            },
            onError = { message ->
                context.actions.onError?.invoke(
                    HtmlRenderIssue(element.key, "IMAGE_LOAD_FAILED", message),
                )
            },
        )
    val accessibility = Modifier.semantics {
        request.alt?.takeIf(String::isNotBlank)?.let { contentDescription = it }
    }
    if (inline) {
        // 异步加载尚未产生 Image 时也保留同一个占位布局节点。
        Box(modifier.then(accessibility), propagateMinConstraints = true) {
            image(request, Modifier.fillMaxSize())
        }
    } else {
        val alignment =
            when ((element.attr("align") ?: element.cssProperty("text-align"))?.lowercase()) {
                "center" -> Alignment.Center
                "right",
                "end" -> Alignment.CenterEnd
                else ->
                    when (context.textStyle.textAlign) {
                        TextAlign.Center -> Alignment.Center
                        TextAlign.Right,
                        TextAlign.End -> Alignment.CenterEnd
                        else -> Alignment.CenterStart
                    }
            }
        BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = alignment) {
            val natural = naturalSize
            val width =
                (requestedWidth ?: natural?.width?.toFloat())?.dp?.let { minOf(it, maxWidth) }
            var imageModifier = accessibility.widthIn(max = maxWidth)
            if (width != null) {
                imageModifier = imageModifier.width(width)
            }
            if (requestedHeight != null) {
                imageModifier = imageModifier.height(requestedHeight.dp)
            } else if (natural != null) {
                val ratio = natural.width.toFloat() / natural.height
                if (ratio.isFinite() && ratio > 0f) {
                    imageModifier = imageModifier.aspectRatio(ratio)
                }
            }
            image(request, imageModifier)
        }
    }
}

@Composable
internal fun NativeHtmlIcon(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
) {
    val slot = context.slots.icon
    if (slot == null) {
        NativeHtmlSvg(element, context, modifier)
        return
    }
    val classes = element.classes()
    val name =
        classes.firstOrNull { it.startsWith("octicon-") }?.removePrefix("octicon-")
            ?: element.attr("data-icon")
            ?: "unknown"
    val size = context.cssTypography.emSpacing(context.textStyle.fontSize, 1f)
    slot(
        NativeHtmlIconRequest(
            name,
            classes,
            element.attr("aria-label"),
            context.textStyle.color,
            size,
        ),
        modifier
            .padding(
                start = if (element.hasClass("ml-1")) 4.dp else 0.dp,
                end = nativeHtmlIconEndMargin(element),
            )
            .size(size),
    )
}

internal fun nativeHtmlIconEndMargin(element: HtmlElement) =
    when {
        element.hasClass("mr-2") -> 8.dp
        element.hasClass("mr-1") -> 4.dp
        else -> 0.dp
    }

@Composable
internal fun NativeHtmlTask(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
) {
    val task = element.semantic.task ?: return
    val callback = context.actions.onTaskChanged
    val enabled = callback != null
    val fill = context.style.palette.link
    val background = context.style.palette.background
    val border = context.style.palette.border
    var canvasSize by remember(element.key) { mutableStateOf(IntSize.Zero) }
    Canvas(
        modifier
            .size(16.dp)
            .onSizeChanged { canvasSize = it }
            .toggleable(
                value = task.checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { checked -> callback?.invoke(task.position, checked) },
            )
            .semantics {
                element.attr("aria-label")?.let { contentDescription = it }
                if (canvasSize.width > 0 && canvasSize.height > 0) {
                    this[NativeHtmlPaintMetricsKey] =
                        NativeHtmlPaintMetrics(
                            element.key,
                            Rect(0f, 0f, canvasSize.width.toFloat(), canvasSize.height.toFloat()),
                        )
                }
            },
    ) {
        val stroke = 1.dp.toPx()
        val corner = CornerRadius(2.dp.toPx())
        drawRoundRect(if (task.checked) fill else background, cornerRadius = corner)
        if (!task.checked)
            drawRoundRect(
                border,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = size.copy(width = size.width - stroke, height = size.height - stroke),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
        else {
            val ink = background
            drawLine(
                ink,
                Offset(size.width * .22f, size.height * .5f),
                Offset(size.width * .43f, size.height * .72f),
                stroke * 1.8f,
                StrokeCap.Round,
            )
            drawLine(
                ink,
                Offset(size.width * .43f, size.height * .72f),
                Offset(size.width * .8f, size.height * .26f),
                stroke * 1.8f,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun NativeHtmlMedia(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
) {
    val source =
        element.children.filterIsInstance<HtmlElement>().firstOrNull {
            it.tag == "source" && !it.attr("src").isNullOrBlank()
        }
    val url = element.semantic.media?.url ?: element.attr("src") ?: source?.attr("src")
    val slot = context.slots.media
    if (url.isNullOrBlank() || slot == null) {
        NativeHtmlFailure(
            element.key,
            if (slot == null) "MEDIA_SLOT_REQUIRED" else "MEDIA_URL_MISSING",
            "The host must provide a native media player and a resolved source URL",
            context,
            modifier,
        )
        return
    }
    slot(
        NativeHtmlMediaRequest(
            url = url,
            posterUrl = element.attr("poster"),
            mimeType = source?.attr("type") ?: element.attr("type"),
            audio = element.tag == "audio",
            controls = element.attr("controls") != null,
            loop = element.attr("loop") != null,
            muted = element.attr("muted") != null,
            nodeKey = element.key,
            onError = { message ->
                context.actions.onError?.invoke(
                    HtmlRenderIssue(element.key, "MEDIA_PLAYBACK_FAILED", message),
                )
            },
        ),
        modifier,
    )
}
