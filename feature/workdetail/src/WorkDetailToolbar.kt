package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.canMerge
import moe.yumeyuka.yumehub.locale.GithubStrings_WorkDetail
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors

internal fun CollaborationSection.label(strings: GithubStrings_WorkDetail): String =
    when (this) {
        CollaborationSection.Discussion -> strings.Discussion
        CollaborationSection.Reviews -> strings.Reviews
        CollaborationSection.Files -> strings.Files
        CollaborationSection.Commits -> strings.Commits
    }

@Composable
internal fun WorkDetailToolbar(
    state: CollaborationDetailUiState,
    kind: CollaborationKind,
    onMerge: () -> Unit,
    onComment: () -> Unit,
    onSubscription: () -> Unit,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    val motionEasing = Easing { fraction ->
        val remaining = 1f - fraction
        1f - remaining * remaining * remaining * remaining * remaining
    }
    val enabled = !state.submitting || state.sendingInBackground
    val canCompose =
        !state.sendingInBackground &&
            !state.loading &&
            !state.submissionUncertain &&
            (state.canComment || state.commentDraft.isNotEmpty())
    val subscribed = state.desiredSubscription ?: (state.subscription?.subscribed == true)
    val showMerge = kind == CollaborationKind.PullRequest && state.item?.state == "open"
    val canMerge =
        state.item?.canMerge() == true &&
            enabled &&
            !state.loading &&
            !state.submitting &&
            !state.submissionUncertain
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlasmioIconButton(
            onClick = onSubscription,
            enabled = !state.item?.nodeId.isNullOrBlank() && state.subscription?.canSubscribe != false,
            size = 48.dp,
        ) {
            GithubIcon(
                if (subscribed) Lucide.BellOff else Lucide.Bell,
                contentDescription =
                    when {
                        state.subscriptionError != null && !state.subscriptionUpdating ->
                            strings.SubscriptionFailed
                        subscribed -> strings.Unsubscribe
                        else -> strings.Subscribe
                    },
            )
        }
        AnimatedContent(
            targetState = showMerge,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(320, easing = motionEasing)) togetherWith
                        fadeOut(tween(320, easing = motionEasing)))
                    .using(SizeTransform(clip = false))
            },
            label = "work-bottom-action",
        ) { merging ->
            val colors = LocalGlassColors.current
            CompositionLocalProvider(
                LocalGlassColors provides
                    if (merging) colors.copy(prominentContainer = GithubWorkColors.open)
                    else colors,
            ) {
                GlasmioButton(
                    onClick = if (merging) onMerge else onComment,
                    enabled = if (merging) canMerge else enabled && canCompose,
                    emphasis = if (merging) GlassEmphasis.Prominent else GlassEmphasis.Regular,
                    navigationSurfaceColor = if (merging) GithubWorkColors.open else null,
                    sampleBackdrop = true,
                    style = GlassSurfaceStyle.Navigation,
                    size = GlasmioButtonSize.Large,
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(44.dp)
                            .then(
                                if (merging)
                                    Modifier.border(
                                        width = 3.dp,
                                        color =
                                            GithubWorkColors.onStatus.copy(
                                                alpha = if (canMerge) 0.85f else 0.45f,
                                            ),
                                        shape = RoundedCornerShape(50),
                                    )
                                else Modifier,
                            ),
                ) {
                    Text(
                        if (merging) strings.Merge else strings.Comment,
                        style = GlasmioTheme.textStyles.body,
                        color =
                            if (merging && canMerge) GithubWorkColors.onStatus
                            else GlasmioTheme.colors.content,
                    )
                }
            }
        }
        if (showMerge) {
            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                GlasmioIconButton(
                    onClick = onComment,
                    enabled = enabled && canCompose,
                    size = 48.dp,
                ) {
                    GithubIcon(Lucide.MessageSquare, contentDescription = strings.Comment)
                }
            }
        }
    }
}
