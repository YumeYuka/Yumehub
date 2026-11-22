package moe.yumeyuka.yumehub.locale

public data class GithubStrings_Explore(
    val Title: String,
    val Discover: String,
    val Trending: String,
    val Featured: String,
    val Period: String,
    val Today: String,
    val ThisWeek: String,
    val ThisMonth: String,
    val ProgrammingLanguage: String,
    val SpokenLanguage: String,
    val ActivityType: String,
    val AllActivities: String,
    val NewestFirst: String,
    val OldestFirst: String,
    val Announcements: String,
    val Stars: String,
    val Repositories: String,
    val Follows: String,
    val Suggestions: String,
    val ReadMore: String,
    val OpenOnGitHub: String,
    val Merged: String,
    val Closed: String,
    val Draft: String,
    val Open: String,
    val WorkTitle: (pullRequest: Boolean, number: Int?) -> String,
    val ReleaseTitle: String,
    val ReleaseCover: (title: String) -> String,
    val CreatedDiscussion: (repository: String) -> String,
    val RecommendedUser: (login: String) -> String,
    val RecommendedRepository: (repository: String) -> String,
    val FeedEvent: (type: String, target: String, action: String?) -> String,
    val RepositoryCount: (Int) -> String,
    val FollowingUser: String,
    val Unfollow: String,
    val UnfollowPrompt: String,
)

internal val ZhHansExploreStrings: GithubStrings_Explore =
    GithubStrings_Explore(
        Title = "探索",
        Discover = "发现",
        Trending = "热门仓库",
        Featured = "精选仓库",
        Period = "时间范围",
        Today = "今天",
        ThisWeek = "本周",
        ThisMonth = "本月",
        ProgrammingLanguage = "编程语言",
        SpokenLanguage = "口语",
        ActivityType = "活动类型",
        AllActivities = "全部活动",
        NewestFirst = "最新优先",
        OldestFirst = "最早优先",
        Announcements = "公告",
        Stars = "星标",
        Repositories = "仓库",
        Follows = "关注",
        Suggestions = "建议",
        ReadMore = "阅读更多",
        OpenOnGitHub = "在 GitHub 中查看",
        Merged = "已合并",
        Closed = "已关闭",
        Draft = "草稿",
        Open = "打开",
        WorkTitle = { pullRequest, number ->
            "${if (pullRequest) "拉取请求" else "议题"} #${number ?: ""}"
        },
        ReleaseTitle = "发布版本",
        ReleaseCover = { title -> "$title 的发布封面" },
        CreatedDiscussion = { repository -> "创建了 $repository 的讨论" },
        RecommendedUser = { login -> "推荐关注 $login" },
        RecommendedRepository = { repository -> "推荐了 $repository" },
        FeedEvent = { type, target, action ->
            val verb = when (action) {
                "opened" -> "创建"
                "merged" -> "合并"
                "closed" -> "关闭"
                "reopened" -> "重新打开"
                else -> "更新"
            }
            when (type) {
                "WatchEvent" -> "已向 1 个仓库添加星标"
                "ForkEvent" -> "派生了 $target"
                "FollowEvent" -> "关注了 $target"
                "CreateEvent" -> "创建了 $target"
                "ReleaseEvent" -> "发布了 $target 的新版本"
                "IssuesEvent" -> "${verb}了 $target 的议题"
                "PullRequestEvent" -> "${verb}了 $target 的拉取请求"
                "PublicEvent" -> "公开了 $target"
                "MemberEvent" -> if (action == "removed") "在 $target 移除了协作者" else "在 $target 添加了协作者"
                else -> "更新了 $target"
            }
        },
        RepositoryCount = { "$it 个仓库" },
        FollowingUser = "已关注",
        Unfollow = "取消关注",
        UnfollowPrompt = "将不再接收此用户的关注动态。",
    )

internal val EnExploreStrings: GithubStrings_Explore =
    GithubStrings_Explore(
        Title = "Explore",
        Discover = "Discover",
        Trending = "Trending repositories",
        Featured = "Featured repositories",
        Period = "Time range",
        Today = "Today",
        ThisWeek = "This week",
        ThisMonth = "This month",
        ProgrammingLanguage = "Programming language",
        SpokenLanguage = "Spoken language",
        ActivityType = "Activity types",
        AllActivities = "All activity",
        NewestFirst = "Newest first",
        OldestFirst = "Oldest first",
        Announcements = "Announcements",
        Stars = "Stars",
        Repositories = "Repositories",
        Follows = "Follows",
        Suggestions = "Recommendations",
        ReadMore = "Read more",
        OpenOnGitHub = "Open on GitHub",
        Merged = "Merged",
        Closed = "Closed",
        Draft = "Draft",
        Open = "Open",
        WorkTitle = { pullRequest, number ->
            "${if (pullRequest) "Pull request" else "Issue"} #${number ?: ""}"
        },
        ReleaseTitle = "Release",
        ReleaseCover = { title -> "Release cover for $title" },
        CreatedDiscussion = { repository -> "Created a discussion in $repository" },
        RecommendedUser = { login -> "Recommended $login" },
        RecommendedRepository = { repository -> "Recommended $repository" },
        FeedEvent = { type, target, action ->
            val verb = when (action) {
                "opened" -> "opened"
                "merged" -> "merged"
                "closed" -> "closed"
                "reopened" -> "reopened"
                else -> "updated"
            }
            when (type) {
                "WatchEvent" -> "starred a repository"
                "ForkEvent" -> "forked $target"
                "FollowEvent" -> "followed $target"
                "CreateEvent" -> "created $target"
                "ReleaseEvent" -> "published a release in $target"
                "IssuesEvent" -> "$verb an issue in $target"
                "PullRequestEvent" -> "$verb a pull request in $target"
                "PublicEvent" -> "made $target public"
                "MemberEvent" -> if (action == "removed") "removed a collaborator from $target" else "added a collaborator to $target"
                else -> "updated $target"
            }
        },
        RepositoryCount = { if (it == 1) "$it repository" else "$it repositories" },
        FollowingUser = "Following",
        Unfollow = "Unfollow",
        UnfollowPrompt = "You will no longer see activity from this user in your feed.",
    )
