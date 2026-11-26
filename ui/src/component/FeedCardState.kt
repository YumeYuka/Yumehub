package moe.yumeyuka.yumehub.ui.component

import moe.yumeyuka.yumehub.data.api.dto.*

data class FeedCardState(
    val subjectId: String? = null,
    val repository: RepositoryDto? = null,
    val user: UserDto? = null,
    val work: FeedWorkDto? = null,
    val contributorsCount: Int? = null,
    val selected: Boolean? = null,
    val busy: Boolean = false,
    val phase: moe.yumeyuka.yumehub.data.cache.WritePhase =
        moe.yumeyuka.yumehub.data.cache.WritePhase.IDLE,
    val actionError: String? = null,
)

fun FeedEventDto.initialCard(): FeedCardState? {
    val user =
        payload.target?.takeIf { type == "FollowEvent" || type == "FollowRecommendationEvent" }
    if (repositoryDetails == null && user == null) return null
    return FeedCardState(
        subjectId = subjectId,
        repository = repositoryDetails,
        user = user,
        work = workItem(),
        selected = viewerSelected,
        contributorsCount = contributorsCount,
    )
}
