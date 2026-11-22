package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonDefaults
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.yumeyucca.lucide.CircleCheck
import io.github.yumeyucca.lucide.CircleDot
import io.github.yumeyucca.lucide.CircleSlash
import io.github.yumeyucca.lucide.GitMerge
import io.github.yumeyucca.lucide.GitPullRequest
import io.github.yumeyucca.lucide.GitPullRequestClosed
import io.github.yumeyucca.lucide.GitPullRequestDraft
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.MessageSquare
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors

data class GithubWorkStatusVisual(
    val icon: ImageVector,
    val color: Color,
)

object GithubWorkStatusTokens {
    val IconSize = 23.dp
}

/** 列表与详情使用同一状态优先级，已关闭的草稿 PR 显示为关闭。 */
@Composable
fun githubWorkStatusLabel(
    kind: CollaborationKind,
    state: String?,
    draft: Boolean = false,
    merged: Boolean = false,
): String {
    val strings = LocalGithubStrings.current.Explore
    return when {
        kind == CollaborationKind.PullRequest && merged -> strings.Merged
        state.equals("closed", ignoreCase = true) -> strings.Closed
        kind == CollaborationKind.PullRequest && draft -> strings.Draft
        else -> strings.Open
    }
}

/** 议题、拉取请求和讨论共用的状态图标与 Primer 状态色。 */
@Composable
fun githubWorkStatusVisual(
    kind: CollaborationKind,
    state: String?,
    draft: Boolean = false,
    merged: Boolean = false,
    stateReason: String? = null,
): GithubWorkStatusVisual {
    val closed = state.equals("closed", ignoreCase = true)
    if (kind == CollaborationKind.PullRequest) {
        return when {
            merged -> GithubWorkStatusVisual(Lucide.GitMerge, GithubWorkColors.merged)
            closed -> GithubWorkStatusVisual(Lucide.GitPullRequestClosed, GithubWorkColors.closed)
            draft -> GithubWorkStatusVisual(Lucide.GitPullRequestDraft, GithubWorkColors.draft)
            else -> GithubWorkStatusVisual(Lucide.GitPullRequest, GithubWorkColors.open)
        }
    }
    return when {
        closed && stateReason.equals("NOT_PLANNED", ignoreCase = true) ->
            GithubWorkStatusVisual(Lucide.CircleSlash, GithubWorkColors.draft)
        closed -> GithubWorkStatusVisual(Lucide.CircleCheck, GithubWorkColors.merged)
        else -> GithubWorkStatusVisual(Lucide.CircleDot, GithubWorkColors.open)
    }
}

@Composable
fun GithubWorkStatusIcon(
    kind: CollaborationKind,
    state: String?,
    draft: Boolean = false,
    merged: Boolean = false,
    stateReason: String? = null,
    size: Dp = GithubWorkStatusTokens.IconSize,
) {
    val status = githubWorkStatusVisual(kind, state, draft, merged, stateReason)
    GithubIcon(status.icon, contentDescription = null, size = size, tint = status.color)
}

/** 列表与详情共用状态标签；只有详情页的标签有独立操作。 */
@Composable
fun GithubWorkStatusTag(
    item: CollaborationItemDto,
    kind: CollaborationKind,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val status = githubWorkStatusVisual(kind, item.state, item.draft, item.merged, item.stateReason)
    val label = githubWorkStatusLabel(kind, item.state, item.draft, item.merged)
    val content: @Composable () -> Unit = {
        GithubIcon(status.icon, contentDescription = null, size = 16.dp, tint = status.color)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = GlasmioTheme.textStyles.subheadline,
            color = status.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (onClick != null) {
        GlasmioButton(
            onClick = onClick,
            enabled = enabled,
            sampleBackdrop = false,
            size = GlasmioButtonSize.Small,
        ) {
            content()
        }
    } else {
        GlassSurface(
            modifier = Modifier.heightIn(min = GlasmioButtonDefaults.SmallHeight),
            shape = Capsule(),
            sampleBackdrop = false,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

/** 讨论只有打开／关闭两种状态，单独使用消息图标，避免和议题混淆。 */
@Composable
fun GithubDiscussionStatusIcon(closed: Boolean, size: Dp = 20.dp) {
    if (closed) {
        GithubIcon(
            Lucide.CircleCheck,
            contentDescription = null,
            size = size,
            tint = GithubWorkColors.merged,
        )
    } else {
        GithubIcon(
            Lucide.MessageSquare,
            contentDescription = null,
            size = size,
            tint = GithubWorkColors.open,
        )
    }
}
