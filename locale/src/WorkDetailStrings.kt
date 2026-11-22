package moe.yumeyuka.yumehub.locale

public data class GithubStrings_WorkDetail(
    val LoadFailed: String,
    val RecoveryTitle: String,
    val SubmissionUncertain: String,
    val RecoveryMessage: String,
    val Acknowledged: String,
    val NoDescription: String,
    val NoBody: String,
    val NoChecksCommit: String,
    val CommitStatuses: String,
    val PreviousChecks: String,
    val RevisionChanged: String,
    val CommentPosted: String,
    val LineNumber: (Int) -> String,
    val CheckStatus: (String) -> String,
    val CheckConclusion: (String?) -> String?,
    val ReviewState: (String) -> String,
    val MarkDone: String,
    val Done: String,
    val ConfirmDone: String,
    val ConfirmDoneMessage: String,
    val Subscribe: String,
    val Unsubscribe: String,
    val SubscriptionFailed: String,
    val NotificationUnavailable: String,
    val Discussion: String,
    val Activity: String,
    val Changes: String,
    val TimelineEvent: (String, String?, String?) -> String,
    val TimelineTitleChange: (currentTitle: String?) -> Pair<String, String>,
    val TimelineForcePush:
        (baseBranch: Boolean, branch: String?, before: String?, after: String?) -> String,
    val Created: (String) -> String,
    val CommitCount: (Int) -> String,
    val ReviewCount: (Int) -> String,
    val FileCount: (Int) -> String,
    val MergeRequirements: String,
    val MergePullRequest: String,
    val MergeMessageLoadFailed: String,
    val Merge: String,
    val MergeCommit: String,
    val SquashMerge: String,
    val RebaseMerge: String,
    val MergeStatus: (String) -> String,
    val ReviewDecision: (String) -> String,
    val ReviewRequests: (Int) -> String,
    val LatestReview: (String?, String) -> String,
    val Required: String,
    val RequiredCheckSummary: (String, String) -> String,
    val RequiredChecks: (Int) -> String,
    val Reviews: String,
    val Commits: String,
    val Files: String,
    val Checks: String,
    val ChecksProgress: (Int, Int) -> String,
    val ChecksPassed: (Int, Int) -> String,
    val TaskProgress: (Int, Int) -> String,
    val LabelCount: (Int) -> String,
    val Comment: String,
    val CommentActions: String,
    val EditComment: String,
    val CopyLink: String,
    val CopyMarkdown: String,
    val QuoteReply: String,
    val ReferenceInNewIssue: String,
    val DeleteComment: String,
    val DeleteCommentTitle: String,
    val DeleteCommentMessage: String,
    val HideComment: String,
    val HiddenComment: String,
    val HideReasonOffTopic: String,
    val HideReasonOutdated: String,
    val HideReasonResolved: String,
    val HideReasonSpam: String,
    val HideReasonAbuse: String,
    val LineComment: String,
    val WriteComment: String,
    val Publish: String,
    val SaveChanges: String,
    val SubmitReview: String,
    val ReviewResult: String,
    val Approve: String,
    val RequestChanges: String,
    val Information: String,
    val EditTitle: String,
    val EditDescription: String,
    val ChangeBaseBranch: String,
    val LockConversation: String,
    val UnlockConversation: String,
    val ClosePullRequest: String,
    val CloseIssue: String,
    val ConfirmClosePullRequest: String,
    val ConfirmCloseIssue: String,
    val ConfirmLockConversation: String,
    val RequestReview: String,
    val ReviewResults: String,
    val MyReview: String,
    val NoReviewRequests: String,
    val NoReviewResults: String,
    val Properties: String,
    val Assignees: String,
    val Labels: String,
    val Milestone: String,
    val LinkedItems: String,
    val SubIssues: String,
    val Projects: String,
    val EditProperty: String,
    val Unassigned: String,
    val NoLabels: String,
    val NoMilestone: String,
    val NoLinkedItems: String,
    val NoSubIssues: String,
    val NoProjects: String,
    val PropertyLoadFailed: String,
    val PropertySaveFailed: String,
    val ContinueAdding: String,
    val PropertySearch: String,
    val NoPropertyOptions: String,
    val RequestedReviewers: String,
    val Author: String,
    val Status: String,
    val Locked: String,
    val IssueTitle: (Int) -> String,
    val PullRequestTitle: (Int) -> String,
    val LineContext: (String, Int, Boolean) -> String,
)

