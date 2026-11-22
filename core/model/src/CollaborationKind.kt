package moe.yumeyuka.yumehub.core.model

/** Issue / pull request 的两种协作条目类型，跨 feature 共享。 */
enum class CollaborationKind(val title: String) {
    Issue("议题"),
    PullRequest("拉取请求"),
}
