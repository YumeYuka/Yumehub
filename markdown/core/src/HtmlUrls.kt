package moe.yumeyuka.markdown.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal fun decodeHtmlAnchor(raw: String): String = runCatching {
    URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
}
    .getOrDefault(raw)

internal class HtmlUrlResolver(private val options: HtmlParseOptions) {
    private val repository = parseHttps(options.repositoryRootUrl)
    private val page = parseHttps(options.pageUrl)
    private val linkBase = page?.let(::directoryAwareBase) ?: repository?.let(::directoryBase)
    private val mediaBase =
        if (
            options.readme &&
                repository?.host.equals("github.com", true) &&
                (page == null || page.toString().trimEnd('/') == repository.toString().trimEnd('/'))
        ) {
            val parts = repository?.path.orEmpty().trim('/').split('/')
            if (parts.size == 2 && parts.all(String::isNotBlank)) {
                val ref = options.ref?.takeIf(String::isNotBlank) ?: "HEAD"
                URI("https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/${segment(ref)}/")
            } else linkBase
        } else linkBase

    fun link(raw: String, allowMailto: Boolean = false): String? =
        raw.trim().takeIf { allowMailto && isSafeHtmlMailto(it) }
            ?: resolve(raw, linkBase, allowAnchor = true)

    fun media(
        raw: String,
        image: Boolean = false,
        trimBoundaryWhitespace: Boolean = true,
    ): String? {
        val value =
            if (mediaBase != linkBase && raw.startsWith('/') && !raw.startsWith("//")) {
                raw.drop(1)
            } else raw
        val resolved =
            resolve(
                value,
                mediaBase,
                allowAnchor = false,
                trimBoundaryWhitespace = trimBoundaryWhitespace,
            ) ?: return null
        val uri = URI(resolved)
        val rawUrl =
            if (uri.host.equals("github.com", true) && "/blob/" in uri.rawPath) {
                resolved.replaceFirst("/blob/", "/raw/")
            } else resolved
        return if (image) unescapeRawImage(rawUrl) else rawUrl
    }

    fun imageFallback(url: String): String? {
        val uri = parseHttps(url) ?: return null
        if (!uri.host.equals("raw.githubusercontent.com", true)) return null
        return "https://media.githubusercontent.com/media${uri.rawPath}" +
            uri.rawQuery?.let { "?$it" }.orEmpty() +
            uri.rawFragment?.let { "#$it" }.orEmpty()
    }

    fun srcset(raw: String): List<HtmlMediaCandidate> {
        val candidates = mutableListOf<HtmlMediaCandidate>()
        var position = 0
        while (position < raw.length) {
            while (
                position < raw.length && (raw[position].isSrcsetSpace() || raw[position] == ',')
            ) {
                position++
            }
            val start = position
            // URL 可以含逗号，只有 URL 尾部或描述符之后的逗号才分隔候选。
            while (position < raw.length && !raw[position].isSrcsetSpace()) position++
            var url = raw.substring(start, position)
            val descriptors = mutableListOf<String>()
            if (url.endsWith(',')) {
                url = url.trimEnd(',')
            } else {
                val descriptor = StringBuilder()
                var inParens = false
                while (position < raw.length) {
                    val character = raw[position++]
                    when {
                        character == ',' && !inParens -> break
                        character.isSrcsetSpace() && !inParens -> {
                            if (descriptor.isNotEmpty()) {
                                descriptors += descriptor.toString()
                                descriptor.clear()
                            }
                        }
                        else -> {
                            descriptor.append(character)
                            if (character == '(') inParens = true
                            if (character == ')') inParens = false
                        }
                    }
                }
                if (descriptor.isNotEmpty()) descriptors += descriptor.toString()
            }
            val descriptor = parseImageDescriptor(descriptors) ?: continue
            // URL 已按 ASCII 空白分词，尾部 NBSP 等字符仍是地址的一部分。
            val normalized = media(url, image = true, trimBoundaryWhitespace = false) ?: continue
            candidates += HtmlMediaCandidate(normalized, imageFallback(normalized), descriptor)
        }
        return freeze(candidates)
    }

    private fun directoryAwareBase(uri: URI): URI {
        val parts = uri.path.orEmpty().trim('/').split('/').filter(String::isNotEmpty)
        return if (
            uri.toString().trimEnd('/') == repository?.toString()?.trimEnd('/') ||
                (uri.host.equals("github.com", true) &&
                    (parts.size == 2 || parts.getOrNull(2) == "tree"))
        ) {
            directoryBase(uri)
        } else uri
    }

