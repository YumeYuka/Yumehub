package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.Group
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.Path
import androidx.compose.ui.graphics.vector.VectorComposable
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.LocalGroupedListIconColor
import io.github.neoruaa.glasmio.icons.GlasmioIconResolver
import io.github.neoruaa.glasmio.icons.MaterialGlasmioIconResolver
import io.github.yumeyucca.lucide.*

@Composable
fun GithubIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 23.dp,
    tint: Color = LocalGroupedListIconColor.current ?: GlasmioTheme.colors.content,
    filled: Boolean = false,
) {
    if (filled) {
        val painter =
            rememberVectorPainter(
                defaultWidth = imageVector.defaultWidth,
                defaultHeight = imageVector.defaultHeight,
                viewportWidth = imageVector.viewportWidth,
                viewportHeight = imageVector.viewportHeight,
                autoMirror = imageVector.autoMirror,
            ) { _, _ ->
                FilledLucideGroup(imageVector.root, tint)
            }
        Icon(painter, contentDescription, modifier.size(size), tint)
    } else Icon(imageVector, contentDescription, modifier.size(size), tint)
}

/** 复用 Lucide 原始路径，仅改变填充画刷，不定义或修改图标几何。 */
@Composable
@VectorComposable
private fun FilledLucideGroup(group: VectorGroup, tint: Color) {
    Group(
        rotation = group.rotation,
        pivotX = group.pivotX,
        pivotY = group.pivotY,
        scaleX = group.scaleX,
        scaleY = group.scaleY,
        translationX = group.translationX,
        translationY = group.translationY,
        clipPathData = group.clipPathData,
    ) {
        for (node in group) {
            when (node) {
                is VectorGroup -> FilledLucideGroup(node, tint)
                is VectorPath ->
                    Path(
                        pathData = node.pathData,
                        pathFillType = node.pathFillType,
                        fill = SolidColor(tint),
                        stroke = node.stroke?.let { SolidColor(tint) },
                        strokeLineWidth = node.strokeLineWidth,
                        strokeLineCap = node.strokeLineCap,
                        strokeLineJoin = node.strokeLineJoin,
                        strokeLineMiter = node.strokeLineMiter,
                    )
            }
        }
    }
}

// 来源：YumeYucca/YumeBox（Moe）ui/src/presentation/icon/yume/Github.kt，AGPL-3.0-or-later。
// Copyright (c) YumeYucca 2025 - Present。
val GithubBrandIcon: ImageVector by lazy {
    ImageVector.Builder(
            name = "Github",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15f, 22f)
                verticalLineToRelative(-4f)
                arcToRelative(4.8f, 4.8f, 0f, false, false, -1f, -3.5f)
                curveToRelative(3f, 0f, 6f, -2f, 6f, -5.5f)
                curveToRelative(0.08f, -1.25f, -0.27f, -2.48f, -1f, -3.5f)
                curveToRelative(0.28f, -1.15f, 0.28f, -2.35f, 0f, -3.5f)
                curveToRelative(0f, 0f, -1f, 0f, -3f, 1.5f)
                curveToRelative(-2.64f, -0.5f, -5.36f, -0.5f, -8f, 0f)
                curveTo(6f, 2f, 5f, 2f, 5f, 2f)
                curveToRelative(-0.3f, 1.15f, -0.3f, 2.35f, 0f, 3.5f)
                arcTo(5.403f, 5.403f, 0f, false, false, 4f, 9f)
                curveToRelative(0f, 3.5f, 3f, 5.5f, 6f, 5.5f)
                curveToRelative(-0.39f, 0.49f, -0.68f, 1.05f, -0.85f, 1.65f)
                curveToRelative(-0.17f, 0.6f, -0.22f, 1.23f, -0.15f, 1.85f)
                verticalLineToRelative(4f)
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 18f)
                curveToRelative(-4.51f, 2f, -5f, -2f, -7f, -2f)
            }
        }
        .build()
}

object GithubIconResolver : GlasmioIconResolver {
    override fun resolve(systemName: String): ImageVector? =
        when (systemName) {
            "textformat" -> Lucide.CaseSensitive
            "bold" -> Lucide.Bold
            "italic" -> Lucide.Italic
            "strikethrough" -> Lucide.Strikethrough
            "chevron.left.forwardslash.chevron.right" -> Lucide.Code
            "link" -> Lucide.Link
            "text.quote" -> Lucide.Quote
            "number" -> Lucide.Heading
            "list.bullet" -> Lucide.List
            "list.number" -> Lucide.ListOrdered
            "checklist" -> Lucide.ListChecks
            "curlybraces" -> Lucide.SquareCode
            "chevron.up.chevron.down" -> Lucide.ChevronsUpDown
            "chevron.up" -> Lucide.ChevronUp
            "chevron.down" -> Lucide.ChevronDown
            "chevron.left",
            "chevron.backward" -> Lucide.ChevronLeft
            "chevron.right",
            "chevron.forward" -> Lucide.ChevronRight
            "circle.lefthalf.filled" -> Lucide.Contrast
            "sun.max",
            "sun.max.fill" -> Lucide.Sun
            "moon",
            "moon.fill" -> Lucide.Moon
            "paintpalette",
            "paintpalette.fill" -> Lucide.Palette
            "paintbrush" -> Lucide.Paintbrush
            "eyedropper" -> Lucide.Pipette
            "envelope.open" -> Lucide.MailOpen
            "info.circle" -> Lucide.Info
            "checkmark" -> Lucide.Check
            "xmark" -> Lucide.X
            "xmark.circle" -> Lucide.CircleX
            "magnifyingglass" -> Lucide.Search
            else -> MaterialGlasmioIconResolver.resolve(systemName)
        }
}
