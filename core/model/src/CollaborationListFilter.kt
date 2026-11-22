package moe.yumeyuka.yumehub.core.model

/** 仓库协作列表的状态筛选；[All] 只用于查看完整历史。 */
enum class CollaborationStateFilter {
  Open,
  Closed,
  All,
}

/** 列表范围筛选。不同协作类型只展示自身支持的范围。 */
enum class CollaborationScopeFilter {
  All,
  CreatedByMe,
  AssignedToMe,
  ReviewRequested,
  Commented,
  Mentioned,
  Involved,
}

/** 列表排序；映射到 GitHub 搜索的 `sort:*` 限定符。 */
enum class CollaborationSortOrder {
  Updated,
  Created,
  Comments,
}

/** PR 搜索的评审状态限定符。 */
enum class CollaborationReviewFilter {
  Any,
  NoReviews,
  Required,
  Approved,
  ChangesRequested,
  ReviewedByMe,
  NotReviewedByMe,
  AwaitingMyTeamReview,
  AwaitingMyReview,
}

/** 议题、拉取请求和讨论列表共用的筛选条件。 */
data class CollaborationListFilter(
    val state: CollaborationStateFilter = CollaborationStateFilter.Open,
    val scope: CollaborationScopeFilter = CollaborationScopeFilter.All,
    val sort: CollaborationSortOrder = CollaborationSortOrder.Updated,
    val searchQuery: String = "",
    val draftOnly: Boolean = false,
    val unansweredOnly: Boolean = false,
    val label: String? = null,
    val category: String? = null,
    val milestone: String? = null,
    val issueType: String? = null,
    val review: CollaborationReviewFilter = CollaborationReviewFilter.Any,
    val unlabeledOnly: Boolean = false,
    val unassignedOnly: Boolean = false,
)
