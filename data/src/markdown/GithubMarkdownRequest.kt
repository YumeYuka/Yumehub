package moe.yumeyuka.yumehub.data.markdown

import kotlinx.serialization.Serializable

/** 官方 POST /markdown 的输入；context 只能来自已验证的 github.com 仓库 URL */
@Serializable
data class GithubMarkdownRequest(
    val text: String,
    val mode: String,
    val context: String? = null,
)
