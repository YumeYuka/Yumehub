package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.glass.glasmioMenuAnchor
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.yumeyucca.lucide.ArrowDownUp
import io.github.yumeyucca.lucide.CircleDot
import io.github.yumeyucca.lucide.GitPullRequestDraft
import io.github.yumeyucca.lucide.ListFilter
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.MessageCircleCheck
import io.github.yumeyucca.lucide.MessageSquare
import io.github.yumeyucca.lucide.MessagesSquare
import io.github.yumeyucca.lucide.Milestone
import io.github.yumeyucca.lucide.Search
import io.github.yumeyucca.lucide.Tag
import io.github.yumeyucca.lucide.UsersRound
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationReviewFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationSortOrder
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.locale.GithubStrings_WorkList
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

private val WorkListIssueTabs =
    listOf(
        CollaborationStateFilter.All,
        CollaborationStateFilter.Open,
        CollaborationStateFilter.Closed,
    )

fun workListTabOrder(kind: CollaborationKind, filter: CollaborationListFilter): Int =
    if (kind == CollaborationKind.PullRequest) {
        if (filter.draftOnly) 1 else 0
    } else {
        WorkListIssueTabs.indexOf(filter.state)
    }

@Composable
fun WorkListTabs(
    kind: CollaborationKind,
    filter: CollaborationListFilter,
    onSelect: (CollaborationListFilter) -> Unit,
) {
    val modifier = Modifier.padding(vertical = 8.dp)
    if (kind == CollaborationKind.PullRequest) {
        PullRequestDraftFilterControl(
            draftOnly = filter.draftOnly,
            onSelected = { onSelect(filter.copy(draftOnly = it)) },
            modifier = modifier,
        )
    } else {
        CollaborationStateFilterControl(
            selected = filter.state,
            onSelected = { onSelect(filter.copy(state = it)) },
            modifier = modifier,
            allFirst = true,
        )
    }
}

@Composable
internal fun WorkListActions(
    enabled: Boolean,
    onSearch: () -> Unit,
    onShowFilterSheet: () -> Unit,
) {
    val common = LocalGithubStrings.current.Common
    val strings = LocalGithubStrings.current.WorkList
    GithubToolbarActions(
        toolbarIcon(
            Lucide.Search,
            common.Search,
            enabled = enabled,
            key = "search",
            onClick = onSearch,
        ),
        toolbarIcon(
            Lucide.ListFilter,
            strings.SortAndFilter,
            enabled = enabled,
            key = "filter",
            onClick = onShowFilterSheet,
        ),
    )
}

