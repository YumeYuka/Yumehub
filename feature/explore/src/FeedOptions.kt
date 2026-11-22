package moe.yumeyuka.yumehub.feature.explore

import moe.yumeyuka.yumehub.data.api.dto.FeedEventDto

internal data class FeedOptions(val filters: Set<FeedFilter>, val sort: FeedSort)

/**
 * 探索动态只保留六类高信号活动。
 *
 * 对应官方 Dashboard Feed 的讨论、发布、星标、仓库、关注和推荐事件。
 */
internal enum class FeedFilter(val serverGroup: String) {
    Announcements("ANNOUNCEMENTS"),
    Releases("RELEASES"),
    Stars("STARS"),
    Repositories("REPOSITORIES"),
    Follows("FOLLOWS"),
    Suggestions("RECOMMENDATIONS"),
}

internal enum class FeedSort {
    Newest,
    Oldest,
}

internal fun FeedFilter.matches(event: FeedEventDto): Boolean =
    when (this) {
        FeedFilter.Announcements ->
            event.type == "DiscussionEvent" ||
                event.type == "PublicEvent" ||
                event.type == "MemberEvent"
        FeedFilter.Releases -> event.type == "ReleaseEvent"
        FeedFilter.Stars -> event.type == "WatchEvent"
        FeedFilter.Repositories ->
            event.type == "ForkEvent" ||
                (event.type == "CreateEvent" &&
                    event.payload.refType != "branch" &&
                    event.payload.refType != "tag")
        FeedFilter.Follows -> event.type == "FollowEvent"
        FeedFilter.Suggestions ->
            event.type == "RepositoryRecommendationEvent" ||
                event.type == "FollowRecommendationEvent" ||
                event.type == "PullRequestEvent" ||
                event.type == "IssuesEvent"
    }

/** 推送、分支/标签创建删除、评论和评审等低信号活动不属于任何分类，因此不会进入列表。 */
internal fun FeedEventDto.matches(filters: Set<FeedFilter>): Boolean = filters.any {
    it.matches(this)
}
