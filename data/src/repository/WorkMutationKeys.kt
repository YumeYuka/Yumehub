package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.CollaborationCommentDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.data.api.dto.IssueFieldValue
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.CommandState
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.NullableValue
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.WriteState

class WorkMutationKeys(val target: String) {
    val title = text("title")
    val body = text("body")
    val base = text("base")
    val state = text("state")
    val locked = MutationKey(MutationKind.WORK_FIELD, "$target:locked", Boolean.serializer())
    val merge = MutationKey(MutationKind.MERGE, "$target:merge", CommandAccepted.serializer())
    val issueType =
        MutationKey(
            MutationKind.ISSUE_FIELD,
            "$target:issue-type",
            NullableValue.serializer(WorkPropertyOption.serializer()),
        )
    val fieldPrefix = "$target:issue-field:"

    fun field(id: String) =
        MutationKey(
            MutationKind.ISSUE_FIELD,
            "$fieldPrefix$id",
            NullableValue.serializer(IssueFieldValue.serializer()),
        )

    fun property(value: WorkProperty) =
        MutationKey(MutationKind.WORK_PROPERTY, "$target:property:${value.name}", Options)

    fun comment(id: String) =
        MutationKey(MutationKind.COMMENT, id, CollaborationCommentDto.serializer())

    fun deleteComment(id: String) =
        MutationKey(
            MutationKind.COMMENT,
            "$target:delete-comment:$id",
            CommandAccepted.serializer(),
        )

    private fun text(name: String) =
        MutationKey(MutationKind.WORK_FIELD, "$target:$name", String.serializer())

    companion object {
        private val Options = ListSerializer(WorkPropertyOption.serializer())

        fun forTarget(target: WorkTarget) =
            WorkMutationKeys(
                "${target.kind.name}:${target.owner}/${target.repository}/${target.number}"
                    .lowercase(Locale.ROOT),
            )
    }
}

internal fun CollaborationItemDto.withProperty(
    property: WorkProperty,
    options: List<WorkPropertyOption>,
): CollaborationItemDto =
    when (property) {
        WorkProperty.Labels ->
            copy(labels = options.map { CollaborationLabelDto(it.title, it.color, it.id) })
        WorkProperty.Assignees -> copy(assignedActors = options.map { it.title })
        WorkProperty.Reviewers ->
            copy(
                requestedReviewers = options.map { it.title },
                requestedReviewerAvatars = options.map { it.avatarUrl },
                reviewRequestCount = options.size,
            )
        WorkProperty.SubIssues ->
            copy(
                subIssuesTotal = options.size,
                subIssuesCompleted = options.count { it.state.equals("CLOSED", true) },
            )
        WorkProperty.LinkedItems ->
            if (head == null) copy(linkedPullRequests = options.size)
            else copy(closingIssues = options.size)
        WorkProperty.Projects,
        WorkProperty.Milestone -> this
    }

data class WorkCommentWrite(
    val edit: WriteState<CollaborationCommentDto>,
    val deletion: CommandState<CommandAccepted>,
    val editOperationId: String?,
) {
    val busy: Boolean
        get() = edit.phase == WritePhase.PENDING || deletion.phase == WritePhase.PENDING

    val uncertain: Boolean
        get() = edit.phase == WritePhase.UNCERTAIN || deletion.phase == WritePhase.UNCERTAIN

    fun apply(row: CollaborationCommentDto): CollaborationCommentDto {
        val value = edit.visible ?: return row
        if (
            value.nodeId != row.nodeId ||
                (edit.phase != WritePhase.PENDING &&
                    row.updatedAt != null &&
                    (value.updatedAt ?: "") < row.updatedAt)
        )
            return row
        return row.copy(
            body = value.body,
            bodyHtml = value.bodyHtml,
            isMinimized = value.isMinimized,
            minimizedReason = value.minimizedReason,
            updatedAt = value.updatedAt ?: row.updatedAt,
        )
    }
}

/** Only presentation values. This type never goes into a paging snapshot or a response cache. */
data class WorkRowProjection(
    val confirmed: CollaborationItemDto? = null,
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,
    val labels: List<WorkPropertyOption>? = null,
    val subIssues: List<WorkPropertyOption>? = null,
    val mergePhase: WritePhase = WritePhase.IDLE,
    val merged: Boolean = false,
) {
    fun apply(row: CollaborationItemDto): CollaborationItemDto {
        val detail = confirmed?.takeIf {
            it.nodeId == row.nodeId &&
                (row.updatedAt == null || (it.updatedAt ?: "") >= row.updatedAt)
        }
        if (
            detail == null &&
                title == null &&
                body == null &&
                state == null &&
                labels == null &&
                subIssues == null &&
                !merged
        )
            return row
        val isMerged = row.merged || merged || detail?.merged == true
        val nextBody = body ?: detail?.body
        var projected =
            row.copy(
                title = title ?: detail?.title ?: row.title,
                state = if (isMerged) "closed" else state ?: detail?.state ?: row.state,
                merged = isMerged,
                labels = detail?.labels ?: row.labels,
                bodyPreview = nextBody?.take(240) ?: row.bodyPreview,
                subIssuesTotal = detail?.subIssuesTotal ?: row.subIssuesTotal,
                subIssuesCompleted = detail?.subIssuesCompleted ?: row.subIssuesCompleted,
            )
        labels?.let { projected = projected.withProperty(WorkProperty.Labels, it) }
        subIssues?.let { projected = projected.withProperty(WorkProperty.SubIssues, it) }
        return projected
    }
}

private val <T : Any> WriteState<T>.inFlightValue: T?
    get() = if (phase == WritePhase.PENDING) visible else null

internal fun workRowProjection(
    cache: CachedDataRepository,
    account: String,
    target: WorkTarget,
    base: Flow<CollaborationItemDto?>,
): Flow<WorkRowProjection> {
    val coord = cache.mutations(account)
    val keys = WorkMutationKeys.forTarget(target)
    val fields =
        combine(
            coord.watch(keys.title),
            coord.watch(keys.body),
            coord.watch(keys.state),
            coord.watch(keys.property(WorkProperty.Labels)),
            if (target.kind == CollaborationKind.Issue)
                coord.watch(keys.property(WorkProperty.SubIssues))
            else flowOf(WriteState()),
        ) { title, body, state, labels, subs ->
            WorkRowProjection(
                title = title.inFlightValue,
                body = body.inFlightValue,
                state = state.inFlightValue,
                labels = labels.inFlightValue,
                subIssues = subs.inFlightValue,
            )
        }
    val merge =
        if (target.kind == CollaborationKind.PullRequest) coord.watchCommand(keys.merge)
        else flowOf(CommandState())
    return combine(fields, merge, base) { row, command, item ->
        row.copy(confirmed = item, mergePhase = command.phase, merged = command.result != null)
    }
}
