package moe.yumeyuka.yumehub.data.markdown

import java.net.URI

internal data class MarkdownRepositoryUrl(
    val owner: String,
    val name: String,
    val workType: String? = null,
    val workNumber: Int? = null,
) {
    val context: String
        get() = "$owner/$name"
}

/** 只解码 URL 路径一次；分支名不从 URL 猜测或重新编码，直接使用调用方传入的 ref */
internal fun parseMarkdownRepositoryUrl(value: String?): MarkdownRepositoryUrl? {
    if (value == null) return null
    val uri =
        try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
    if (
        !uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port != 443)
    )
        return null
    val segments = uri.rawPath.orEmpty().removePrefix("/").trimEnd('/').split('/')
    if (segments.size < 2) return null
    val owner = decodeSegment(segments[0]) ?: return null
    val name = decodeSegment(segments[1]) ?: return null
    if (!owner.matches(Regex("[A-Za-z0-9-]+"))) return null
    if (!name.matches(Regex("[A-Za-z0-9_.-]+")) || name == "." || name == "..") return null
    // 评论锚点不能被误认为 Issue / PR 正文；应由调用方提供评论 nodeId
    val workType =
        segments.getOrNull(2)?.takeIf {
            segments.size == 4 &&
                (it == "issues" || it == "pull") &&
                uri.rawFragment.isNullOrEmpty()
        }
    val number = if (workType != null) segments[3].toIntOrNull()?.takeIf { it > 0 } else null
    return MarkdownRepositoryUrl(owner, name, workType.takeIf { number != null }, number)
}

private fun decodeSegment(value: String): String? =
    try {
        URI("https://github.com/$value").path.removePrefix("/")
    } catch (_: Exception) {
        null
    }
