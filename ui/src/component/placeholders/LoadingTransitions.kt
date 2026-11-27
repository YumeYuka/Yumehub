package moe.yumeyuka.yumehub.ui.component.placeholders

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import kotlin.math.roundToInt
import moe.yumeyuka.yumehub.ui.component.LocalReadingReceipt
import moe.yumeyuka.yumehub.ui.component.skeleton.SkeletonSwap
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady

// Apple UnitCurve curves: easeOut (0, 0, 0.58, 1) and easeIn (0.42, 0, 1, 1).
private val IosEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val IosEaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

internal val GithubCardEnterSpec = tween<Float>(360, easing = IosEaseOut)
internal val GithubCardExitSpec = tween<Float>(200, easing = IosEaseIn)
val GithubCardSizeSpec =
    spring<IntSize>(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = IntSize(1, 1))
private val CardContentEnterSpec = tween<Float>(300, easing = IosEaseOut)
private val CardContentExitSpec = tween<Float>(200, delayMillis = 60, easing = IosEaseIn)

/** 卡片只在持续加载时显示骨架。正文就绪即交接；前进期间只组合占位，不提前挂载重内容。 */
@Composable
fun GithubLoadingContent(
    loading: Boolean,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    SkeletonSwap(
        loading = loading,
        modifier = modifier,
        contentPending = loading || !LocalGithubRouteEntryReady.current,
        placeholder = placeholder,
        content = content,
    )
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

/** Owned by the bounded route document cache. No pixels, View or Compose state are retained. */
class GithubDocumentGeometry {
    private var environment: Any? = null
    private var width = 0
    private var height = 0

    internal fun heightFor(width: Int, environment: Any): Int =
        if (this.width == width && this.environment == environment) height else 0

    internal fun drawn(width: Int, height: Int, environment: Any) {
        this.width = width
        this.height = height
        this.environment = environment
    }
}

/** 文档正文先挂载，加载期间按缓存或骨架高度占位。渲染器可画后，正文与槽高同一拍交接。 */
@Composable
fun GithubLoadingOverlay(
    loading: Boolean,
    documentKey: Any,
    modifier: Modifier = Modifier,
    geometry: GithubDocumentGeometry? = null,
    layoutKey: Any? = null,
    layoutWidth: Int,
    visible: Boolean = true,
    onPresented: (() -> Unit)? = null,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val receipt = LocalReadingReceipt.current
    val owner = remember(documentKey, loading) { Any() }
    DisposableEffect(receipt, owner) {
        onDispose { if (receipt != null && receipt.drawnBy === owner) receipt.drawnBy = null }
    }
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val font = GlasmioTheme.typography.bodyLarge
    val environment =
        remember(config, density, direction, font, layoutKey) {
            listOf(Configuration(config), density, direction, font, layoutKey)
        }
    val active = LocalGithubPageHost.current?.isCurrent != false
    val width = layoutWidth
    val restored =
        remember(documentKey, geometry, width, environment) {
            geometry?.heightFor(width, environment) ?: 0
        }
    var presented by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(owner, presented) { if (presented) onPresented?.invoke() }
    SkeletonSwap(
        loading = loading,
        modifier = modifier,
        contentPending = false,
        reservedHeight = restored,
        placeholder = placeholder,
    ) {
        Box(
            Modifier.then(
                    if (loading || !visible) Modifier.clearAndSetSemantics {} else Modifier,
                )
                .drawWithContent {
                    if (!loading) {
                        drawContent()
                        if (visible && size.width > 0f && size.height > 0f) {
                            if (active) {
                                geometry?.drawn(width, size.height.roundToInt(), environment)
                                if (!presented) presented = true
                            }
                            if (receipt != null && receipt.drawnBy !== owner)
                                receipt.drawnBy = owner
                        }
                    }
                },
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}

/** Only presence changes animate; a cached field is composed at full size on its first frame. */
@Composable
fun <T : Any> GithubCardField(value: T?, content: @Composable (T) -> Unit) {
    val animate = LocalGithubCardMotionEnabled.current
    AnimatedVisibility(
        visible = value != null,
        enter =
            if (animate)
                fadeIn(CardContentEnterSpec) + expandVertically(GithubCardSizeSpec, Alignment.Top)
            else EnterTransition.None,
        exit =
            if (animate)
                fadeOut(CardContentExitSpec) + shrinkVertically(GithubCardSizeSpec, Alignment.Top)
            else ExitTransition.None,
        label = "card-field",
    ) {
        // Keep the outgoing value for its fade; forget it when the field leaves composition.
        var last by remember { mutableStateOf(value) }
        if (value != null && value != last) SideEffect { last = value }
        (value ?: last)?.let { content(it) }
    }
}
