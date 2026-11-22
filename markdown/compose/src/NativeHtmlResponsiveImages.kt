package moe.yumeyuka.markdown.compose

import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlMedia
import moe.yumeyuka.markdown.core.HtmlMediaCandidate
import moe.yumeyuka.markdown.core.HtmlMediaDescriptor

/** URL 已由 core 解析；这里仅选择候选，并保留解码像素到 CSS 自然尺寸的密度。 */
internal data class NativeHtmlImageSource(
    val url: String,
    val fallbackUrl: String?,
    val density: Double = 1.0,
)

internal fun selectNativeHtmlImageSource(
    image: HtmlElement,
    picture: HtmlElement?,
    dark: Boolean,
    pixelRatio: Float,
    viewportWidthCssPx: Float,
): NativeHtmlImageSource? {
    val viewport = viewportWidthCssPx.takeIf { it.isFinite() && it > 0f } ?: 1f
    val source =
        picture
            ?.children
            ?.filterIsInstance<HtmlElement>()
            ?.takeWhile { it.key != image.key }
            ?.firstOrNull {
                it.tag == "source" &&
                    it.semantic.media?.candidates?.isNotEmpty() == true &&
                    supportedImageType(it.attr("type")) &&
                    imageMediaMatches(it.attr("media"), dark, viewport)
            }
    val owner = source ?: image
    val media = owner.semantic.media ?: return null
    val sourceWidth = imageSourceWidth(owner.attr("sizes"), dark, viewport)
    return selectNativeHtmlImageCandidate(
        media,
        includeSource = source == null && !image.attr("src").isNullOrBlank(),
        pixelRatio = pixelRatio.toDouble(),
        sourceWidthCssPx = sourceWidth.toDouble(),
    )
}

internal fun selectNativeHtmlImageCandidate(
    media: HtmlMedia,
    includeSource: Boolean,
    pixelRatio: Double,
    sourceWidthCssPx: Double,
): NativeHtmlImageSource {
    val candidates = media.candidates.toMutableList()
    val hasWidth = candidates.any { it.descriptor is HtmlMediaDescriptor.Width }
    if (
        includeSource &&
            !hasWidth &&
            candidates.none { (it.descriptor as? HtmlMediaDescriptor.Density)?.value == 1.0 }
    ) {
        candidates += HtmlMediaCandidate(media.url, media.fallbackUrl)
    }
    val sourceWidth = sourceWidthCssPx.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val normalized = candidates.mapNotNull { candidate ->
        val density =
            when (val descriptor = candidate.descriptor) {
                is HtmlMediaDescriptor.Density -> descriptor.value
                is HtmlMediaDescriptor.Width -> descriptor.value / sourceWidth
            }
        density
            .takeIf { it.isFinite() && it > 0.0 }
            ?.let { NativeHtmlImageSource(candidate.url, candidate.fallbackUrl, it) }
    }
    val target = pixelRatio.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    // 同密度保留原顺序，避免重复描述符使选图随排序实现变化。
    return normalized.filter { it.density >= target }.minByOrNull { it.density }
        ?: normalized.maxByOrNull { it.density }
        ?: NativeHtmlImageSource(media.url, media.fallbackUrl)
}

/** GitHub 图片的亮暗查询，以及常用 screen/宽度条件；未知特性按不匹配处理。 */
internal fun imageMediaMatches(raw: String?, dark: Boolean, viewport: Float): Boolean {
    if (raw.isNullOrBlank()) return true
    return raw.split(',').any { clause ->
        var query = clause.trim().lowercase()
        val invert = query.startsWith("not ")
        if (invert) query = query.removePrefix("not ").trim()
        query = query.removePrefix("only ").trim()
        val parts = query.split(Regex("\\s+and\\s+"))
        val matches = parts.all { part ->
            val condition = part.trim()
            if (!condition.startsWith('(') || !condition.endsWith(')')) {
                condition == "screen" || condition == "all"
            } else {
                val pair = condition.substring(1, condition.length - 1).split(':', limit = 2)
                if (pair.size != 2) false
                else
                    when (pair[0].trim()) {
                        "prefers-color-scheme" ->
                            when (pair[1].trim()) {
                                "dark" -> dark
                                "light" -> !dark
                                else -> false
                            }
                        "min-width" ->
                            imageCssLength(pair[1], viewport)?.let { viewport >= it } ?: false
                        "max-width" ->
                            imageCssLength(pair[1], viewport)?.let { viewport <= it } ?: false
                        "width" ->
                            imageCssLength(pair[1], viewport)?.let { viewport == it } ?: false
                        else -> false
                    }
            }
        }
        if (invert) !matches else matches
    }
}

private fun imageSourceWidth(sizes: String?, dark: Boolean, viewport: Float): Float {
    if (sizes.isNullOrBlank()) return viewport
    for (raw in sizes.split(',')) {
        val part = raw.trim()
        val boundary = part.lastIndexOf(')')
        val media = if (boundary >= 0) part.substring(0, boundary + 1) else null
        val length = if (boundary >= 0) part.substring(boundary + 1) else part
        val width = imageCssLength(length, viewport) ?: continue
        if (width > 0f && imageMediaMatches(media, dark, viewport)) return width
    }
    return viewport
}

private fun imageCssLength(raw: String, viewport: Float): Float? {
    val value = raw.trim().lowercase()
    val factor =
        when {
            value.endsWith("px") -> 1f
            value.endsWith("vw") -> viewport / 100f
            else -> return null
        }
    val amount = value.dropLast(2).toFloatOrNull() ?: return null
    return (amount * factor).takeIf { it.isFinite() && it >= 0f }
}

private fun supportedImageType(raw: String?): Boolean =
    raw.isNullOrBlank() || raw.substringBefore(';').trim().lowercase() in ImageTypes

private val ImageTypes =
    setOf(
        "image/png",
        "image/apng",
        "image/jpeg",
        "image/webp",
        "image/gif",
        "image/bmp",
        "image/avif",
        "image/svg+xml",
        "image/x-icon",
        "image/vnd.microsoft.icon",
    )
