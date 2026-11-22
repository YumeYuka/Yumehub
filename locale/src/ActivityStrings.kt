package moe.yumeyuka.yumehub.locale

public data class GithubStrings_Activity(
    val Commit: String,
    val Details: String,
    val CommitHash: String,
    val CopyHash: String,
    val Overview: String,
    val Parents: String,
    val Related: String,
    val Unavailable: String,
    val Empty: String,
    val LoadFailed: String,
    val Replies: (Int) -> String,
    val Reply: String,
    val DiscussionAuthorRole: (String) -> String?,
    val CommentFailed: String,
    val CommentUnconfirmed: String,
    val ActionFailed: String,
    val MarkAsAnswer: String,
    val UnmarkAsAnswer: String,
    val Answer: String,
    val Upvote: (Int) -> String,
    val PollVotes: (Int) -> String,
    val AddReaction: String,
    val ChooseReaction: String,
    val ReactionLike: String,
    val ReactionDislike: String,
    val ReactionLaugh: String,
    val ReactionCelebrate: String,
    val ReactionConfused: String,
    val ReactionLove: String,
    val ReactionRocket: String,
    val ReactionEyes: String,
    val ReactionCount: (String, Int) -> String,
    val LoadMoreItems: String,
    val NoWorkflows: String,
    val AllRuns: String,
    val RunWorkflow: String,
    val Rerun: String,
    val RerunFailed: String,
    val ArtifactExpired: String,
    val DownloadZip: String,
    val DownloadFailed: String,
    val NoArtifacts: String,
    val RunSummary: String,
    val RerunSubmitted: String,
    val DebugLogging: String,
    val RunBranch: String,
    val ManualUnavailable: String,
    val RunSubmitted: String,
    val RunUncertain: String,
    val WorkflowFilter: String,
    val NoWorkflowRuns: String,
    val NoJobs: String,
    val NoSteps: String,
    val WorkflowRunCount: (Int) -> String,
    val WorkflowRunTitle: (String, Int) -> String,
    val ElapsedSeconds: (Int) -> String,
    val State: (String) -> String,
)

internal val ZhHansActivityStrings: GithubStrings_Activity =
    GithubStrings_Activity(
        Commit = "提交",
        Details = "详细信息",
        CommitHash = "提交哈希",
        CopyHash = "复制提交哈希",
        Overview = "简介",
        Parents = "父提交",
        Related = "关联 PR",
        Unavailable = "内容不可用",
        Empty = "暂无内容",
        LoadFailed = "加载失败",
        Replies = { "$it 条回复" },
        Reply = "回复",
        DiscussionAuthorRole = {
            when (it) {
                "OWNER" -> "所有者"
                "MEMBER" -> "组织成员"
                "COLLABORATOR" -> "协作者"
                "CONTRIBUTOR" -> "贡献者"
                "FIRST_TIME_CONTRIBUTOR" -> "首次贡献"
                "FIRST_TIMER" -> "首次参与"
                else -> null
            }
        },
        CommentFailed = "未能发表评论，草稿已保留。",
        CommentUnconfirmed = "未能确认评论是否已发表。请返回讨论核对后再试，草稿已保留。",
        ActionFailed = "操作失败，请重试",
        MarkAsAnswer = "标记为答案",
        UnmarkAsAnswer = "取消最佳答案",
        Answer = "最佳答案",
        Upvote = { "$it 个赞同" },
        PollVotes = { "$it 票" },
        AddReaction = "添加回应",
        ChooseReaction = "选择回应",
        ReactionLike = "赞",
        ReactionDislike = "踩",
        ReactionLaugh = "大笑",
        ReactionCelebrate = "庆祝",
        ReactionConfused = "困惑",
        ReactionLove = "喜爱",
        ReactionRocket = "火箭",
        ReactionEyes = "关注",
        ReactionCount = { label, count -> "$label，$count 个回应" },
        LoadMoreItems = "加载更多项目",
        NoWorkflows = "此仓库没有工作流",
        AllRuns = "全部运行",
        RunWorkflow = "运行工作流",
        Rerun = "重新运行",
        RerunFailed = "重新运行失败的作业",
        ArtifactExpired = "工件已过期",
        DownloadZip = "下载 ZIP",
        DownloadFailed = "工件下载失败，请检查账号的 Actions 读取权限",
        NoArtifacts = "没有工件",
        RunSummary = "运行摘要",
        RerunSubmitted = "已提交重新运行请求",
        DebugLogging = "启用调试日志",
        RunBranch = "运行分支",
        ManualUnavailable = "此分支不支持手动运行",
        RunSubmitted = "已提交工作流运行",
        RunUncertain = "未能确认是否已提交，请先查看运行历史",
        WorkflowFilter = "筛选工作流",
        NoWorkflowRuns = "此工作流尚无运行记录",
        NoJobs = "此次运行没有作业",
        NoSteps = "此作业没有步骤",
        WorkflowRunCount = { "运行 $it 次" },
        WorkflowRunTitle = { name, number -> "$name #$number" },
        ElapsedSeconds = { if (it < 60) "$it 秒" else "${it / 60} 分 ${it % 60} 秒" },
        State = { value ->
            when (value.lowercase(java.util.Locale.ROOT)) {
                "open" -> "进行中"
                "active" -> "启用"
                "closed" -> "已关闭"
                "merged" -> "已合并"
                "success" -> "成功"
                "failure" -> "失败"
                "cancelled" -> "已取消"
                "queued" -> "排队中"
                "pending" -> "等待中"
                "waiting" -> "等待中"
                "in_progress" -> "运行中"
                "completed" -> "已完成"
                "skipped" -> "已跳过"
                "neutral" -> "中性"
                "timed_out" -> "已超时"
                "action_required" -> "需要操作"
                "requested" -> "已请求"
                "stale" -> "已过期"
                "startup_failure" -> "启动失败"
                "disabled_manually" -> "手动停用"
                "disabled_inactivity" -> "因不活跃停用"
                "disabled_fork" -> "复刻仓库停用"
                "deleted" -> "已删除"
                else -> value
            }
        },
    )

