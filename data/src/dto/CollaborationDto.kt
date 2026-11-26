package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import moe.yumeyuka.yumehub.data.repository.GithubReactionGroup

@Serializable
data class CollaborationItemDto(
    val number: Int,
    val title: String,
    val state: String,
    val body: String? = null,
    val user: UserDto? = null,
    val locked: Boolean = false,
    val draft: Boolean = false,
    val merged: Boolean = false,
    val head: PullRequestHeadDto? = null,
    val baseRefName: String? = null,
    val baseRefOid: String? = null,
    @SerialName("pull_request") val pullRequest: JsonObject? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("merged_at") val mergedAt: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
    val labels: List<CollaborationLabelDto> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    val commitCount: Int? = null,
    val reviewCount: Int? = null,
    @SerialName("changed_files") val changedFiles: Int? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val mergeable: String? = null,
    val viewerCanEdit: Boolean = false,
    val viewerCanClose: Boolean = false,
    val viewerCanChangeBase: Boolean = false,
    val viewerCanModerate: Boolean = false,
    val viewerCanMerge: Boolean = false,
    val mergeCommitAllowed: Boolean = false,
    val squashMergeAllowed: Boolean = false,
    val rebaseMergeAllowed: Boolean = false,
    val reviewDecision: String? = null,
    val mergeStateStatus: String? = null,
    val reviewRequestCount: Int? = null,
    val requestedReviewers: List<String> = emptyList(),
    val requestedReviewerAvatars: List<String?> = emptyList(),
    val viewerLatestReviewState: String? = null,
    val viewerReviewRequested: Boolean = false,
    val commentsCount: Int = 0,
    val participantsCount: Int? = null,
    val repositoryName: String? = null,
    val assignedActors: List<String> = emptyList(),
    val stateReason: String? = null,
    val issueType: String? = null,
    val issueTypeColor: String? = null,
    val subIssuesTotal: Int? = null,
    val subIssuesCompleted: Int? = null,
    val linkedPullRequests: Int? = null,
    val closingIssues: Int? = null,
    val headRefName: String? = null,
    val checkState: String? = null,
    val requiredStatusCheckCount: Int? = null,
    val allStatusChecksPassed: Boolean = false,
    val requiredStatusChecks: List<PullRequestStatusCheckDto> = emptyList(),
    val bodyPreview: String? = null,
    val participantAvatars: List<UserDto> = emptyList(),
    val taskProgress: WorkTaskProgressDto? = null,
    val viewerCanReact: Boolean = false,
    val reactions: List<GithubReactionGroup> = emptyList(),
    val viewerCanTypeIssue: Boolean = false,
    val viewerCanSeeIssueFields: Boolean = false,
)

/** GitHub 的 UNSTABLE 表示检查未通过，但仍可合并。 */
fun CollaborationItemDto.canMerge(): Boolean =
    state == "open" &&
        !draft &&
        !merged &&
        viewerCanMerge &&
        (mergeCommitAllowed || squashMergeAllowed || rebaseMergeAllowed) &&
        mergeable == "MERGEABLE" &&
        (mergeStateStatus == "CLEAN" || mergeStateStatus == "UNSTABLE") &&
        reviewDecision != "REVIEW_REQUIRED" &&
        reviewDecision != "CHANGES_REQUESTED"

@Serializable data class WorkTaskProgressDto(val completed: Int, val total: Int)

enum class WorkProperty {
    Reviewers,
    Assignees,
    Labels,
    Milestone,
    LinkedItems,
    SubIssues,
    Projects,
}

@Serializable
data class WorkPropertyOption(
    val id: String,
    val title: String,
    val editable: Boolean = true,
    val itemId: String? = null,
    val avatarUrl: String? = null,
    val state: String? = null,
    val kind: String? = null,
    val number: Int? = null,
    val subtitle: String? = null,
    val color: String? = null,
)

data class WorkProperties(
    val values: Map<WorkProperty, List<WorkPropertyOption>>,
    val editable: Boolean,
    val labelsEditable: Boolean,
)

data class WorkReviewRequests(
    val requested: List<WorkPropertyOption>,
    val reviewed: List<WorkPropertyOption>,
    val authorLogin: String?,
    val editable: Boolean,
)

@Serializable
data class PullRequestStatusCheckDto(
    val name: String,
    val state: String,
    val description: String? = null,
)

@Serializable
data class CollaborationLabelDto(
    val name: String,
    val color: String? = null,
    val id: String? = null,
)

data class IssueTypeOptionDto(val id: String, val name: String, val color: String? = null)