    private fun unescapeRawImage(value: String): String {
        val original = parseHttps(value) ?: return value
        if (!original.host.equals("raw.githubusercontent.com", true)) return value
        // 仅修复被转义进路径的查询串，保留 ref 和文件名中有意义的转义斜杠。
        if (!original.rawPath.contains("%3f", ignoreCase = true)) return value
        val path = decodeHtmlAnchor(original.rawPath)
        if (path.startsWith("//") || '\\' in path) return value
        val decoded =
            runCatching { URI("https://raw.githubusercontent.com").resolve(escapedSpaces(path)) }
                .getOrNull() ?: return value
        if (!decoded.host.equals("raw.githubusercontent.com", true)) return value
        val queries = listOfNotNull(decoded.rawQuery, original.rawQuery).filter(String::isNotEmpty)
        return "https://raw.githubusercontent.com${decoded.rawPath}" +
            queries.takeIf(List<String>::isNotEmpty)?.joinToString("&", "?").orEmpty() +
            (original.rawFragment ?: decoded.rawFragment)?.let { "#$it" }.orEmpty()
    }
}

/** 邮箱仅作为用户点击的链接保留，不允许作为图片、媒体或自动请求地址。 */
fun isSafeHtmlMailto(value: String): Boolean = runCatching {
    if (value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f || it == '\\' }) {
        return@runCatching false
    }
    val uri = URI(value)
    val address = uri.rawSchemeSpecificPart?.substringBefore('?').orEmpty()
    uri.scheme.equals("mailto", true) &&
        uri.isOpaque &&
        uri.rawFragment == null &&
        address.contains('@') &&
        !address.startsWith("//") &&
        !value.contains(Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE))
}
    .getOrDefault(false)

private fun resolve(
    raw: String,
    base: URI?,
    allowAnchor: Boolean,
    trimBoundaryWhitespace: Boolean = true,
): String? = runCatching {
    val value = if (trimBoundaryWhitespace) raw.trim() else escapedSrcsetWhitespace(raw)
    if (value.isEmpty() || value.any { it.code < 0x20 || it.code == 0x7f } || '\\' in value) {
        return@runCatching null
    }
    if (allowAnchor && value.startsWith('#')) return@runCatching value
    val input = URI(escapedSpaces(value))
    val target =
        when {
            input.isAbsolute -> input
            base == null -> return@runCatching null
            input.rawPath.isNullOrEmpty() && input.rawQuery != null ->
                URI(
                    base.toString().substringBefore('#').substringBefore('?') +
                        "?${input.rawQuery}" +
                        input.rawFragment?.let { "#$it" }.orEmpty(),
                )
            else -> base.resolve(input)
        }
    target.takeIf(::isAllowedHttps)?.toASCIIString()
}
    .getOrNull()

private fun parseHttps(value: String?): URI? =
    value?.let { runCatching { URI(escapedSpaces(it)) }.getOrNull() }?.takeIf(::isAllowedHttps)

private fun isAllowedHttps(uri: URI): Boolean =
    uri.scheme.equals("https", true) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        !uri.host.equals("appassets.androidplatform.net", true)

private fun directoryBase(uri: URI): URI =
    URI(
        uri.toString().substringBefore('#').substringBefore('?').trimEnd('/') +
            "/" +
            uri.rawQuery?.let { "?$it" }.orEmpty(),
    )

private fun segment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun escapedSpaces(value: String): String = value.replace(" ", "%20")

/** srcset 只把 ASCII 空白当分隔符；URI 构造器拒绝的其他空白必须作为 URL 内容编码。 */
private fun escapedSrcsetWhitespace(value: String): String = buildString {
    value.forEach { character ->
        if (character.code > 0x7f && character.isWhitespace()) {
            append(URLEncoder.encode(character.toString(), StandardCharsets.UTF_8))
        } else append(character)
    }
}

private fun Char.isSrcsetSpace(): Boolean = this in " \t\n\r\u000c"

private fun parseImageDescriptor(descriptors: List<String>): HtmlMediaDescriptor? {
    if (descriptors.isEmpty()) return HtmlMediaDescriptor.Density(1.0)
    if (descriptors.size != 1) return null
    val descriptor = descriptors.single()
    val value = descriptor.dropLast(1)
    return when {
        descriptor.endsWith('w') && value.matches(SrcsetWidth) ->
            value.toIntOrNull()?.takeIf { it > 0 }?.let { HtmlMediaDescriptor.Width(it) }
        descriptor.endsWith('x') && value.matches(SrcsetDensity) ->
            value
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0 }
                ?.let { HtmlMediaDescriptor.Density(it) }
        else -> null
    }
}

private val SrcsetWidth = Regex("[0-9]+")
private val SrcsetDensity = Regex("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
