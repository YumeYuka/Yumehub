package moe.yumeyuka.yumehub.feature.workspaces

import moe.yumeyuka.yumehub.core.model.GithubRepositoryLocation
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute

/** 仓库代码浏览页；目录切换只改页面内部状态，不新增路由。 */
data class WorkspacesRoute(
    val owner: String,
    val name: String,
    val ref: String? = null,
    val path: String = "",
    val file: Boolean = false,
    val anchor: String? = null,
    val gitObject: GithubRepositoryLocation.GitObject? = null,
    val linkUrl: String? = null,
    val license: Boolean = false,
) : GithubRoute

/** 当前分支的提交历史页面。 */
data class CommitHistoryRoute(
    val owner: String,
    val name: String,
    val ref: String,
) : GithubRoute
