package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.yumeyucca.lucide.FileText
import io.github.yumeyucca.lucide.Lucide
import java.io.IOException
import kotlinx.serialization.SerializationException
import moe.yumeyuka.yumehub.data.repository.GithubPageException
import retrofit2.HttpException

/** 分页列表共用的空态尾项，供各 feature 的列表直接复用。 */
fun LazyListScope.collaborationPagingStatus(
    slots: GithubPagingSlots<*>,
    title: String,
    emptyPlaceholder: Boolean = true,
    emptyMessage: String? = null,
    emptyIcon: ImageVector = Lucide.FileText,
    emptyVerticalBias: Float = -0.1f,
) {
    if (slots.isEmptyResult && emptyPlaceholder) {
        item(key = "collaboration-page-empty") {
            GithubEmptyState(
                icon = emptyIcon,
                title = emptyMessage ?: "暂无$title",
                modifier = Modifier.fillParentMaxHeight(),
                verticalBias = emptyVerticalBias,
            )
        }
    }
}

/** 统一的错误文案映射，网络与 GitHub 状态码都归到这里。 */
fun collaborationErrorMessage(error: Throwable): String =
    when (error) {
        is HttpException ->
            when (error.code()) {
                401 -> "会话已失效，请重新登录"
                403,
                429 -> "GitHub 拒绝请求，请检查权限或稍后重试"
                404 -> "找不到该内容，或当前账号无权访问"
                422 -> "GitHub 未接受该操作，请检查内容和当前状态"
                else -> "GitHub 请求失败（HTTP ${error.code()}）"
            }
        is IOException -> "网络连接失败，请检查网络后重试"
        is SerializationException -> "无法解析 GitHub 返回的数据"
        is GithubPageException -> {
            val message = error.message.orEmpty()
            when {
                message.contains("rate limit", ignoreCase = true) -> "GitHub 请求过于频繁，请稍后重试"
                message.contains("Bad credentials", ignoreCase = true) -> "会话已失效，请重新登录"
                else -> "GitHub 请求失败，请稍后重试"
            }
        }
        else -> "操作失败，请稍后重试"
    }