data class MilestoneOptionDto(val id: String, val title: String)

@Serializable
data class AssigneeOptionDto(val id: String, val login: String, val name: String? = null)

@Serializable
data class WorkCreationDraft(
    val title: String = "",
    val body: String = "",
    val base: String = "",
    val head: String = "",
    val issueTypeId: String? = null,
    val milestoneId: String? = null,
    val labelIds: List<String> = emptyList(),
    val assignees: List<AssigneeOptionDto> = emptyList(),
    val issueTemplate: String? = null,
)

@Serializable
data class DiscussionCreationDraft(
    val title: String = "",
    val body: String = "",
    val categoryId: String? = null,
)

data class IssueTemplateOptionDto(
    val name: String,
    val title: String?,
    val body: String?,
    val labels: List<CollaborationLabelDto>,
    val assignees: List<AssigneeOptionDto>,
    val issueTypeId: String?,
)

@Serializable data class PullRequestHeadDto(val sha: String? = null, val ref: String? = null)

/** 列表筛选器的可选标签与讨论分类；只在前端用于构建搜索条件。 */
data class CollaborationFilterOptions(
    val labels: List<CollaborationLabelDto> = emptyList(),
    val categories: List<String> = emptyList(),
    val discussionCategories: List<DiscussionCategoryDto> = emptyList(),
    val repositoryId: String? = null,
    val milestones: List<String> = emptyList(),
    val issueTypes: List<CollaborationLabelDto> = emptyList(),
    val issueTypeOptions: List<IssueTypeOptionDto> = emptyList(),
    val milestoneOptions: List<MilestoneOptionDto> = emptyList(),
    val issueTemplates: List<IssueTemplateOptionDto> = emptyList(),
)

data class DiscussionCategoryDto(val id: String, val name: String)

/** 讨论列表卡片；正文和回复仍由讨论详情页按需读取。 */
@Serializable
data class DiscussionListItemDto(
    val number: Int,
    val title: String,
    val closed: Boolean,
    val url: String? = null,
    val nodeId: String? = null,
    val author: UserDto? = null,
    val category: String? = null,
    val repository: String? = null,
    val labels: List<CollaborationLabelDto> = emptyList(),
    val commentsCount: Int = 0,
    val commentAuthors: List<UserDto> = emptyList(),
    val bodyPreview: String? = null,
    val authorAssociation: String? = null,
    val upvoteCount: Int = 0,
    val answered: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class CollaborationCommentDto(
    val id: Long,
    val body: String? = null,
    val user: UserDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
    val url: String? = null,
    val isMinimized: Boolean = false,
    val minimizedReason: String? = null,
    val viewerCanMinimize: Boolean = false,
    val viewerCanReact: Boolean = false,
    val reactions: List<GithubReactionGroup> = emptyList(),
)

@Serializable
data class PullRequestReviewDto(
    val id: Long,
    val state: String,
    val body: String? = null,
    val user: UserDto? = null,
    @SerialName("commit_url") val commitUrl: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
)

@Serializable
data class PullRequestFileDto(
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
    @SerialName("previous_filename") val previousFilename: String? = null,
    val structuredDiff: StructuredDiffDto? = null,
)

@Serializable
data class PullRequestChecksResponse(
    @SerialName("check_runs") val checkRuns: List<PullRequestCheckRunDto>,
)

@Serializable
data class PullRequestCheckRunDto(
    val id: String,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val required: Boolean = false,
    val description: String? = null,
    val suiteId: String? = null,
    val appName: String? = null,
    val workflowName: String? = null,
    val runNumber: Int? = null,
    val runUrl: String? = null,
)

@Serializable data class CreateCollaborationCommentRequest(val body: String)

@Serializable
data class CreatePullRequestReviewRequest(
    val event: String,
    @SerialName("commit_id") val commitId: String,
    val body: String? = null,
)

@Serializable
data class PullRequestReviewCommentDto(
    val id: Long,
    val body: String? = null,
    val path: String,
    val line: Int? = null,
    val side: String? = null,
    @SerialName("commit_id") val commitId: String? = null,
    @SerialName("original_commit_id") val originalCommitId: String? = null,
    @SerialName("original_line") val originalLine: Int? = null,
    @SerialName("start_line") val startLine: Int? = null,
    @SerialName("start_side") val startSide: String? = null,
    val user: UserDto? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
)

@Serializable
data class CreatePullRequestReviewCommentRequest(
    val body: String,
    @SerialName("commit_id") val commitId: String,
    val path: String,
    val line: Int,
    val side: String,
)