internal val EnActivityStrings: GithubStrings_Activity =
    GithubStrings_Activity(
        Commit = "Commit",
        Details = "Details",
        CommitHash = "Commit hash",
        CopyHash = "Copy commit hash",
        Overview = "Overview",
        Parents = "Parents",
        Related = "Related PRs",
        Unavailable = "Content unavailable",
        Empty = "Nothing here yet",
        LoadFailed = "Couldn't load",
        Replies = { if (it == 1) "1 reply" else "$it replies" },
        Reply = "Reply",
        DiscussionAuthorRole = {
            when (it) {
                "OWNER" -> "Owner"
                "MEMBER" -> "Member"
                "COLLABORATOR" -> "Collaborator"
                "CONTRIBUTOR" -> "Contributor"
                "FIRST_TIME_CONTRIBUTOR" -> "New contributor"
                "FIRST_TIMER" -> "New participant"
                else -> null
            }
        },
        CommentFailed = "Could not post the comment. Your draft was kept.",
        CommentUnconfirmed =
            "Could not confirm posting. Go back and check the discussion before retrying. Your draft was kept.",
        ActionFailed = "Could not complete the action. Please retry.",
        MarkAsAnswer = "Mark as answer",
        UnmarkAsAnswer = "Unmark answer",
        Answer = "Answer",
        Upvote = { "$it upvotes" },
        PollVotes = { if (it == 1) "1 vote" else "$it votes" },
        AddReaction = "Add reaction",
        ChooseReaction = "Choose a reaction",
        ReactionLike = "Like",
        ReactionDislike = "Dislike",
        ReactionLaugh = "Laugh",
        ReactionCelebrate = "Celebrate",
        ReactionConfused = "Confused",
        ReactionLove = "Love",
        ReactionRocket = "Rocket",
        ReactionEyes = "Eyes",
        ReactionCount = { label, count -> "$label, $count reactions" },
        LoadMoreItems = "Load more items",
        NoWorkflows = "No workflows in this repository",
        AllRuns = "All runs",
        RunWorkflow = "Run workflow",
        Rerun = "Re-run jobs",
        RerunFailed = "Re-run failed jobs",
        ArtifactExpired = "Artifact expired",
        DownloadZip = "Download ZIP",
        DownloadFailed = "Artifact download failed. Check your Actions read access.",
        NoArtifacts = "No artifacts",
        RunSummary = "Run summary",
        RerunSubmitted = "Re-run requested",
        DebugLogging = "Enable debug logging",
        RunBranch = "Run from branch",
        ManualUnavailable = "Manual runs are unavailable on this branch",
        RunSubmitted = "Workflow run submitted",
        RunUncertain = "Submission wasn't confirmed. Check the run history first.",
        WorkflowFilter = "Filter workflows",
        NoWorkflowRuns = "This workflow hasn't run yet",
        NoJobs = "No jobs in this run",
        NoSteps = "No steps in this job",
        WorkflowRunCount = { if (it == 1) "1 run" else "$it runs" },
        WorkflowRunTitle = { name, number -> "$name #$number" },
        ElapsedSeconds = { if (it < 60) "${it}s" else "${it / 60}m ${it % 60}s" },
        State = { it.lowercase(java.util.Locale.ROOT).replace('_', ' ') },
    )