private data class WorkListFilterChoice(
    val title: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
internal fun WorkListFilterSheet(
    visible: Boolean,
    kind: CollaborationKind?,
    filter: CollaborationListFilter,
    scopes: List<CollaborationScopeFilter>,
    labels: List<CollaborationLabelDto>,
    milestones: List<String>,
    issueTypes: List<CollaborationLabelDto>,
    discussionCategories: List<String>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (CollaborationListFilter) -> Unit,
) {
    val strings = LocalGithubStrings.current.WorkList
    val common = LocalGithubStrings.current.Common
    var query by remember(visible) { mutableStateOf("") }
    var draft by remember(visible) { mutableStateOf(filter) }
    val normalizedQuery = query.trim()
    val matches: (String) -> Boolean = {
        normalizedQuery.isEmpty() || it.contains(normalizedQuery, ignoreCase = true)
    }
    val stateChoices =
        WorkListIssueTabs.filter { matches(stateLabel(it, strings)) }
            .map { state ->
                WorkListFilterChoice(stateLabel(state, strings), draft.state == state) {
                    draft = draft.copy(state = state)
                }
            }
    val reviewChoices =
        if (kind == CollaborationKind.PullRequest) {
            CollaborationReviewFilter.entries
                .map { review ->
                    WorkListFilterChoice(reviewLabel(review, strings), draft.review == review) {
                        draft = draft.copy(review = review)
                    }
                }
                .filter { matches(it.title) }
        } else emptyList()
    val scopeChoices =
        scopes
            .filter { matches(scopeLabel(it, strings)) }
            .map { scope ->
                WorkListFilterChoice(scopeLabel(scope, strings), draft.scope == scope) {
                    draft =
                        draft.copy(
                            scope = scope,
                            unassignedOnly =
                                draft.unassignedOnly &&
                                    scope != CollaborationScopeFilter.AssignedToMe,
                        )
                }
            }
    val detailChoices =
        if (kind == null) {
            listOf(
                    WorkListFilterChoice(strings.Unanswered, draft.unansweredOnly) {
                        draft = draft.copy(unansweredOnly = !draft.unansweredOnly)
                    },
                )
                .filter { matches(it.title) }
        } else
            listOfNotNull(
                    if (kind == CollaborationKind.PullRequest) {
                        WorkListFilterChoice(strings.DraftOnly, draft.draftOnly) {
                            draft = draft.copy(draftOnly = !draft.draftOnly)
                        }
                    } else null,
                    WorkListFilterChoice(strings.NoLabels, draft.unlabeledOnly) {
                        draft = draft.copy(label = null, unlabeledOnly = !draft.unlabeledOnly)
                    },
                    WorkListFilterChoice(strings.NoAssignee, draft.unassignedOnly) {
                        val scope =
                            if (draft.scope == CollaborationScopeFilter.AssignedToMe) {
                                CollaborationScopeFilter.All
                            } else {
                                draft.scope
                            }
                        draft = draft.copy(scope = scope, unassignedOnly = !draft.unassignedOnly)
                    },
                )
                .filter { matches(it.title) }
    val sortChoices =
        CollaborationSortOrder.entries
            .filter { matches(sortLabel(it, strings)) }
            .map { sort ->
                WorkListFilterChoice(sortLabel(sort, strings), draft.sort == sort) {
                    draft = draft.copy(sort = sort)
                }
            }
    val labelChoices =
        (listOf(null) + labels.map { it.name })
            .filter { matches(it ?: strings.All) }
            .map { label ->
                WorkListFilterChoice(
                    label ?: strings.All,
                    draft.label == label && !draft.unlabeledOnly,
                ) {
                    draft = draft.copy(label = label, unlabeledOnly = false)
                }
            }
    val milestoneChoices =
        (listOf(null) + milestones)
            .filter { matches(it ?: strings.All) }
            .map { milestone ->
                WorkListFilterChoice(
                    milestone ?: strings.All,
                    draft.milestone == milestone,
                ) {
                    draft = draft.copy(milestone = milestone)
                }
            }
    val issueTypeChoices =
        (listOf(null) + issueTypes.map { it.name })
            .filter { matches(it ?: strings.All) }
            .map { type ->
                WorkListFilterChoice(type ?: strings.All, draft.issueType == type) {
                    draft = draft.copy(issueType = type)
                }
            }
    GithubSearchSelectionSheet(
        visible = visible,
        title = strings.SortAndFilter,
        placeholder = common.Search,
        queryKey = query,
        onQueryChange = { query = it },
        onDismiss = onDismiss,
        onConfirm = { onApply(draft) },
        enabled = enabled,
    ) { _ ->
        fun group(title: String, choices: List<WorkListFilterChoice>) {
            if (choices.isEmpty()) return
            item {
                GithubSection(title) {
                    choices.forEach { choice ->
                        GithubFilterChoiceRow(
                            choice.title,
                            choice.selected,
                            enabled = enabled,
                            onClick = choice.onClick,
                        )
                    }
                }
            }
        }
        group(strings.State, stateChoices)
        group(strings.Scope, scopeChoices)
        group(strings.ReviewStatus, reviewChoices)
        group(strings.Filter, detailChoices)
        if (labels.isNotEmpty()) group(strings.Labels, labelChoices)
        if (milestones.isNotEmpty()) group(strings.Milestone, milestoneChoices)
        if (issueTypes.isNotEmpty()) group(strings.IssueType, issueTypeChoices)
        if (discussionCategories.isNotEmpty()) {
            group(
                strings.Category,
                (listOf(null) + discussionCategories)
                    .map { category ->
                        WorkListFilterChoice(category ?: strings.All, draft.category == category) {
                            draft = draft.copy(category = category)
                        }
                    }
                    .filter { matches(it.title) },
            )
        }
        group(strings.Sort, sortChoices)
    }
}

/** Issue 和讨论的列表状态切换。 */
@Composable
fun CollaborationStateFilterControl(
    selected: CollaborationStateFilter,
    onSelected: (CollaborationStateFilter) -> Unit,
    modifier: Modifier = Modifier,
    allFirst: Boolean = false,
) {
    val strings = LocalGithubStrings.current.WorkList
    val states = if (allFirst) WorkListIssueTabs else CollaborationStateFilter.entries
    GlasmioSegmentedControl(
        items = states.map { it to stateLabel(it, strings) },
        selected = selected,
        onSelected = onSelected,
        modifier = modifier.fillMaxWidth(),
        height = 36.dp,
    )
}

@Composable
fun WorkListFilterMenus(
    filter: CollaborationListFilter,
    scopes: List<CollaborationScopeFilter>,
    enabled: Boolean,
    onSelect: (CollaborationListFilter) -> Unit,
    labels: List<CollaborationLabelDto> = emptyList(),
    milestones: List<String> = emptyList(),
    issueTypes: List<CollaborationLabelDto> = emptyList(),
    pullRequest: Boolean = false,
) {
    val strings = LocalGithubStrings.current.WorkList
    val details = buildList {
        add(
            GlasmioCascadingMenuItem(
                title = strings.NoLabels,
                checked = filter.unlabeledOnly,
                onClick = {
                    onSelect(filter.copy(label = null, unlabeledOnly = !filter.unlabeledOnly))
                },
            ),
        )
        add(
            GlasmioCascadingMenuItem(
                title = strings.NoAssignee,
                checked = filter.unassignedOnly,
                onClick = {
                    val scope =
                        if (filter.scope == CollaborationScopeFilter.AssignedToMe) {
                            CollaborationScopeFilter.All
                        } else {
                            filter.scope
                        }
                    onSelect(filter.copy(scope = scope, unassignedOnly = !filter.unassignedOnly))
                },
            ),
        )
        if (pullRequest) {
            add(
                filterGroup(
                    strings.State,
                    filter.state,
                    CollaborationStateFilter.entries,
                    { stateLabel(it, strings) },
                ) {
                    onSelect(filter.copy(state = it))
                },
            )
            add(
                filterGroup(
                    strings.ReviewStatus,
                    filter.review,
                    CollaborationReviewFilter.entries,
                    { reviewLabel(it, strings) },
                ) {
                    onSelect(filter.copy(review = it))
                },
            )
        }
        if (labels.isNotEmpty()) {
            add(
                filterGroup(
                    strings.Labels,
                    filter.label,
                    listOf(null) + labels.map { it.name },
                    { it ?: strings.All },
                ) {
                    onSelect(filter.copy(label = it, unlabeledOnly = false))
                },
            )
        }
        if (milestones.isNotEmpty()) {
            add(
                filterGroup(
                    strings.Milestone,
                    filter.milestone,
                    listOf(null) + milestones,
                    { it ?: strings.All },
                ) {
                    onSelect(filter.copy(milestone = it))
                },
            )
        }
        if (issueTypes.isNotEmpty()) {
            add(
                filterGroup(
                    strings.IssueType,
                    filter.issueType,
                    listOf(null) + issueTypes.map { it.name },
                    { it ?: strings.All },
                ) {
                    onSelect(filter.copy(issueType = it))
                },
            )
        }
    }
    val selectScope: (CollaborationScopeFilter) -> Unit = { scope ->
        onSelect(
            filter.copy(
                scope = scope,
                unassignedOnly =
                    filter.unassignedOnly && scope != CollaborationScopeFilter.AssignedToMe,
            ),
        )
    }
    val selectSort: (CollaborationSortOrder) -> Unit = { onSelect(filter.copy(sort = it)) }
    WorkListFilterMenu(
        title = strings.Scope,
        icon = Lucide.UsersRound,
        enabled = enabled,
        items = filterRows(filter.scope, scopes, { scopeLabel(it, strings) }, selectScope),
    )
    WorkListFilterMenu(
        title = strings.Filter,
        icon = Lucide.ListFilter,
        enabled = enabled,
        items = details,
    )
    WorkListFilterMenu(
        title = strings.Sort,
        icon = Lucide.ArrowDownUp,
        enabled = enabled,
        items =
            filterRows(
                filter.sort,
                CollaborationSortOrder.entries,
                { sortLabel(it, strings) },
                selectSort,
            ),
    )
}

private fun <T> filterGroup(
    title: String,
    selected: T,
    items: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) =
    GlasmioCascadingMenuItem(
        title = title,
        subtitle = label(selected),
        children = filterRows(selected, items, label, onSelect),
    )

private fun <T> filterRows(
    selected: T,
    items: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) = items.map { item ->
    GlasmioCascadingMenuItem(
        title = label(item),
        checked = item == selected,
        onClick = { onSelect(item) },
    )
}

@Composable
private fun WorkListFilterMenu(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    items: List<GlasmioCascadingMenuItem>,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberGlasmioMenuAnchorState()
    val strings = LocalGithubStrings.current
    val density = LocalDensity.current
    var windowScreenOrigin by remember { mutableStateOf<Offset?>(null) }
    Box {
        GlasmioIconButton(
            onClick = { expanded = true },
            modifier =
                Modifier.glasmioMenuAnchor(anchor).onGloballyPositioned { coordinates ->
                    windowScreenOrigin =
                        coordinates.localToScreen(Offset.Zero) -
                            coordinates.localToWindow(Offset.Zero)
                },
            enabled = enabled,
        ) {
            GithubIcon(icon, contentDescription = title)
        }
        val origin = windowScreenOrigin
        val bounds = anchor.bounds
        if (expanded && enabled && origin != null && bounds != null) {
            val gap = with(density) { 12.dp.toPx() }
            val layoutDirection = LocalLayoutDirection.current
            val windowSize = LocalWindowInfo.current.containerSize
            val insets = WindowInsets.safeDrawing.union(WindowInsets.ime)
            val left = insets.getLeft(density, layoutDirection) + gap
            val right = windowSize.width - insets.getRight(density, layoutDirection) - gap
            val safeTop = insets.getTop(density) + gap
            val safeBottom = windowSize.height - insets.getBottom(density) - gap
            // 与编辑器一致，取父菜单与子菜单两套纵向坐标的安全区交集。
            val top = maxOf(safeTop, safeTop + origin.y)
            val bottom = minOf(safeBottom, safeBottom + origin.y)
            val viewport =
                if (right > left && bottom - top > gap * 2f) Rect(left, top, right, bottom)
                else null
            GlasmioCascadingMenu(
                anchorBounds = bounds.translate(Offset(0f, gap)),
                menuAnchorState = anchor,
                title = title,
                items = items,
                expandedDescription = strings.EditorMenu.Expanded,
                collapsedDescription = strings.EditorMenu.Collapsed,
                onDismiss = { expanded = false },
                forceBelowAnchor = true,
                viewportBounds = viewport,
                useAccentIcons = false,
            )
        }
    }
}

@Composable
private fun PullRequestDraftFilterControl(
    draftOnly: Boolean,
    onSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalGithubStrings.current.WorkList
    GlasmioSegmentedControl(
        items = listOf(false to strings.All, true to strings.Draft),
        selected = draftOnly,
        onSelected = onSelected,
        modifier = modifier.fillMaxWidth(),
        height = 36.dp,
    )
}

@Composable
fun CollaborationFilterAction(
    filter: CollaborationListFilter,
    scopes: List<CollaborationScopeFilter>,
    onSelect: (CollaborationListFilter) -> Unit,
    enabled: Boolean = true,
    showDraftFilter: Boolean = false,
    showUnansweredFilter: Boolean = false,
    labels: List<CollaborationLabelDto> = emptyList(),
    categories: List<String> = emptyList(),
    milestones: List<String> = emptyList(),
    issueTypes: List<CollaborationLabelDto> = emptyList(),
    showReviewFilter: Boolean = false,
    onExpand: (() -> Unit)? = null,
) {
    val strings = LocalGithubStrings.current.WorkList
    val menuStrings = LocalGithubStrings.current.EditorMenu
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberGlasmioMenuAnchorState()
    Box {
        GlasmioIconButton(
            onClick = {
                if (enabled) {
                    onExpand?.invoke()
                    expanded = true
                }
            },
            enabled = enabled,
            modifier = Modifier.glasmioMenuAnchor(anchor),
        ) {
            GithubIcon(Lucide.ListFilter, contentDescription = strings.Filter)
        }
        if (expanded) {
            GlasmioCascadingMenu(
                anchorBounds = anchor.bounds,
                menuAnchorState = anchor,
                title = strings.Filter,
                expandedDescription = menuStrings.Expanded,
                collapsedDescription = menuStrings.Collapsed,
                onDismiss = { expanded = false },
                forceBelowAnchor = true,
                useAccentIcons = false,
                items =
                    buildList<GlasmioCascadingMenuItem> {
                        add(
                            GlasmioCascadingMenuItem(
                                title = strings.State,
                                icon = {
                                    GithubIcon(
                                        Lucide.CircleDot,
                                        contentDescription = null,
                                        size = 20.dp,
                                    )
                                },
                                subtitle = stateLabel(filter.state, strings),
                                children =
                                    CollaborationStateFilter.entries.map { state ->
                                        GlasmioCascadingMenuItem(
                                            title = stateLabel(state, strings),
                                            checked = filter.state == state,
                                            onClick = { onSelect(filter.copy(state = state)) },
                                        )
                                    },
                            ),
                        )
                        add(
                            GlasmioCascadingMenuItem(
                                title = strings.Scope,
                                icon = {
                                    GithubIcon(
                                        Lucide.UsersRound,
                                        contentDescription = null,
                                        size = 20.dp,
                                    )
                                },
                                subtitle = scopeLabel(filter.scope, strings),
                                children =
                                    scopes.map { scope ->
                                        GlasmioCascadingMenuItem(
                                            title = scopeLabel(scope, strings),
                                            checked = filter.scope == scope,
                                            onClick = { onSelect(filter.copy(scope = scope)) },
                                        )
                                    },
                            ),
                        )
                        add(
                            GlasmioCascadingMenuItem(
                                title = strings.Sort,
                                icon = {
                                    GithubIcon(
                                        Lucide.ArrowDownUp,
                                        contentDescription = null,
                                        size = 20.dp,
                                    )
                                },
                                subtitle = sortLabel(filter.sort, strings),
                                children =
                                    CollaborationSortOrder.entries.map { sort ->
                                        GlasmioCascadingMenuItem(
                                            title = sortLabel(sort, strings),
                                            checked = filter.sort == sort,
                                            onClick = { onSelect(filter.copy(sort = sort)) },
                                        )
                                    },
                            ),
                        )
                        if (labels.isNotEmpty()) {
                            add(
                                GlasmioCascadingMenuItem(
                                    title = strings.Labels,
                                    icon = {
                                        GithubIcon(
                                            Lucide.Tag,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    subtitle = filter.label ?: strings.All,
                                    children =
                                        buildList<GlasmioCascadingMenuItem> {
                                            add(
                                                GlasmioCascadingMenuItem(
                                                    title = strings.All,
                                                    checked = filter.label == null,
                                                    onClick = {
                                                        onSelect(filter.copy(label = null))
                                                    },
                                                ),
                                            )
                                            labels.take(50).forEach { label ->
                                                add(
                                                    GlasmioCascadingMenuItem(
                                                        title = label.name,
                                                        checked = filter.label == label.name,
                                                        onClick = {
                                                            onSelect(
                                                                filter.copy(
                                                                    label = label.name,
                                                                ),
                                                            )
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                ),
                            )
                        }
                        if (issueTypes.isNotEmpty()) {
                            add(
                                GlasmioCascadingMenuItem(
                                    title = strings.IssueType,
                                    icon = {
                                        GithubIcon(
                                            Lucide.CircleDot,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    subtitle = filter.issueType ?: strings.All,
                                    children =
                                        buildList<GlasmioCascadingMenuItem> {
                                            add(
                                                GlasmioCascadingMenuItem(
                                                    title = strings.All,
                                                    checked = filter.issueType == null,
                                                    onClick = {
                                                        onSelect(
                                                            filter.copy(issueType = null),
                                                        )
                                                    },
                                                ),
                                            )
                                            issueTypes.take(50).forEach { type ->
                                                add(
                                                    GlasmioCascadingMenuItem(
                                                        title = type.name,
                                                        checked = filter.issueType == type.name,
                                                        onClick = {
                                                            onSelect(
                                                                filter.copy(
                                                                    issueType = type.name,
                                                                ),
                                                            )
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                ),
                            )
                        }
                        if (milestones.isNotEmpty()) {
                            add(
                                GlasmioCascadingMenuItem(
                                    title = strings.Milestone,
                                    icon = {
                                        GithubIcon(
                                            Lucide.Milestone,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    subtitle = filter.milestone ?: strings.All,
                                    children =
                                        buildList<GlasmioCascadingMenuItem> {
                                            add(
                                                GlasmioCascadingMenuItem(
                                                    title = strings.All,
                                                    checked = filter.milestone == null,
                                                    onClick = {
                                                        onSelect(
                                                            filter.copy(milestone = null),
                                                        )
                                                    },
                                                ),
                                            )
                                            milestones.take(50).forEach { milestone ->
                                                add(
                                                    GlasmioCascadingMenuItem(
                                                        title = milestone,
                                                        checked = filter.milestone == milestone,
                                                        onClick = {
                                                            onSelect(
                                                                filter.copy(
                                                                    milestone = milestone,
                                                                ),
                                                            )
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                ),
                            )
                        }
                        if (categories.isNotEmpty()) {
                            add(
                                GlasmioCascadingMenuItem(
                                    title = strings.Category,
                                    icon = {
                                        GithubIcon(
                                            Lucide.MessagesSquare,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    subtitle = filter.category ?: strings.All,
                                    children =
                                        buildList<GlasmioCascadingMenuItem> {
                                            add(
                                                GlasmioCascadingMenuItem(
                                                    title = strings.All,
                                                    checked = filter.category == null,
                                                    onClick = {
                                                        onSelect(
                                                            filter.copy(category = null),
                                                        )
                                                    },
                                                ),
                                            )
                                            categories.take(50).forEach { category ->
                                                add(
                                                    GlasmioCascadingMenuItem(
                                                        title = category,
                                                        checked = filter.category == category,
                                                        onClick = {
                                                            onSelect(
                                                                filter.copy(
                                                                    category = category,
                                                                ),
                                                            )
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                ),
                            )
                        }
                        if (showReviewFilter) {
                            add(
                                GlasmioCascadingMenuItem(
                                    title = strings.ReviewStatus,
                                    icon = {
                                        GithubIcon(
                                            Lucide.MessageCircleCheck,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    subtitle = reviewLabel(filter.review, strings),
                                    children =
                                        CollaborationReviewFilter.entries.map { review ->
                                            GlasmioCascadingMenuItem(
                                                title = reviewLabel(review, strings),
                                                checked = filter.review == review,
                                                onClick = {
                                                    onSelect(filter.copy(review = review))
                                                },
                                            )
                                        },
                                ),
                            )
                        }
                        if (showDraftFilter) {
                            add(
                                GlasmioCascadingMenuItem(
                                    title = strings.DraftOnly,
                                    icon = {
                                        GithubIcon(
                                            Lucide.GitPullRequestDraft,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    checked = filter.draftOnly,
                                    onClick = {
                                        onSelect(filter.copy(draftOnly = !filter.draftOnly))
                                    },
                                ),
                            )
                        }
                        if (showUnansweredFilter) {
                            add(
                                GlasmioCascadingMenuItem(
                                    title = strings.Unanswered,
                                    icon = {
                                        GithubIcon(
                                            Lucide.MessageSquare,
                                            contentDescription = null,
                                            size = 20.dp,
                                        )
                                    },
                                    checked = filter.unansweredOnly,
                                    onClick = {
                                        onSelect(
                                            filter.copy(
                                                unansweredOnly = !filter.unansweredOnly,
                                            ),
                                        )
                                    },
                                ),
                            )
                        }
                    },
            )
        }
    }
}

private fun stateLabel(state: CollaborationStateFilter, strings: GithubStrings_WorkList) =
    when (state) {
        CollaborationStateFilter.Open -> strings.Open
        CollaborationStateFilter.Closed -> strings.Closed
        CollaborationStateFilter.All -> strings.All
    }

private fun scopeLabel(scope: CollaborationScopeFilter, strings: GithubStrings_WorkList) =
    when (scope) {
        CollaborationScopeFilter.All -> strings.All
        CollaborationScopeFilter.CreatedByMe -> strings.CreatedByMe
        CollaborationScopeFilter.AssignedToMe -> strings.AssignedToMe
        CollaborationScopeFilter.ReviewRequested -> strings.ReviewRequested
        CollaborationScopeFilter.Commented -> strings.Commented
        CollaborationScopeFilter.Mentioned -> strings.Mentioned
        CollaborationScopeFilter.Involved -> strings.Involved
    }

private fun sortLabel(sort: CollaborationSortOrder, strings: GithubStrings_WorkList) =
    when (sort) {
        CollaborationSortOrder.Updated -> strings.Updated
        CollaborationSortOrder.Created -> strings.Created
        CollaborationSortOrder.Comments -> strings.Comments
    }

private fun reviewLabel(review: CollaborationReviewFilter, strings: GithubStrings_WorkList) =
    when (review) {
        CollaborationReviewFilter.Any -> strings.All
        CollaborationReviewFilter.NoReviews -> strings.NoReviews
        CollaborationReviewFilter.Required -> strings.ReviewRequired
        CollaborationReviewFilter.Approved -> strings.Approved
        CollaborationReviewFilter.ChangesRequested -> strings.ChangesRequested
        CollaborationReviewFilter.ReviewedByMe -> strings.ReviewedByMe
        CollaborationReviewFilter.NotReviewedByMe -> strings.NotReviewedByMe
        CollaborationReviewFilter.AwaitingMyTeamReview -> strings.AwaitingMyTeamReview
        CollaborationReviewFilter.AwaitingMyReview -> strings.AwaitingMyReview
    }
