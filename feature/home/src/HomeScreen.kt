package moe.yumeyuka.yumehub.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.locale.GithubStrings_Home
import moe.yumeyuka.yumehub.ui.component.GithubSection

internal fun LazyListScope.HomeScreen(
    items: List<HomeWorkItem>,
    strings: GithubStrings_Home,
    onEdit: () -> Unit,
    onOpenRepositories: () -> Unit,
    onOpenWork: (CollaborationKind) -> Unit,
    onOpenStarred: () -> Unit,
    onOpenOrganizations: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenDiscussions: () -> Unit,
) {
    item(key = "home-repositories") {
        GithubSection(
            title = strings.MyWork,
            modifier = Modifier.animateContentSize(tween(420)),
        ) {
            AnimatedContent(
                targetState = items,
                transitionSpec = {
                    (fadeIn(tween(220)) togetherWith fadeOut(tween(160))).using(
                        SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(420) }),
                    )
                },
                label = "home-work-items",
            ) { displayed ->
                Column {
                    if (displayed.isEmpty()) {
                        GlasmioListRow(
                            title = strings.NoWorkItems,
                            systemName = null,
                            onClick = onEdit,
                        )
                    }
                    displayed.forEachIndexed { index, item ->
                        key(item) {
                            GlasmioListRow(
                                title = item.title(strings),
                                showTopSeparator = index > 0,
                                leading = { HomeWorkIcon(item) },
                                showDisclosure = true,
                                onClick =
                                    when (item) {
                                        HomeWorkItem.Issues -> ({
                                                onOpenWork(CollaborationKind.Issue)
                                            })
                                        HomeWorkItem.PullRequests -> ({
                                                onOpenWork(CollaborationKind.PullRequest)
                                            })
                                        HomeWorkItem.Repositories -> onOpenRepositories
                                        HomeWorkItem.Starred -> onOpenStarred
                                        HomeWorkItem.Organizations -> onOpenOrganizations
                                        HomeWorkItem.Discussions -> onOpenDiscussions
                                        HomeWorkItem.Projects -> onOpenProjects
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
