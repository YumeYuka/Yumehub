package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem
import moe.yumeyuka.yumehub.ui.component.text.GlasmioTextSelectionHost
import moe.yumeyuka.yumehub.ui.component.text.GlasmioTextSelectionLabels
import moe.yumeyuka.yumehub.ui.component.text.LocalGlasmioTextEditingHost

private class SelectionActions {
    var owner by mutableStateOf<Any?>(null)
        private set

    var items by mutableStateOf(emptyList<GlasmioCascadingMenuItem>())
        private set

    fun activate(owner: Any, items: List<GlasmioCascadingMenuItem>) {
        this.owner = owner
        this.items = items
    }

    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            items = emptyList()
        }
    }
}

private val LocalSelectionActions = staticCompositionLocalOf<SelectionActions?> { null }

/** 窗口只安装一个 Glasmio 编辑宿主，同时接管旧、新两种文本菜单入口。 */
@Composable
fun TextSelectionHost(content: @Composable () -> Unit) {
    val registry = remember { SelectionActions() }
    CompositionLocalProvider(LocalSelectionActions provides registry) {
        GlasmioTextSelectionHost(
            labels =
                GlasmioTextSelectionLabels(
                    cut = "剪切",
                    copy = "复制",
                    paste = "粘贴",
                    selectAll = "全选",
                    autofill = "自动填充",
                    more = "更多",
                    expanded = "已展开",
                    collapsed = "已收起",
                ),
            additionalItems = registry.items,
            content = content,
        )
    }
}

/** 仅把当前聚焦输入框的动作交给窗口宿主，菜单回调始终使用最新的正文选区。 */
@Composable
fun rememberMarkdownSelectionModifier(
    state: MarkdownEditorState,
    enabled: Boolean,
    onSourceChange: (String) -> Unit,
): Modifier {
    val registry = LocalSelectionActions.current ?: return Modifier
    val owner = remember(state) { Any() }
    val editingHost = LocalGlasmioTextEditingHost.current
    val ownsActions = registry.owner === owner
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnSourceChange by rememberUpdatedState(onSourceChange)
    val hasSelection = !state.value.selection.collapsed
    val items =
        remember(state, enabled, hasSelection) {
            fun action(title: String, symbol: String, operation: MarkdownEditAction) =
                GlasmioCascadingMenuItem(
                    title = title,
                    systemName = symbol,
                    enabled = enabled && (hasSelection || operation in BlockActions),
                    onClick = {
                        if (latestEnabled && registry.owner === owner) {
                            state.apply(operation)
                            latestOnSourceChange(state.value.text)
                        }
                    },
                )
            listOf(
                GlasmioCascadingMenuItem(
                    title = "格式",
                    systemName = "textformat",
                    enabled = enabled,
                    children =
                        listOf(
                            action("加粗", "bold", MarkdownEditAction.Bold),
                            action("斜体", "italic", MarkdownEditAction.Italic),
                            action("删除线", "strikethrough", MarkdownEditAction.Strike),
                            action(
                                "行内代码",
                                "chevron.left.forwardslash.chevron.right",
                                MarkdownEditAction.InlineCode,
                            ),
                            action("链接", "link", MarkdownEditAction.Link),
                            action("引用", "text.quote", MarkdownEditAction.Quote),
                            action("标题", "number", MarkdownEditAction.Heading),
                            action("无序列表", "list.bullet", MarkdownEditAction.BulletedList),
                            action("有序列表", "list.number", MarkdownEditAction.NumberedList),
                            action("任务列表", "checklist", MarkdownEditAction.TaskList),
                            action("代码块", "curlybraces", MarkdownEditAction.CodeBlock),
                        ),
                ),
            )
        }
    SideEffect {
        if (ownsActions) {
            if (enabled) registry.activate(owner, items) else registry.clear(owner)
        }
    }
    DisposableEffect(registry, owner) { onDispose { registry.clear(owner) } }
    return Modifier.onFocusChanged { focus ->
        if (focus.isFocused && enabled) registry.activate(owner, items)
        // 级联菜单接管焦点时保留正文 owner，动作仍应用于 state 的当前选区。
        else if (!enabled || editingHost?.menuOwnsFocus != true) registry.clear(owner)
    }
}

private val BlockActions =
    setOf(
        MarkdownEditAction.Quote,
        MarkdownEditAction.Heading,
        MarkdownEditAction.BulletedList,
        MarkdownEditAction.NumberedList,
        MarkdownEditAction.TaskList,
        MarkdownEditAction.CodeBlock,
    )
