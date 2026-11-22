package moe.yumeyuka.yumehub.core.model

/** 「我的工作」页的过滤项，由 [CollaborationKind] 限定可用范围。 */
enum class WorkFilter(val kind: CollaborationKind, val title: String) {
    AssignedIssues(CollaborationKind.Issue, "分配给我"),
    CreatedIssues(CollaborationKind.Issue, "我创建的"),
    CreatedPullRequests(CollaborationKind.PullRequest, "我创建的"),
    ReviewRequested(CollaborationKind.PullRequest, "待我评审"),
}
