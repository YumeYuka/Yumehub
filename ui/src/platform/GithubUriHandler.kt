package moe.yumeyuka.yumehub.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** app 提供当前页面的链接动作；ui 和 feature 不持有其他页面的路由类型。 */
val LocalGithubLinkHandler = compositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun rememberGithubUriHandler(): (String) -> Unit {
    val handler = LocalGithubLinkHandler.current
    val external = rememberExternalUriHandler()
    val latestHandler = rememberUpdatedState(handler)
    val latestExternal = rememberUpdatedState(external)
    return remember {
        { url ->
            val open = latestHandler.value ?: latestExternal.value
            open(url)
        }
    }
}