internal val ZhHansWorkDetailStrings: GithubStrings_WorkDetail =
    GithubStrings_WorkDetail(
        LoadFailed = "详情加载失败",
        RecoveryTitle = "核对提交结果",
        SubmissionUncertain = "提交结果尚未确认",
        RecoveryMessage = "请刷新或在 GitHub 核对，确认后再继续发布，草稿已保留",
        Acknowledged = "已核对",
        NoDescription = "暂无描述",
        NoBody = "暂无正文",
        NoChecksCommit = "GitHub 未提供此拉取请求的提交，暂时无法查看检查",
        CommitStatuses = "提交状态",
        PreviousChecks = "加载之前的检查",
        RevisionChanged = "拉取请求已更新，点击重新加载",
        CommentPosted = "评论已发布",
        LineNumber = { "第 $it 行" },
        CheckStatus = { value ->
            when (value) {
                "queued" -> "排队中"
                "in_progress" -> "进行中"
                "completed" -> "已完成"
                "waiting" -> "等待中"
                "requested" -> "已请求"
                "pending" -> "待处理"
                "expected" -> "等待中"
                "error" -> "错误"
                "success" -> "成功"
                "failure" -> "失败"
                else -> value
            }
        },
        CheckConclusion = { value ->
            when (value) {
                "success" -> "成功"
                "failure" -> "失败"
                "cancelled" -> "已取消"
                "timed_out" -> "超时"
                "action_required" -> "需要操作"
                "neutral" -> "中性"
                "skipped" -> "已跳过"
                "stale" -> "已过期"
                "startup_failure" -> "启动失败"
                else -> value
            }
        },
        ReviewState = { value ->
            when (value) {
                "APPROVED" -> "已批准"
                "CHANGES_REQUESTED" -> "请求修改"
                "COMMENTED" -> "评论"
                "DISMISSED" -> "已撤销"
                "PENDING" -> "待提交"
                else -> value
            }
        },
        MarkDone = "完成通知",
        Done = "通知已完成",
        ConfirmDone = "完成此通知？",
        ConfirmDoneMessage = "仅将通知标为完成，不会关闭议题或关闭、合并拉取请求。",
        Subscribe = "订阅此工作项",
        Unsubscribe = "取消订阅此工作项",
        SubscriptionFailed = "订阅状态加载失败，点击重试",
        NotificationUnavailable = "未能定位待完成的通知，请从通知列表打开后重试",
        Discussion = "讨论",
        Activity = "动态",
        Changes = "更改",
        TimelineEvent = { kind, subject, detail ->
            val eventSubject = subject?.takeIf { it.isNotBlank() }
            val eventDetail = detail?.takeIf { it.isNotBlank() }
            when (kind) {
                "Closed" -> "关闭了此工作项"
                "Reopened" -> "重新打开了此工作项"
                "RenamedTitle" ->
                    eventDetail?.let { "将标题改为「$it」" } ?: "重命名了标题"
                "Merged" ->
                    when {
                        eventSubject != null && eventDetail != null ->
                            "已将提交 $eventDetail 合并到 $eventSubject"
                        eventSubject != null -> "合并到 $eventSubject"
                        else -> "合并了此拉取请求"
                    }
                "Labeled" -> eventSubject?.let { "添加标签：$it" } ?: "添加了标签"
                "Unlabeled" -> eventSubject?.let { "移除标签：$it" } ?: "移除了标签"
                "Assigned" -> eventSubject?.let { "指派给 $it" } ?: "指派了负责人"
                "Unassigned" -> eventSubject?.let { "取消指派 $it" } ?: "取消了指派"
                "Locked" -> "锁定了对话"
                "Unlocked" -> "解锁了对话"
                "Milestoned" -> eventSubject?.let { "设置里程碑 $it" } ?: "设置了里程碑"
                "Demilestoned" -> eventSubject?.let { "移除里程碑 $it" } ?: "移除了里程碑"
                "CrossReferenced" -> eventSubject?.let { "引用了 $it" } ?: "引用了此工作项"
                "Referenced" -> eventSubject?.let { "引用了 $it" } ?: "引用了提交"
                "HeadRefDeleted" -> eventSubject?.let { "删除了分支 $it" } ?: "删除了分支"
                "HeadRefRestored" -> eventSubject?.let { "恢复了分支 $it" } ?: "恢复了分支"
                "ReviewRequested" -> eventSubject?.let { "请求 $it 评审" } ?: "请求了评审"
                "ReviewRequestRemoved" -> eventSubject?.let { "取消了 $it 的评审请求" } ?: "取消了评审请求"
                "ReviewDismissed" -> eventSubject?.let { "驳回了 $it 的评审" } ?: "驳回了评审"
                "ReadyForReview" -> "标记为可评审"
                "ConvertedToDraft" -> "转为草稿"
                "CommentDeleted" -> eventSubject?.let { "删除了 $it 的评论" } ?: "删除了评论"
                "Deployed" -> eventSubject?.let { "部署了 $it" } ?: "部署了环境"
                "BaseRefChanged" ->
                    when {
                        eventSubject != null && eventDetail != null ->
                            "将基本分支从 $eventSubject 改为 $eventDetail"
                        else -> "更改了基本分支"
                    }
                "MarkedAsDuplicate" -> eventSubject?.let { "标记为 $it 的重复项" } ?: "标记为重复项"
                "DeploymentEnvironmentChanged" ->
                    eventSubject?.let { "更新了部署环境 $it" } ?: "更新了部署环境"
                "AutoMergeEnabled" -> "启用了自动合并"
                "AutoMergeDisabled" -> "关闭了自动合并"
                "AutoRebaseEnabled" -> "启用了自动变基"
                "AutoSquashEnabled" -> "启用了自动压缩"
                "UserBlocked" -> eventSubject?.let { "屏蔽了 $it" } ?: "屏蔽了用户"
                "AddedToMergeQueue" -> "加入了合并队列"
                "RemovedFromMergeQueue" -> "移出了合并队列"
                "Connected" -> eventSubject?.let { "关联了 $it" } ?: "关联了工作项"
                "Disconnected" -> eventSubject?.let { "取消关联 $it" } ?: "取消关联了工作项"
                "AutomaticBaseChanged" ->
                    when {
                        eventSubject != null && eventDetail != null ->
                            "自动将基本分支从 $eventSubject 改为 $eventDetail"
                        else -> "自动更改了基本分支"
                    }
                "AddedToProject" -> eventSubject?.let { "将此项添加到项目 $it" } ?: "添加到了项目"
                "MovedColumnsInProject" ->
                    when {
                        eventSubject != null && eventDetail != null ->
                            "将此项从 $eventSubject 移到 $eventDetail"
                        eventDetail != null -> "将此项的项目状态设为 $eventDetail"
                        else -> "更新了此项在项目中的状态"
                    }
                "RemovedFromProject" -> eventSubject?.let { "将此项从项目 $it 移除" } ?: "从项目移除"
                "Pinned" -> "置顶了此工作项"
                "Unpinned" -> "取消置顶"
                "Transferred" -> eventSubject?.let { "转移到了 $it" } ?: "转移了工作项"
                "ConvertedToDiscussion" -> eventSubject?.let { "转换为讨论 $it" } ?: "转换为讨论"
                "SubIssueAdded" -> eventSubject?.let { "添加子议题 $it" } ?: "添加了子议题"
                "SubIssueRemoved" -> eventSubject?.let { "移除子议题 $it" } ?: "移除了子议题"
                "CopilotWorkStarted" -> "启动了 Copilot 任务"
                "CopilotWorkFinished" -> "完成了 Copilot 任务"
                "CopilotWorkFinishedFailure" -> "Copilot 任务失败"
                else -> "更新了状态"
            }
        },
        TimelineTitleChange = { currentTitle ->
            if (currentTitle.isNullOrBlank()) "更改了原标题「" to "」"
            else "将标题从「" to "」改为「$currentTitle」"
        },
        TimelineForcePush = { baseBranch, branch, before, after ->
            val action =
                when {
                    !branch.isNullOrBlank() -> "强制推送到「$branch」"
                    baseBranch -> "强制推送了 PR 目标分支"
                    else -> "强制推送了 PR 源分支"
                }
            when {
                !before.isNullOrBlank() && !after.isNullOrBlank() -> "$action（$before → $after）"
                !before.isNullOrBlank() -> "$action（原提交 $before）"
                !after.isNullOrBlank() -> "$action（新提交 $after）"
                else -> action
            }
        },
        Created = { author -> "$author 创建了此工作项" },
        CommitCount = { count -> "$count 个提交" },
        ReviewCount = { count -> "$count 个评审" },
        FileCount = { count -> "$count 个文件已更改" },
        MergeRequirements = "合并条件",
        MergePullRequest = "合并拉取请求",
        MergeMessageLoadFailed = "无法加载默认提交信息，可重试或手动填写。",
        Merge = "合并",
        MergeCommit = "创建合并提交",
        SquashMerge = "压缩并合并",
        RebaseMerge = "变基并合并",
        MergeStatus = { value ->
            when (value) {
                "MERGEABLE" -> "可合并"
                "CONFLICTING" -> "存在合并冲突"
                "UNKNOWN" -> "等待合并检查"
                "CLEAN" -> "满足合并条件"
                "DIRTY" -> "存在合并冲突"
                "BLOCKED" -> "被合并条件阻止"
                "BEHIND" -> "分支落后于目标分支"
                "UNSTABLE" -> "检查未稳定通过"
                else -> "未知状态"
            }
        },
        ReviewDecision = { value ->
            when (value) {
                "APPROVED" -> "已批准"
                "CHANGES_REQUESTED" -> "请求修改"
                "REVIEW_REQUIRED" -> "需要评审"
                else -> "等待评审状态"
            }
        },
        ReviewRequests = { count -> "$count 个评审请求" },
        LatestReview = { author, state -> "${author ?: "未知评审人"}：$state" },
        Required = "必需",
        RequiredCheckSummary = { name, state -> "$name：$state" },
        RequiredChecks = { count -> "$count 个必需检查" },
        Commits = "提交",
        Reviews = "评审",
        Files = "文件变更",
        Checks = "检查",
        ChecksProgress = { passed, total -> "检查 $passed/$total" },
        ChecksPassed = { passed, total -> "$passed/$total 通过" },
        TaskProgress = { completed, total -> "任务 $completed/$total" },
        LabelCount = { count -> "$count 个标签" },
        Comment = "评论",
        CommentActions = "评论操作",
        EditComment = "编辑评论",
        CopyLink = "复制链接",
        CopyMarkdown = "复制 Markdown",
        QuoteReply = "引用回复",
        ReferenceInNewIssue = "在新议题中引用",
        DeleteComment = "删除",
        DeleteCommentTitle = "删除评论？",
        DeleteCommentMessage = "删除后无法恢复",
        HideComment = "隐藏",
        HiddenComment = "已隐藏",
        HideReasonOffTopic = "偏离主题",
        HideReasonOutdated = "已过时",
        HideReasonResolved = "已解决",
        HideReasonSpam = "垃圾信息",
        HideReasonAbuse = "滥用",
        LineComment = "行级评论",
        WriteComment = "撰写评论",
        Publish = "发布评论",
        SaveChanges = "保存修改",
        SubmitReview = "提交评审",
        ReviewResult = "评审结果",
        Approve = "批准",
        RequestChanges = "请求修改",
        Information = "工作项信息",
        EditTitle = "编辑标题",
        EditDescription = "编辑描述",
        ChangeBaseBranch = "更改基本分支",
        LockConversation = "锁定对话",
        UnlockConversation = "解锁对话",
        ClosePullRequest = "关闭拉取请求",
        CloseIssue = "关闭议题",
        ConfirmClosePullRequest = "确认关闭此拉取请求？",
        ConfirmCloseIssue = "确认关闭此议题？",
        ConfirmLockConversation = "确认锁定此对话？",
        RequestReview = "请求评审",
        ReviewResults = "评审结果",
        MyReview = "我的评审",
        NoReviewRequests = "未选择评审人",
        NoReviewResults = "暂无他人评审",
        Properties = "属性",
        Assignees = "受理人",
        Labels = "标签",
        Milestone = "里程碑",
        LinkedItems = "链接的项",
        SubIssues = "子议题",
        Projects = "项目",
        EditProperty = "编辑",
        Unassigned = "未分配",
        NoLabels = "未选择标签",
        NoMilestone = "未选择里程碑",
        NoLinkedItems = "未选择链接的项",
        NoSubIssues = "暂无子议题",
        NoProjects = "未选择项目",
        PropertyLoadFailed = "属性加载失败",
        PropertySaveFailed = "未能确认保存结果，请刷新核对后重试；草稿已保留",
        ContinueAdding = "继续添加",
        PropertySearch = "搜索选项",
        NoPropertyOptions = "没有可用选项",
        RequestedReviewers = "已请求评审",
        Author = "作者",
        Status = "状态",
        Locked = "讨论已锁定",
        IssueTitle = { number -> "议题 #$number" },
        PullRequestTitle = { number -> "拉取请求 #$number" },
        LineContext = { path, line, newSide ->
            "$path · ${if (newSide) "新" else "旧"}第 $line 行"
        },
    )

