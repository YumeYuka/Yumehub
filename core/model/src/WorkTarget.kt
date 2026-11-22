package moe.yumeyuka.yumehub.core.model

/** 已校验的 issue / pull request 定位信息。 */
data class WorkTarget(
    val owner: String,
    val repository: String,
    val number: Int,
    val kind: CollaborationKind,
)
