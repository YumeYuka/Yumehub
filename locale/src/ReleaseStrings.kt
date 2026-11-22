package moe.yumeyuka.yumehub.locale

public data class GithubStrings_Release(
    val Timeline: String,
    val SearchVersions: String,
    val NoMatchingVersions: String,
    val CopyTag: String,
    val Tag: String,
    val Artifacts: String,
    val SourceAndAttestation: String,
    val CopyDigest: String,
    val AssetStats: (String, Int) -> String,
    val Download: String,
    val SourceArchive: (String) -> String,
    val ViewAttestation: String,
    val AssetsLoadFailed: String,
    val Latest: String,
    val Prerelease: String,
    val Empty: String,
    val VersionNotFound: String,
    val NoNotes: String,
    val LoadFailed: String,
)

internal val ZhHansReleaseStrings: GithubStrings_Release =
    GithubStrings_Release(
        Timeline = "版本时间线",
        SearchVersions = "搜索发行版",
        NoMatchingVersions = "未找到匹配的版本",
        CopyTag = "复制标签",
        Tag = "标签",
        Artifacts = "工件",
        SourceAndAttestation = "源码与发行证明",
        CopyDigest = "复制哈希",
        AssetStats = { size, count -> "$size · $count 次下载" },
        Download = "下载",
        SourceArchive = { extension -> "源代码（$extension）" },
        ViewAttestation = "在 GitHub 查看发行证明",
        AssetsLoadFailed = "发行版附件加载失败",
        Latest = "最新版",
        Prerelease = "预发布",
        Empty = "暂无发行版",
        VersionNotFound = "未找到该发行版",
        NoNotes = "暂无发布说明",
        LoadFailed = "发行版加载失败",
    )

internal val EnReleaseStrings: GithubStrings_Release =
    GithubStrings_Release(
        Timeline = "Release history",
        SearchVersions = "Search releases",
        NoMatchingVersions = "No matching releases",
        CopyTag = "Copy tag",
        Tag = "Tag",
        Artifacts = "Artifacts",
        SourceAndAttestation = "Source and attestation",
        CopyDigest = "Copy hash",
        AssetStats = { size, count ->
            "$size · $count ${if (count == 1) "download" else "downloads"}"
        },
        Download = "Download",
        SourceArchive = { extension -> "Source code ($extension)" },
        ViewAttestation = "View release attestation on GitHub",
        AssetsLoadFailed = "Unable to load release attachments",
        Latest = "Latest",
        Prerelease = "Pre-release",
        Empty = "No releases",
        VersionNotFound = "Release not found",
        NoNotes = "No release notes",
        LoadFailed = "Unable to load the release",
    )
