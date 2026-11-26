package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubDetailPresentation
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady

private val FadeSpec = tween<Float>(PlaceholderTokens.TransitionMillis)
private val SizeSpec = tween<IntSize>(PlaceholderTokens.TransitionMillis)
private val ExitFadeSpec = tween<Float>(160)

/** 正文揭开时长与占位淡出时长，对齐 KernelSU MarkdownContent 的 300/150ms。 */
private val RevealMillis = 300
private val PlaceholderExitMillis = 150

/** 只替换卡片内部内容；缓存命中时直接显示，未知高度在同一个外壳内平滑变化。 */
@Composable
fun GithubLoadingContent(
    loading: Boolean,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val detail = LocalGithubDetailPresentation.current
    AnimatedContent(
        targetState = loading || !LocalGithubRouteEntryReady.current,
        modifier = modifier,
        transitionSpec = {
            if (detail != null && !detail.interactive) {
                (EnterTransition.None togetherWith ExitTransition.None).using(null)
            } else {
                (fadeIn(FadeSpec) togetherWith fadeOut(FadeSpec)).using(
                    SizeTransform { _, _ -> SizeSpec },
                )
            }
        },
        label = "loading-content",
    ) { pending ->
        if (pending) Box(Modifier.clearAndSetSemantics {}) { placeholder() } else content()
    }
}

@Composable
fun GithubLoadingCard(
    loading: Boolean,
    modifier: Modifier = Modifier,
    grouped: Boolean = true,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (grouped) {
        GlasmioGroupedList(modifier = modifier) {
            GithubLoadingContent(loading, placeholder = placeholder, content = content)
        }
    } else {
        GithubLoadingContent(
            loading = loading,
            modifier = modifier,
            placeholder = placeholder,
            content = content,
        )
    }
}

/** 保留同一个渲染器；覆盖占位不参与正文测量，适用于 WebView 首次布局。 */
@Composable
fun GithubLoadingOverlay(
    loading: Boolean,
    modifier: Modifier = Modifier,
    matchPlaceholderToContent: Boolean = true,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // 对齐 KernelSU GithubMarkdown：就绪时正文 300ms 淡入、占位 150ms 淡出，期间交叉过渡；
    // 重新进入等待时立即恢复抑制，缓存命中直接组合的正文不重播淡入。
    val contentAlpha = remember { Animatable(if (loading) 0f else 1f) }
    val placeholderAlpha = remember { Animatable(if (loading) 1f else 0f) }
    var wasLoading by remember { mutableStateOf(loading) }
    LaunchedEffect(loading) {
        if (loading) {
            contentAlpha.snapTo(0f)
            placeholderAlpha.snapTo(1f)
        } else if (wasLoading) {
            launch { contentAlpha.animateTo(1f, tween(RevealMillis)) }
            launch { placeholderAlpha.animateTo(0f, tween(PlaceholderExitMillis)) }
        }
        wasLoading = loading
    }
    Box(modifier) {
        Box(
            Modifier.graphicsLayer { alpha = contentAlpha.value }
                .then(
                    if (loading || placeholderAlpha.value > 0.01f) Modifier.clearAndSetSemantics {}
                    else Modifier,
                ),
        ) {
            content()
        }
        if (placeholderAlpha.value > 0.01f) {
            Box(
                (if (matchPlaceholderToContent) Modifier.matchParentSize() else Modifier)
                    .graphicsLayer { alpha = placeholderAlpha.value }
                    .clipToBounds()
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                                    it.consume()
                                }
                            }
                        }
                    },
            ) {
                placeholder()
            }
        }
    }
}

@Composable
fun GithubContentVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    animateEnter: Boolean = true,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter =
            if (animateEnter) fadeIn(FadeSpec) + expandVertically(SizeSpec)
            else EnterTransition.None,
        exit = fadeOut(ExitFadeSpec) + shrinkVertically(SizeSpec),
        label = "content-visibility",
        content = { content() },
    )
}