internal val EnWorkDetailStrings: GithubStrings_WorkDetail =
    GithubStrings_WorkDetail(
        LoadFailed = "Unable to load details",
        RecoveryTitle = "Verify submission",
        SubmissionUncertain = "Submission result is uncertain",
        RecoveryMessage =
            "Refresh or check GitHub before posting again. Your draft has been kept.",
        Acknowledged = "Verified",
        NoDescription = "No description",
        NoBody = "No content",
        NoChecksCommit =
            "Checks are unavailable because GitHub did not provide a head commit.",
        CommitStatuses = "Commit statuses",
        PreviousChecks = "Load previous checks",
        RevisionChanged = "This pull request has changed. Tap to reload.",
        CommentPosted = "Comment posted",
        LineNumber = { "Line $it" },
        CheckStatus = { value ->
            when (value) {
                "queued" -> "Queued"
                "in_progress" -> "In progress"
                "completed" -> "Completed"
                "waiting" -> "Waiting"
                "requested" -> "Requested"
                "pending" -> "Pending"
                "expected" -> "Expected"
                "error" -> "Error"
                "success" -> "Success"
                "failure" -> "Failure"
                else -> value
            }
        },
        CheckConclusion = { value ->
            when (value) {
                "success" -> "Success"
                "failure" -> "Failure"
                "cancelled" -> "Cancelled"
                "timed_out" -> "Timed out"
                "action_required" -> "Action required"
                "neutral" -> "Neutral"
                "skipped" -> "Skipped"
                "stale" -> "Stale"
                "startup_failure" -> "Startup failure"
                else -> value
            }
        },
        ReviewState = { value ->
            when (value) {
                "APPROVED" -> "Approved"
                "CHANGES_REQUESTED" -> "Changes requested"
                "COMMENTED" -> "Commented"
                "DISMISSED" -> "Dismissed"
                "PENDING" -> "Pending"
                else -> value
            }
        },
        MarkDone = "Mark notification as done",
        Done = "Notification completed",
        ConfirmDone = "Complete this notification?",
        ConfirmDoneMessage =
            "Only the notification is completed. The issue or pull request will not be closed or merged.",
        Subscribe = "Subscribe to this work item",
        Unsubscribe = "Unsubscribe from this work item",
        SubscriptionFailed = "Unable to load subscription. Tap to retry",
        NotificationUnavailable =
            "The pending notification could not be located. Open it from Notifications and retry.",
        Discussion = "Discussion",
        Activity = "Activity",
        Changes = "Changes",
        TimelineEvent = { kind, subject, detail ->
            val eventSubject = subject?.takeIf { it.isNotBlank() }
            val eventDetail = detail?.takeIf { it.isNotBlank() }
            when (kind) {
                "Closed" -> "closed this item"
                "Reopened" -> "reopened this item"
                "RenamedTitle" ->
                    eventDetail?.let { "renamed the title to \"$it\"" } ?: "renamed the title"
                "Merged" ->
                    when {
                        eventSubject != null && eventDetail != null ->
                            "merged commit $eventDetail into $eventSubject"
                        eventSubject != null -> "merged into $eventSubject"
                        else -> "merged this pull request"
                    }
                "Labeled" -> eventSubject?.let { "added label: $it" } ?: "added a label"
                "Unlabeled" ->
                    eventSubject?.let { "removed label: $it" } ?: "removed a label"
                "Assigned" -> eventSubject?.let { "assigned to $it" } ?: "assigned someone"
                "Unassigned" ->
                    eventSubject?.let { "unassigned $it" } ?: "removed an assignee"
                "Locked" -> "locked the conversation"
                "Unlocked" -> "unlocked the conversation"
                "Milestoned" ->
                    eventSubject?.let { "set milestone: $it" } ?: "set a milestone"
                "Demilestoned" ->
                    eventSubject?.let { "removed milestone: $it" } ?: "removed a milestone"
                "CrossReferenced" ->
                    eventSubject?.let { "referenced $it" } ?: "referenced this item"
                "Referenced" ->
                    eventSubject?.let { "referenced $it" } ?: "referenced a commit"
                "HeadRefDeleted" ->
                    eventSubject?.let { "deleted branch $it" } ?: "deleted a branch"
                "HeadRefRestored" ->
                    eventSubject?.let { "restored branch $it" } ?: "restored a branch"
                "ReviewRequested" ->
                    eventSubject?.let { "requested a review from $it" }
                        ?: "requested a review"
                "ReviewRequestRemoved" ->
                    eventSubject?.let { "removed the review request for $it" }
                        ?: "removed a review request"
                "ReviewDismissed" ->
                    eventSubject?.let { "dismissed the review by $it" }
                        ?: "dismissed a review"
                "ReadyForReview" -> "marked as ready for review"
                "ConvertedToDraft" -> "converted to draft"
                "CommentDeleted" ->
                    eventSubject?.let { "deleted a comment by $it" } ?: "deleted a comment"
                "Deployed" ->
                    eventSubject?.let { "deployed $it" } ?: "deployed an environment"
                "BaseRefChanged" ->
                    when {
                        eventSubject != null && eventDetail != null ->
                            "changed the base branch from $eventSubject to $eventDetail"
                        else -> "changed the base branch"
                    }
                "MarkedAsDuplicate" ->
                    eventSubject?.let { "marked as a duplicate of $it" }
                        ?: "marked as a duplicate"
                "DeploymentEnvironmentChanged" ->
                    eventSubject?.let { "updated $it" }
                        ?: "updated the deployment environment"
                "AutoMergeEnabled" -> "enabled auto-merge"
                "AutoMergeDisabled" -> "disabled auto-merge"
                "AutoRebaseEnabled" -> "enabled auto-rebase"
                "AutoSquashEnabled" -> "enabled auto-squash"
                "UserBlocked" -> eventSubject?.let { "blocked $it" } ?: "blocked a user"
                "AddedToMergeQueue" -> "added to the merge queue"
                "RemovedFromMergeQueue" -> "removed from the merge queue"
                "Connected" -> eventSubject?.let { "connected $it" } ?: "connected an item"
                "Disconnected" ->
                    eventSubject?.let { "disconnected $it" } ?: "disconnected an item"
                "AutomaticBaseChanged" ->
                    when {
                        eventSubject != null && eventDetail != null ->
                            "automatically changed the base branch from " +
                                "$eventSubject to $eventDetail"
                        else -> "automatically changed the base branch"
                    }
                "AddedToProject" ->
                    eventSubject?.let { "added this item to $it" }
                        ?: "added this item to a project"
                "MovedColumnsInProject" ->
                    when {
                        eventSubject != null && eventDetail != null ->
                            "moved this item from $eventSubject to $eventDetail"
                        eventDetail != null -> "set this item's project status to $eventDetail"
                        else -> "updated this item's status in a project"
                    }
                "RemovedFromProject" ->
                    eventSubject?.let { "removed this item from $it" }
                        ?: "removed this item from a project"
                "Pinned" -> "pinned this item"
                "Unpinned" -> "unpinned this item"
                "Transferred" ->
                    eventSubject?.let { "transferred this item to $it" }
                        ?: "transferred this item"
                "ConvertedToDiscussion" ->
                    eventSubject?.let { "converted this item to $it" }
                        ?: "converted this item to a discussion"
                "SubIssueAdded" ->
                    eventSubject?.let { "added sub-issue $it" } ?: "added a sub-issue"
                "SubIssueRemoved" ->
                    eventSubject?.let { "removed sub-issue $it" } ?: "removed a sub-issue"
                "CopilotWorkStarted" -> "started a Copilot task"
                "CopilotWorkFinished" -> "finished a Copilot task"
                "CopilotWorkFinishedFailure" -> "Copilot task failed"
                else -> "updated the status"
            }
        },
        TimelineTitleChange = { currentTitle ->
            if (currentTitle.isNullOrBlank()) "changed the previous title \"" to "\""
            else "renamed the title from \"" to "\" to \"$currentTitle\""
        },
        TimelineForcePush = { baseBranch, branch, before, after ->
            val action =
                when {
                    !branch.isNullOrBlank() -> "force-pushed to \"$branch\""
                    baseBranch -> "force-pushed the PR base branch"
                    else -> "force-pushed the PR head branch"
                }
            when {
                !before.isNullOrBlank() && !after.isNullOrBlank() -> "$action ($before → $after)"
                !before.isNullOrBlank() -> "$action (previous commit $before)"
                !after.isNullOrBlank() -> "$action (new commit $after)"
                else -> action
            }
        },
        Created = { author -> "$author opened this item" },
        CommitCount = { count -> "$count commits" },
        ReviewCount = { count -> "$count reviews" },
        FileCount = { count -> "$count files changed" },
        MergeRequirements = "Merge requirements",
        MergePullRequest = "Merge pull request",
        MergeMessageLoadFailed =
            "Could not load the default commit message. Retry or enter it manually.",
        Merge = "Merge",
        MergeCommit = "Create a merge commit",
        SquashMerge = "Squash and merge",
        RebaseMerge = "Rebase and merge",
        MergeStatus = { value ->
            when (value) {
                "MERGEABLE" -> "Ready to merge"
                "CONFLICTING" -> "Merge conflicts"
                "UNKNOWN" -> "Merge status pending"
                "CLEAN" -> "Ready to merge"
                "DIRTY" -> "Merge conflicts"
                "BLOCKED" -> "Blocked by requirements"
                "BEHIND" -> "Branch is behind base"
                "UNSTABLE" -> "Checks are unstable"
                else -> "Unknown status"
            }
        },
        ReviewDecision = { value ->
            when (value) {
                "APPROVED" -> "Approved"
                "CHANGES_REQUESTED" -> "Changes requested"
                "REVIEW_REQUIRED" -> "Review required"
                else -> "Review status pending"
            }
        },
        ReviewRequests = { count -> "$count review requests" },
        LatestReview = { author, state -> "${author ?: "Unknown reviewer"}: $state" },
        Required = "Required",
        RequiredCheckSummary = { name, state -> "$name: $state" },
        RequiredChecks = { count -> "$count required checks" },
        Commits = "Commits",
        Reviews = "Reviews",
        Files = "Files changed",
        Checks = "Checks",
        ChecksProgress = { passed, total -> "Checks $passed/$total" },
        ChecksPassed = { passed, total -> "$passed/$total passed" },
        TaskProgress = { completed, total -> "Tasks $completed/$total" },
        LabelCount = { count -> "$count labels" },
        Comment = "Comment",
        CommentActions = "Comment actions",
        EditComment = "Edit comment",
        CopyLink = "Copy link",
        CopyMarkdown = "Copy Markdown",
        QuoteReply = "Quote reply",
        ReferenceInNewIssue = "Reference in new issue",
        DeleteComment = "Delete",
        DeleteCommentTitle = "Delete comment?",
        DeleteCommentMessage = "This cannot be undone.",
        HideComment = "Hide",
        HiddenComment = "Hidden",
        HideReasonOffTopic = "Off-topic",
        HideReasonOutdated = "Outdated",
        HideReasonResolved = "Resolved",
        HideReasonSpam = "Spam",
        HideReasonAbuse = "Abuse",
        LineComment = "Line comment",
        WriteComment = "Write a comment",
        Publish = "Post comment",
        SaveChanges = "Save changes",
        SubmitReview = "Submit review",
        ReviewResult = "Review result",
        Approve = "Approve",
        RequestChanges = "Request changes",
        Information = "Work item information",
        EditTitle = "Edit title",
        EditDescription = "Edit description",
        ChangeBaseBranch = "Change base branch",
        LockConversation = "Lock conversation",
        UnlockConversation = "Unlock conversation",
        ClosePullRequest = "Close pull request",
        CloseIssue = "Close issue",
        ConfirmClosePullRequest = "Close this pull request?",
        ConfirmCloseIssue = "Close this issue?",
        ConfirmLockConversation = "Lock this conversation?",
        RequestReview = "Request review",
        ReviewResults = "Review results",
        MyReview = "My review",
        NoReviewRequests = "No reviewers selected",
        NoReviewResults = "No reviews from others",
        Properties = "Properties",
        Assignees = "Assignees",
        Labels = "Labels",
        Milestone = "Milestone",
        LinkedItems = "Linked items",
        SubIssues = "Sub-issues",
        Projects = "Projects",
        EditProperty = "Edit",
        Unassigned = "Unassigned",
        NoLabels = "No labels selected",
        NoMilestone = "No milestone selected",
        NoLinkedItems = "No linked items selected",
        NoSubIssues = "No sub-issues",
        NoProjects = "No projects selected",
        PropertyLoadFailed = "Unable to load properties",
        PropertySaveFailed =
            "Saving could not be confirmed. Refresh and check before retrying. Your draft is retained",
        ContinueAdding = "Add more",
        PropertySearch = "Search options",
        NoPropertyOptions = "No available options",
        RequestedReviewers = "Requested reviewers",
        Author = "Author",
        Status = "Status",
        Locked = "Conversation locked",
        IssueTitle = { number -> "Issue #$number" },
        PullRequestTitle = { number -> "Pull request #$number" },
        LineContext = { path, line, newSide ->
            "$path · ${if (newSide) "New" else "Old"} line $line"
        },
    )
