package moe.yumeyuka.yumehub.data.repository

import moe.yumeyuka.yumehub.data.api.dto.CollaborationCommentDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewDto

public sealed interface WorkTimelineEntry {
    val key: String

    data class Comment(val value: CollaborationCommentDto) : WorkTimelineEntry {
        override val key: String = "comment-${value.nodeId ?: value.id}"
    }

    data class Review(val value: PullRequestReviewDto) : WorkTimelineEntry {
        override val key: String = "review-${value.nodeId ?: value.id}"
    }

    data class Commit(val value: RepositoryActivityEntry) : WorkTimelineEntry {
        override val key: String = "commit-${value.id}"
    }

    data class Event(
        val id: String,
        val kind: WorkTimelineEventKind,
        val actor: String?,
        val avatarUrl: String?,
        val timestamp: String?,
        val subject: String? = null,
    ) : WorkTimelineEntry {
        override val key: String = "event-$id"
    }
}

public enum class WorkTimelineEventKind {
    RenamedTitle,
    Closed,
    Reopened,
    Merged,
    Labeled,
    Unlabeled,
    Assigned,
    Unassigned,
    Locked,
    Unlocked,
    Milestoned,
    Demilestoned,
    CrossReferenced,
    Referenced,
    HeadRefDeleted,
    HeadRefRestored,
    ReviewRequested,
    ReviewRequestRemoved,
    ReviewDismissed,
    ReadyForReview,
    ConvertedToDraft,
    CommentDeleted,
    Deployed,
    HeadRefForcePushed,
    BaseRefForcePushed,
    BaseRefChanged,
    MarkedAsDuplicate,
    DeploymentEnvironmentChanged,
    AutoMergeEnabled,
    AutoMergeDisabled,
    AutoRebaseEnabled,
    AutoSquashEnabled,
    UserBlocked,
    AddedToMergeQueue,
    RemovedFromMergeQueue,
    Connected,
    Disconnected,
    AutomaticBaseChanged,
    AddedToProject,
    MovedColumnsInProject,
    RemovedFromProject,
    Pinned,
    Unpinned,
    Transferred,
    ConvertedToDiscussion,
    SubIssueAdded,
    SubIssueRemoved,
    CopilotWorkStarted,
    CopilotWorkFinished,
    CopilotWorkFinishedFailure,
}
