package moe.yumeyuka.yumehub.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.EllipsisVertical
import io.github.yumeyucca.lucide.Languages
import io.github.yumeyucca.lucide.ListOrdered
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Pencil
import io.github.yumeyucca.lucide.Redo2
import io.github.yumeyucca.lucide.RemoveFormatting
import io.github.yumeyucca.lucide.Search
import io.github.yumeyucca.lucide.TextWrap
import io.github.yumeyucca.lucide.Undo2
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.CodeLanguages
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.ToolbarActions
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.navigation.EditorSessionKind
import moe.yumeyuka.yumehub.ui.navigation.EditorSession

@Composable
internal fun EditorTopBarActions(session: EditorSession, onSearch: () -> Unit) {
    val isCode = session.kind == EditorSessionKind.Code
    val showMenu = isCode || session.editable

    val strings = LocalAppStrings.current
    val menu = strings.EditorMenu
    var expanded by remember(session) { mutableStateOf(false) }
    val menuAnchor = rememberGlasmioMenuAnchorState()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val safeInsets = WindowInsets.safeDrawing.union(WindowInsets.ime)
    var windowScreenOrigin by remember { mutableStateOf<Offset?>(null) }
    val viewportMargin = with(density) { 12.dp.toPx() }
    val safeWindowViewport =
        Rect(
            left = safeInsets.getLeft(density, layoutDirection) + viewportMargin,
            top = safeInsets.getTop(density) + viewportMargin,
            right =
                windowSize.width - safeInsets.getRight(density, layoutDirection) - viewportMargin,
            bottom = windowSize.height - safeInsets.getBottom(density) - viewportMargin,
        )
    // 0.1.2 的父菜单按 window 坐标定位，子菜单却将纵向边界减去 screen 原点。
    // 两套纵向安全区取交集，使同一参数在两个分支中都不会扩大可视范围。
    val menuViewport = windowScreenOrigin?.let { origin ->
        val top = maxOf(safeWindowViewport.top, safeWindowViewport.top + origin.y)
        val bottom = minOf(safeWindowViewport.bottom, safeWindowViewport.bottom + origin.y)
        if (safeWindowViewport.width > 0f && bottom - top > viewportMargin * 2f) {
            Rect(safeWindowViewport.left, top, safeWindowViewport.right, bottom)
        } else {
            // 公共区间不足库内上下各 12dp 边距时，回退到库默认的子菜单限高。
            null
        }
    }
    val selectedLanguage = session.language.orEmpty().let { if (it == "plaintext") "" else it }
    val languages =
        remember(selectedLanguage) {
            (listOf("") + CodeLanguages + selectedLanguage)
                .filter { it != "plaintext" }
                .distinct()
        }
    val available = session.fileReadStatus == null && !session.submitting && !session.closeRequested
    val editable = session.editable && available
    // 级联菜单内部也会发出空 anchor 节点，不能让它成为顶栏 Row 的第三个子项。
    Box(
        Modifier.onGloballyPositioned { coordinates ->
            windowScreenOrigin =
                coordinates.localToScreen(Offset.Zero) - coordinates.localToWindow(Offset.Zero)
        },
    ) {
        ToolbarActions(
            if (showMenu || session.onRequestEdit != null)
                toolbarIcon(
                    if (session.editable) Lucide.Check else Lucide.Pencil,
                    if (session.editable)
                        session.submitLabel
                            ?: if (isCode) strings.Common.Save else strings.WorkDetail.Publish
                    else strings.Common.Edit,
                    enabled =
                        if (session.editable) editable && session.submitEnabled
                        else available && !session.readingOriginal && session.onRequestEdit != null,
                    key = "edit",
                    onClick = if (session.editable) session::submit else session::requestEdit,
                )
            else null,
            if (showMenu)
                toolbarIcon(
                    Lucide.EllipsisVertical,
                    menu.More,
                    available,
                    menuAnchor = menuAnchor,
                ) {
                    expanded = true
                }
            else null,
        )
        if (showMenu && expanded && available && windowScreenOrigin != null) {
            GlasmioCascadingMenu(
                anchorBounds =
                    menuAnchor.bounds?.translate(Offset(0f, with(density) { 12.dp.toPx() })),
                menuAnchorState = menuAnchor,
                title = menu.More,
                expandedDescription = menu.Expanded,
                collapsedDescription = menu.Collapsed,
                onDismiss = { expanded = false },
                forceBelowAnchor = true,
                viewportBounds = menuViewport,
                useAccentIcons = false,
                items =
                    buildList {
                        val history =
                            listOf(
                                GlasmioCascadingMenuItem(
                                    title = menu.Undo,
                                    icon = {
                                        AppIcon(
                                            Lucide.Undo2,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    enabled = editable && session.markdownState.canUndo,
                                    onClick = {
                                        session.markdownState.undo()
                                        session.changeSource(session.markdownState.value.text)
                                    },
                                ),
                                GlasmioCascadingMenuItem(
                                    title = menu.Redo,
                                    icon = {
                                        AppIcon(
                                            Lucide.Redo2,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    enabled = editable && session.markdownState.canRedo,
                                    onClick = {
                                        session.markdownState.redo()
                                        session.changeSource(session.markdownState.value.text)
                                    },
                                ),
                            )
                        if (!isCode) {
                            addAll(history)
                            return@buildList
                        }
                        add(
                            GlasmioCascadingMenuItem(
                                title = strings.Common.Search,
                                icon = {
                                    AppIcon(
                                        Lucide.Search,
                                        contentDescription = null,
                                        size = 20.dp,
                                    )
                                },
                                onClick = onSearch,
                            ),
                        )
                        add(
                            GlasmioCascadingMenuItem(
                                title = menu.WordWrap,
                                checked = session.wordWrap,
                                icon = {
                                    AppIcon(
                                        Lucide.TextWrap,
                                        contentDescription = null,
                                        size = 20.dp,
                                    )
                                },
                                separatorBefore = true,
                                onClick = { session.changeWordWrap(!session.wordWrap) },
                            ),
                        )
                        add(
                            GlasmioCascadingMenuItem(
                                title = menu.LineNumbers,
                                checked = session.lineNumbers,
                                icon = {
                                    AppIcon(
                                        Lucide.ListOrdered,
                                        contentDescription = null,
                                        size = 20.dp,
                                    )
                                },
                                onClick = { session.changeLineNumbers(!session.lineNumbers) },
                            ),
                        )
                        addAll(history)
                        add(
                            GlasmioCascadingMenuItem(
                                title = strings.Common.FontSize,
                                icon = {
                                    AppIcon(
                                        Lucide.RemoveFormatting,
                                        contentDescription = null,
                                        size = 20.dp,
                                    )
                                },
                                separatorBefore = true,
                                subtitle = session.fontSize.toString(),
                                children =
                                    (10..24 step 2).map { size ->
                                        GlasmioCascadingMenuItem(
                                            title = size.toString(),
                                            checked = size == session.fontSize,
                                            onClick = { session.changeFontSize(size) },
                                        )
                                    },
                            ),
                        )
                        add(
                            GlasmioCascadingMenuItem(
                                title = menu.Language,
                                icon = {
                                    AppIcon(
                                        Lucide.Languages,
                                        contentDescription = null,
                                        size = 20.dp,
                                    )
                                },
                                subtitle = selectedLanguage.ifBlank { menu.PlainText },
                                children =
                                    languages.map { language ->
                                        GlasmioCascadingMenuItem(
                                            title = language.ifBlank { menu.PlainText },
                                            checked = language == selectedLanguage,
                                            onClick = {
                                                session.changeLanguage(language.ifBlank { null })
                                            },
                                        )
                                    },
                            ),
                        )
                    },
            )
        }
    }
}
