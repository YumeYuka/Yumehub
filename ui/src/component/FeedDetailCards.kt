package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.yumeyucca.lucide.ChevronRight
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.MessagesSquare
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

@Composable
internal fun FeedWorkCard(
    event: FeedEventDto,
    state: FeedCardState?,
    open: (() -> Unit)?,
) {
    val work = state?.work ?: event.workItem() ?: return
    val repository = state?.repository ?: event.repositoryDetails
    val pullRequest = event.isPullRequestWork()
    val number = work.number ?: event.payload.number
    val strings = LocalGithubStrings.current
    val discussion = event.payload.discussion != null
    val slug = repository?.fullName ?: event.repo?.name
    val kind = if (pullRequest) CollaborationKind.PullRequest else CollaborationKind.Issue
    val target =
        remember(slug, number, kind, discussion) {
            if (discussion || number == null || slug == null || '/' !in slug) null
            else WorkTarget(slug.substringBefore('/'), slug.substringAfter('/'), number, kind)
        }
    val write = rememberWorkRowProjection(target)
    val merged =
        write.merged || write.confirmed?.merged == true || work.merged || work.mergedAt != null
    val mergedPullRequest =
        pullRequest &&
            (merged ||
                event.sourceType == "MergedPullRequestFeedItem" ||
                event.payload.action == "merged")
    val title =
        write.title
            ?: write.confirmed?.title
            ?: work.title?.takeIf(String::isNotBlank)
            ?: if (discussion) strings.Repository.Discussions
            else strings.Explore.WorkTitle(pullRequest, number)
    FeedDetailCard(
        title = title,
        body = write.body ?: write.confirmed?.body ?: work.body,
        open = open,
        titleMaxLines = if (discussion) 3 else 2,
        compactSummary = mergedPullRequest,
        header = {
            RepositoryCardHeading(
                repository = repository,
                fallbackFullName = event.repo?.name,
                number = number.takeUnless { mergedPullRequest },
                trailing =
                    if (mergedPullRequest) null
                    else
                        ({
                            if (discussion) {
                                GithubIcon(
                                    Lucide.MessagesSquare,
                                    contentDescription = strings.Repository.Discussions,
                                    size = 20.dp,
                                )
                            } else {
                                workMergeProgress(write.mergePhase)?.let {
                                    Text(it, style = GlasmioTheme.textStyles.caption1)
                                }
                                GithubWorkItemStatusIcon(
                                    kind,
                                    write.state ?: write.confirmed?.state ?: work.state,
                                    work.draft,
                                    merged,
                                )
                            }
                        }),
            )
        },
    )
}

@Composable
internal fun FeedReleaseCard(event: FeedEventDto, repository: RepositoryDto?, open: (() -> Unit)?) {
    val release = event.payload.release ?: return
    val strings = LocalGithubStrings.current.Explore
    val title = release.name?.takeIf { it.isNotBlank() } ?: release.tagName ?: strings.ReleaseTitle
    FeedDetailCard(
        title = title,
        body = release.body,
        open = open,
        cover = {
            GithubRepositoryCover(
                imageUrl = release.socialPreviewImageUrl ?: repository?.openGraphImageUrl,
                description = strings.ReleaseCover(title),
            )
        },
        header = {
            RepositoryCardHeading(
                repository = repository,
                fallbackFullName = event.repo?.name,
            )
        },
    )
}

@Composable
private fun FeedDetailCard(
    title: String,
    body: String?,
    open: (() -> Unit)?,
    header: @Composable () -> Unit,
    cover: (@Composable () -> Unit)? = null,
    titleMaxLines: Int = 3,
    compactSummary: Boolean = false,
) {
    GithubActivityCard(
        onClick = open,
        cover = cover,
        header = header,
        summary = {
            GithubActivityCardSummary(
                title,
                body,
                titleStyle =
                    if (compactSummary) githubWorkItemTitleStyle() else feedDetailTitleStyle(),
                bodyStyle =
                    if (compactSummary)
                        repositoryCardBodyStyle().copy(fontSize = 14.sp, lineHeight = 20.sp)
                    else repositoryCardBodyStyle(),
                titleMaxLines = titleMaxLines,
            )
        },
        action = open?.let { onOpen -> { FeedReadMoreButton(onOpen) } },
    )
}

@Composable
internal fun feedDetailTitleStyle() =
    githubWorkItemTitleStyle().copy(fontSize = 18.sp, lineHeight = 24.sp)

@Composable
private fun FeedReadMoreButton(onOpen: () -> Unit) {
    GlasmioButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = LocalGithubStrings.current.Explore.ReadMore,
                style = GlasmioTheme.textStyles.body.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = GlasmioTheme.colors.content,
                maxLines = 1,
            )
            GithubIcon(Lucide.ChevronRight, contentDescription = null, size = 20.dp)
        }
    }
}
