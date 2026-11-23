// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// 基于 Miuix ea1b935d TopAppBar.kt；保留当前标题和操作槽，定制大标题样式及测量间距。
package moe.yumeyuka.yumehub.ui.navigation

import android.icu.text.BreakIterator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlin.math.abs
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.ui.component.placeholders.DetailTitlePlaceholder
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val GithubLargeTitleTopPadding = 16.dp
private const val TITLE_WIDTH_FRACTION = 0.9

@Composable
internal fun GithubMiuixTopAppBarLayout(
    title: String,
    titleColor: Color,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    titlePadding: Dp,
    navigationIconPadding: Dp,
    actionIconPadding: Dp,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    largeTitleSubtitle: String? = null,
    largeTitleMaxLines: Int = 1,
    largeTitleStyle: TextStyle? = null,
    largeTitleSuffix: String? = null,
    largeTitleLeading: (@Composable () -> Unit)? = null,
    /** 渲染在大标题上方的徽标行；与大标题同列，标题自然下移让出空间。 */
    largeTitleEyebrow: (@Composable () -> Unit)? = null,
    /** 挂在大标题行尾（右对齐）的控件，与上方徽标同一视觉行起点。 */
    largeTitleTrailing: (@Composable () -> Unit)? = null,
    largeTitleFooter: (@Composable () -> Unit)? = null,
    largeTitleOnClick: (() -> Unit)? = null,
    largeTitleBlurRadius: Dp = 0.dp,
) {
    val detailPresentation = LocalGithubDetailPresentation.current?.chromeState
    val titleClip = remember { TopAppBarTitleClip() }
    // Producer lambdas — reads stay in layout/draw phases so scroll never recomposes this subtree.
    val scrolledOffset = remember(scrollBehavior) { { scrollBehavior?.state?.heightOffset ?: 0f } }
    // The title does not merely fade out — it goes out of focus on the way, the way the source
    // system dissolves it. `progress` is shared by both so the blur peaks exactly as the last of
    // the title goes, instead of stopping short of it or outliving it.
    val largeTitleFade =
        remember(scrollBehavior) {
            {
                val frac = scrollBehavior?.state?.collapsedFraction ?: 0f
                (frac * 3f).coerceIn(0f, 1f)
            }
        }
    val largeTitleAlpha = remember(largeTitleFade) { { 1f - largeTitleFade() } }
    val updateHeightOffsetLimit =
        remember(scrollBehavior) {
            { height: Int ->
                scrollBehavior?.state?.let { state ->
                    val limit = -height.toFloat()
                    if (state.heightOffsetLimit != limit) {
                        state.heightOffsetLimit = limit
                    }
                }
                Unit
            }
        }

    // Boolean derivedStateOf invalidates only on flip → the spring fires once per crossing, not per
    // frame.
    val smallTitleVisible by
        remember(scrollBehavior) {
            derivedStateOf {
                scrollBehavior?.state?.let { state -> state.collapsedFraction * 3f >= 1f } ?: false
            }
        }
    val smallTitleAlpha = remember { Animatable(if (smallTitleVisible) 1f else 0f) }
    val smallTitleTranslationY = remember { Animatable(if (smallTitleVisible) 0f else 20f) }

    LaunchedEffect(smallTitleVisible) {
        if (smallTitleVisible) {
            val showSpec = folmeSpring<Float>(damping = 1.0f, response = 0.3f)
            launch { smallTitleAlpha.animateTo(1f, showSpec) }
            launch { smallTitleTranslationY.animateTo(0f, showSpec) }
        } else {
            val hideSpec = folmeSpring<Float>(damping = 1.0f, response = 0.15f)
            launch { smallTitleAlpha.animateTo(0f, hideSpec) }
            launch { smallTitleTranslationY.animateTo(20f, hideSpec) }
        }
    }

    val animatedTitleColor by
        animateColorAsState(
            targetValue = titleColor,
            animationSpec = tween(durationMillis = 50),
        )

    Layout(
        {
            Box(
                Modifier.layoutId("navigationIcon").padding(start = navigationIconPadding),
            ) {
                navigationIcon()
            }
            Box(
                Modifier.layoutId("title").graphicsLayer {
                    alpha = smallTitleAlpha.value
                    translationY = smallTitleTranslationY.value
                },
            ) {
                GithubDetailRegion(detailPresentation, { DetailTitlePlaceholder(compact = true) }) {
                    Text(
                        text = title,
                        color = animatedTitleColor,
                        style =
                            MiuixTheme.textStyles.title3.copy(
                                fontFamily = GlasmioTheme.typography.bodyLarge.fontFamily,
                            ),
                        fontSize = MiuixTheme.textStyles.title3.fontSize,
                        fontWeight = FontWeight.Medium,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier =
                            Modifier.clipTopAppBarTitle(titleClip) { titleClip.titleOrigin }
                                .padding(horizontal = titlePadding),
                    )
                }
            }
            Box(
                Modifier.layoutId("actionIcons").padding(end = actionIconPadding),
            ) {
                actions()
            }
            ExpandedTitleBlock(
                modifier =
                    Modifier.layoutId("largeTitle")
                        .clipTopAppBarTitle(titleClip) { titleClip.largeTitleOrigin }
                        .padding(top = TopAppBarDefaults.CollapsedHeight)
                        .graphicsLayer {
                            alpha = largeTitleAlpha()
                            // Assigned on every path, including the disabled one: the layer keeps
                            // whatever it was last given, so a radius that drops back to zero would
                            // otherwise leave the last blur on the title for good.
                            val radius =
                                if (largeTitleBlurRadius > 0.dp) {
                                    largeTitleBlurRadius.toPx() * largeTitleFade()
                                } else {
                                    0f
                                }
                            renderEffect =
                                if (radius > 0.1f) {
                                    BlurEffect(radius, radius, TileMode.Decal)
                                } else {
                                    null
                                }
                        }
                        .offset {
                            val v = scrolledOffset()
                            IntOffset(0, v.fastRoundToInt())
                        }
                        .onSizeChanged { updateHeightOffsetLimit(it.height) },
                titlePadding = titlePadding,
                onClick = if (smallTitleVisible) null else largeTitleOnClick,
                footer = largeTitleFooter,
            ) {
                largeTitleLeading?.invoke()
                Column(
                    // 始终占满扣除前后槽位后的宽度：长标题只省略，不挤压行尾控件。
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    largeTitleEyebrow?.invoke()
                    GithubDetailRegion(
                        detailPresentation,
                        { DetailTitlePlaceholder() },
                        Modifier.fillMaxWidth(),
                    ) {
                        val baseTitleStyle =
                            largeTitleStyle ?: GlasmioTheme.typography.headlineLarge
                        if (largeTitleStyle != null && largeTitleMaxLines > 1) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val measurer = rememberTextMeasurer()
                                val density = LocalDensity.current
                                val width = with(density) { maxWidth.roundToPx() }
                                val singleLineIncrease = if (largeTitleSuffix != null) 4 else 2
                                val largerStyle =
                                    baseTitleStyle.copy(
                                        fontSize =
                                            (baseTitleStyle.fontSize.value + singleLineIncrease).sp,
                                        lineHeight =
                                            (baseTitleStyle.lineHeight.value + singleLineIncrease)
                                                .sp,
                                    )
                                val measurement =
                                    measurer.measure(
                                        text = largeTitle,
                                        style = baseTitleStyle,
                                        constraints = Constraints(maxWidth = width),
                                    )
                                val titleStyle =
                                    if (
                                        measurement.lineCount == 1 && !measurement.hasVisualOverflow
                                    )
                                        largerStyle
                                    else baseTitleStyle
                                val suffixColor = GlasmioTheme.colors.secondaryContent
                                val displayTitle =
                                    remember(
                                        largeTitle,
                                        largeTitleSuffix,
                                        suffixColor,
                                        titleStyle,
                                        largeTitleMaxLines,
                                        width,
                                        measurer,
                                    ) {
                                        largeTitleSuffix?.let { suffix ->
                                            ellipsizeTitleWithSuffix(
                                                title = largeTitle,
                                                suffix = suffix,
                                                suffixStyle =
                                                    SpanStyle(
                                                        color = suffixColor,
                                                        fontWeight = FontWeight.Normal,
                                                    ),
                                                style = titleStyle,
                                                maxLines = largeTitleMaxLines,
                                                width = width,
                                                measurer = measurer,
                                            )
                                        } ?: AnnotatedString(largeTitle)
                                    }
                                MaterialText(
                                    text = displayTitle,
                                    color = animatedTitleColor,
                                    style = titleStyle,
                                    maxLines = largeTitleMaxLines,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else if (largeTitleStyle != null && largeTitleMaxLines == 1) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val measurer = rememberTextMeasurer()
                                val density = LocalDensity.current
                                val width = with(density) { maxWidth.roundToPx() }
                                val titleStyle =
                                    listOf(0, 2, 4)
                                        .map { reduction ->
                                            baseTitleStyle.copy(
                                                fontSize =
                                                    (baseTitleStyle.fontSize.value - reduction).sp,
                                                lineHeight =
                                                    (baseTitleStyle.lineHeight.value - reduction)
                                                        .sp,
                                            )
                                        }
                                        .let { styles ->
                                            styles.firstOrNull { style ->
                                                val measurement =
                                                    measurer.measure(
                                                        text = largeTitle,
                                                        style = style,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        constraints = Constraints(maxWidth = width),
                                                    )
                                                !measurement.hasVisualOverflow
                                            } ?: styles.last()
                                        }
                                Text(
                                    text = largeTitle,
                                    color = animatedTitleColor,
                                    style = titleStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Text(
                                text = largeTitle,
                                color = animatedTitleColor,
                                style = baseTitleStyle,
                                maxLines = largeTitleMaxLines,
                                softWrap = largeTitleMaxLines > 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    largeTitleSubtitle?.let {
                        Text(
                            text = it,
                            color = GlasmioTheme.colors.secondaryContent,
                            style = GlasmioTheme.textStyles.subheadline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                largeTitleTrailing?.invoke()
            }
        },
        modifier =
            modifier
                // Titles retain the viewport clip; controls can cast shadows beyond the bar.
                .onSizeChanged { titleClip.viewportSize = it }
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .pointerInput(Unit) { detectTapGestures { /* Consume click */ } },
    ) { measurables, constraints ->
        val navigationIconPlaceable =
            measurables
                .fastFirst { it.layoutId == "navigationIcon" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val actionIconsPlaceable =
            measurables
                .fastFirst { it.layoutId == "actionIcons" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val maxTitleWidth =
            if (constraints.maxWidth == Constraints.Infinity) {
                constraints.maxWidth
            } else {
                (constraints.maxWidth - navigationIconPlaceable.width - actionIconsPlaceable.width)
                    .coerceAtLeast(0)
            }
        val titleMaxWidth =
            if (maxTitleWidth == Constraints.Infinity) {
                maxTitleWidth
            } else {
                (maxTitleWidth * TITLE_WIDTH_FRACTION).fastRoundToInt()
            }

        val titlePlaceable =
            measurables
                .fastFirst { it.layoutId == "title" }
                .measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val largeTitlePlaceable =
            measurables
                .fastFirst { it.layoutId == "largeTitle" }
                .measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    ),
                )

        val collapsedHeight = TopAppBarDefaults.CollapsedHeight.roundToPx()
        // 大标题修饰符包含折叠栏高度，扣除后才是可折叠区域。
        val expansion = (largeTitlePlaceable.height - collapsedHeight).coerceAtLeast(0)
        val barHeight =
            if (expansion > 0) {
                val offset = scrolledOffset()
                val collapseFraction =
                    if (offset.isNaN()) {
                        0f
                    } else {
                        (abs(offset) / expansion.toFloat()).coerceIn(0f, 1f)
                    }
                lerp(
                    start = collapsedHeight,
                    stop = collapsedHeight + expansion,
                    fraction = 1f - collapseFraction,
                )
            } else {
                collapsedHeight
            }

        val verticalCenter = collapsedHeight / 2
        val smallTitleBottom = verticalCenter + titlePlaceable.height / 2
        val layoutHeight =
            maxOf(barHeight, smallTitleBottom) +
                TopAppBarDefaults.LargeTitleBottomPadding.roundToPx()
        titleClip.contentHeight = layoutHeight

        layout(constraints.maxWidth, layoutHeight) {
            // Navigation icon
            navigationIconPlaceable.placeRelative(
                x = 0,
                y = verticalCenter - navigationIconPlaceable.height / 2,
            )

            // Title
            var baseX = (constraints.maxWidth - titlePlaceable.width) / 2
            if (baseX < navigationIconPlaceable.width) {
                baseX += (navigationIconPlaceable.width - baseX)
            } else if (
                baseX + titlePlaceable.width > constraints.maxWidth - actionIconsPlaceable.width
            ) {
                baseX +=
                    ((constraints.maxWidth - actionIconsPlaceable.width) -
                        (baseX + titlePlaceable.width))
            }
            val titleY = verticalCenter - titlePlaceable.height / 2
            titleClip.titleOrigin =
                IntOffset(
                    if (layoutDirection == LayoutDirection.Ltr) baseX
                    else constraints.maxWidth - baseX - titlePlaceable.width,
                    titleY,
                )
            titlePlaceable.placeRelative(x = baseX, y = titleY)

            // Action icons
            actionIconsPlaceable.placeRelative(
                x = constraints.maxWidth - actionIconsPlaceable.width,
                y = verticalCenter - actionIconsPlaceable.height / 2,
            )

            // Large title
            titleClip.largeTitleOrigin =
                IntOffset(
                    if (layoutDirection == LayoutDirection.Ltr) 0
                    else constraints.maxWidth - largeTitlePlaceable.width,
                    0,
                )
            largeTitlePlaceable.placeRelative(x = 0, y = 0)
        }
    }
}

@Composable
private fun ExpandedTitleBlock(
    modifier: Modifier,
    titlePadding: Dp,
    onClick: (() -> Unit)?,
    footer: (@Composable () -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
            )
            .padding(horizontal = titlePadding)
            .padding(top = GithubLargeTitleTopPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        footer?.invoke()
    }
}

/** 按真实排版缩短标题，后缀始终参与测量；字符边界避免截断组合字符。 */
private fun ellipsizeTitleWithSuffix(
    title: String,
    suffix: String,
    suffixStyle: SpanStyle,
    style: TextStyle,
    maxLines: Int,
    width: Int,
    measurer: TextMeasurer,
): AnnotatedString {
    fun candidate(end: Int, ellipsized: Boolean) = buildAnnotatedString {
        append(title.substring(0, end).trimEnd())
        if (ellipsized) append('\u2026')
        withStyle(suffixStyle) {
            append('\u00a0')
            append(suffix)
        }
    }
    fun fits(text: AnnotatedString): Boolean =
        !measurer
            .measure(
                text = text,
                style = style,
                maxLines = maxLines,
                constraints = Constraints(maxWidth = width),
            )
            .hasVisualOverflow

    val fullTitle = candidate(title.length, false)
    if (fits(fullTitle)) return fullTitle
    val boundaries = buildList {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(title)
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            add(boundary)
            boundary = iterator.next()
        }
    }
    var low = 0
    var high = boundaries.lastIndex
    var best = candidate(0, true)
    while (low <= high) {
        val middle = (low + high) / 2
        val text = candidate(boundaries[middle], true)
        if (fits(text)) {
            best = text
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return best
}

/** Viewport geometry published by layout and read only while drawing the title layers. */
private class TopAppBarTitleClip {
    var viewportSize: IntSize by mutableStateOf(IntSize.Zero)
    var contentHeight: Int by mutableIntStateOf(0)
    var titleOrigin: IntOffset by mutableStateOf(IntOffset.Zero)
    var largeTitleOrigin: IntOffset by mutableStateOf(IntOffset.Zero)
}

/** Keep the original bar clip around transformed titles without clipping control shadows. */
private fun Modifier.clipTopAppBarTitle(
    clip: TopAppBarTitleClip,
    origin: () -> IntOffset,
): Modifier = drawWithContent {
    val position = origin()
    val topInset = clip.viewportSize.height - clip.contentHeight
    clipRect(
        left = -position.x.toFloat(),
        top = -position.y.toFloat() - topInset,
        right = clip.viewportSize.width - position.x.toFloat(),
        bottom = clip.contentHeight - position.y.toFloat(),
    ) {
        this@drawWithContent.drawContent()
    }
}
