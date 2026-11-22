package moe.yumeyuka.yumehub.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.FaceSlightlySmiling
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.repository.GithubReactionContent
import moe.yumeyuka.yumehub.data.repository.GithubReactionGroup
import moe.yumeyuka.yumehub.data.repository.GithubReactionUpdates
import moe.yumeyuka.yumehub.locale.GithubStrings_Activity
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

private const val ReactionFadeMillis = 110

@Composable
fun GithubReactionBar(
    subjectKey: String,
    sourceReactions: List<GithubReactionGroup>,
    canReact: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues =
        PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 12.dp),
    updates: GithubReactionUpdates,
    onToggle: (GithubReactionContent, Boolean) -> Unit,
) {
    val update = updates.observeReaction(subjectKey, sourceReactions)
    val reactions = update?.reactions ?: sourceReactions
    val interactionEnabled = enabled && update?.pending != true
    if (!canReact && reactions.isEmpty()) return
    val strings = LocalGithubStrings.current
    val colors = GlasmioTheme.colors
    var pickerVisible by remember(subjectKey) { mutableStateOf(false) }

    DisableSelection {
        Row(
            modifier.fillMaxWidth().padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canReact) {
                GlasmioIconButton(
                    onClick = { pickerVisible = true },
                    enabled = interactionEnabled,
                    size = 36.dp,
                    navigationSurfaceColor = colors.groupedBackground,
                    navigationSurfaceAlphaMultiplier = 1f,
                    sampleBackdrop = false,
                ) {
                    GithubIcon(
                        Lucide.FaceSlightlySmiling,
                        contentDescription = strings.Activity.AddReaction,
                        size = 22.dp,
                        tint = colors.secondaryContent,
                    )
                }
            }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GithubReactionContent.entries.forEach { content ->
                    key(subjectKey, content) {
                        val reaction = reactions.firstOrNull {
                            it.content == content && it.count > 0
                        }
                        val visible = remember { MutableTransitionState(reaction != null) }
                        var displayed by remember { mutableStateOf(reaction) }
                        SideEffect {
                            visible.targetState = reaction != null
                            if (reaction != null) displayed = reaction
                        }
                        AnimatedVisibility(
                            visibleState = visible,
                            enter = fadeIn(tween(ReactionFadeMillis)),
                            exit = fadeOut(tween(ReactionFadeMillis)),
                        ) {
                            (reaction ?: displayed)?.let { current ->
                                ReactionButton(
                                    selected = current.viewerReacted,
                                    contentDescription =
                                        strings.Activity.ReactionCount(
                                            content.label(strings.Activity),
                                            current.count,
                                        ),
                                    onClick = { onToggle(content, current.viewerReacted) },
                                    enabled = interactionEnabled && canReact && reaction != null,
                                ) {
                                    Text(content.emoji, maxLines = 1)
                                    Crossfade(
                                        targetState = current.count,
                                        animationSpec = tween(ReactionFadeMillis),
                                        label = "Reaction count",
                                    ) {
                                        Text(it.toString(), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val dismiss = { pickerVisible = false }
    GlasmioAlertDialog(
        visible = pickerVisible && canReact,
        onDismissRequest = dismiss,
        title = strings.Activity.ChooseReaction,
    ) {
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GithubReactionContent.Standard.forEach { content ->
                val reacted = reactions.any { it.content == content && it.viewerReacted }
                ReactionButton(
                    selected = reacted,
                    contentDescription = content.label(strings.Activity),
                    enabled = canReact && interactionEnabled && pickerVisible,
                    onClick = {
                        dismiss()
                        onToggle(content, reacted)
                    },
                ) {
                    Text(content.emoji, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ReactionButton(
    selected: Boolean,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    GlasmioButton(
        onClick = onClick,
        size = GlasmioButtonSize.Small,
        emphasis = if (selected) GlassEmphasis.Prominent else GlassEmphasis.Regular,
        enabled = enabled,
        sampleBackdrop = false,
        modifier =
            Modifier.semantics {
                this.selected = selected
                this.contentDescription = contentDescription
            },
        content = content,
    )
}

private fun GithubReactionContent.label(strings: GithubStrings_Activity): String =
    when (this) {
        GithubReactionContent.THUMBS_UP -> strings.ReactionLike
        GithubReactionContent.THUMBS_DOWN -> strings.ReactionDislike
        GithubReactionContent.LAUGH -> strings.ReactionLaugh
        GithubReactionContent.HOORAY -> strings.ReactionCelebrate
        GithubReactionContent.CONFUSED -> strings.ReactionConfused
        GithubReactionContent.HEART -> strings.ReactionLove
        GithubReactionContent.ROCKET -> strings.ReactionRocket
        GithubReactionContent.EYES -> strings.ReactionEyes
    }
