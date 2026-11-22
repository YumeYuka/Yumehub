package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioGroupedTableFooter
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import io.github.neoruaa.glasmio.ui.GlasmioSheetValue
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.rememberGlasmioSheetState
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.CircleSlash
import io.github.yumeyucca.lucide.FaceSlightlySmiling
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.locale.GithubStrings_Common
import moe.yumeyuka.yumehub.locale.GithubStrings_Profile
import moe.yumeyuka.yumehub.ui.component.GithubEmojiChoiceCell
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.placeholders.IconTilePlaceholder

private const val StatusMaxLength = 80

@Composable
internal fun ProfileStatusSheet(
    state: StatusEditorState?,
    strings: GithubStrings_Profile,
    common: GithubStrings_Common,
    onDismiss: () -> Unit,
    onEmojiChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onLimitedAvailabilityChange: (Boolean) -> Unit,
    onExpiryChange: (StatusExpiry) -> Unit,
    onOrganizationChange: (String?) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    var displayed by remember { mutableStateOf(state) }
    LaunchedEffect(state) { if (state != null) displayed = state }
    val editor = state ?: displayed
    val submitting = state?.submitting == true
    val sheetState =
        rememberGlasmioSheetState(skipPartiallyExpanded = true) { value ->
            value != GlasmioSheetValue.Hidden || state?.submitting != true
        }
    GlasmioBottomSheet(
        visible = state != null,
        onDismissRequest = onDismiss,
        title = strings.EditStatus,
        sheetState = sheetState,
        dismissOnBackPress = !submitting,
        dismissOnClickOutside = !submitting,
        topBar = {
            GlasmioTopBar(
                title = strings.EditStatus,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !submitting) {
                        GithubIcon(Lucide.X, contentDescription = common.Close)
                    }
                },
                actions = {
                    val canSave = editor?.canSave() == true
                    GlasmioIconButton(onClick = onSave, enabled = canSave) {
                        GithubIcon(
                            Lucide.Check,
                            contentDescription = strings.Save,
                            tint =
                                if (canSave) GlasmioTheme.colors.accent
                                else GlasmioTheme.colors.tertiaryContent,
                        )
                    }
                },
            )
        },
    ) {
        if (editor != null) {
            StatusEditorContent(
                editor = editor,
                strings = strings,
                common = common,
                onEmojiChange = onEmojiChange,
                onMessageChange = onMessageChange,
                onLimitedAvailabilityChange = onLimitedAvailabilityChange,
                onExpiryChange = onExpiryChange,
                onOrganizationChange = onOrganizationChange,
                onClear = onClear,
            )
        }
    }
}

@Composable
private fun StatusEditorContent(
    editor: StatusEditorState,
    strings: GithubStrings_Profile,
    common: GithubStrings_Common,
    onEmojiChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onLimitedAvailabilityChange: (Boolean) -> Unit,
    onExpiryChange: (StatusExpiry) -> Unit,
    onOrganizationChange: (String?) -> Unit,
    onClear: () -> Unit,
) {
    val colors = GlasmioTheme.colors
    val focus = LocalFocusManager.current
    var pickerOpen by remember { mutableStateOf(false) }
    val remaining = (StatusMaxLength - editor.message.length).coerceAtLeast(0)
    Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
        Column(
            Modifier.fillMaxSize()
                .padding(LocalGlasmioSheetContentPadding.current)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(12.dp))
            GlasmioGroupedList(modifier = Modifier.padding(horizontal = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EmojiPickerButton(
                        emoji = editor.emoji,
                        contentDescription = strings.ChooseEmoji,
                        enabled = !editor.submitting,
                        onClick = {
                            pickerOpen = true
                            focus.clearFocus()
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = editor.message,
                        onValueChange = { if (it.length <= StatusMaxLength) onMessageChange(it) },
                        modifier = Modifier.weight(1f),
                        enabled = !editor.submitting,
                        textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (editor.message.isEmpty()) {
                                Text(
                                    text = strings.StatusPlaceholder,
                                    style = GlasmioTheme.textStyles.body,
                                    color = colors.tertiaryContent,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
            GlasmioGroupedTableFooter(
                text = strings.CharactersRemaining(remaining),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(12.dp))
            GlasmioGroupedList(modifier = Modifier.padding(horizontal = 12.dp)) {
                GlasmioListRow(
                    title = strings.Busy,
                    showTopSeparator = false,
                    systemName = null,
                    trailing = {
                        GlasmioSwitch(
                            checked = editor.limitedAvailability,
                            onCheckedChange = onLimitedAvailabilityChange,
                            enabled = !editor.submitting,
                            onTintColor = colors.accent,
                        )
                    },
                )
            }
            GlasmioGroupedTableFooter(
                text = strings.BusyHint,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(12.dp))
            GlasmioGroupedList(modifier = Modifier.padding(horizontal = 12.dp)) {
                GlasmioListRow(
                    title = strings.ClearAfter,
                    showTopSeparator = false,
                    systemName = null,
                    trailing = {
                        GlasmioPopUpButton(
                            selected = editor.expiry,
                            items = StatusExpiry.entries,
                            onSelected = onExpiryChange,
                            label = { expiryLabel(it, strings) },
                            enabled = !editor.submitting,
                        )
                    },
                )
                GlasmioListRow(
                    title = strings.Visibility,
                    systemName = null,
                    trailing = { VisibilityPicker(editor, strings, onOrganizationChange) },
                )
            }
            if (editor.failed) {
                Text(
                    text = strings.StatusSaveFailed,
                    modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp),
                    style = GlasmioTheme.textStyles.footnote,
                    color = colors.destructive,
                )
            }
            Spacer(Modifier.height(12.dp))
            GlasmioButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                enabled = editor.canClear(),
                destructive = true,
                sampleBackdrop = false,
            ) {
                Text(
                    text = strings.ClearStatus,
                    style = GlasmioTheme.textStyles.body,
                    color = if (editor.canClear()) colors.destructive else colors.secondaryContent,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    EmojiPickerDialog(
        visible = pickerOpen,
        emoji = editor.emoji,
        strings = strings,
        common = common,
        onEmojiChange = onEmojiChange,
        onDismiss = { pickerOpen = false },
    )
}

private data class StatusVisibility(val id: String?, val label: String)

/** 始终保留菜单锚点，所有人与组织使用同一套单选勾选逻辑。 */
@Composable
private fun VisibilityPicker(
    editor: StatusEditorState,
    strings: GithubStrings_Profile,
    onOrganizationChange: (String?) -> Unit,
) {
    val options =
        listOf(StatusVisibility(null, strings.Everyone)) +
            editor.organizations.map { organization ->
                StatusVisibility(
                    id = organization.id,
                    label = organization.name?.takeIf { it.isNotBlank() } ?: organization.login,
                )
            }
    val selected = options.firstOrNull { it.id == editor.organizationId } ?: options.first()
    GlasmioPopUpButton(
        selected = selected,
        items = options,
        onSelected = { onOrganizationChange(it.id) },
        label = { it.label },
        enabled = !editor.submitting,
    )
}

@Composable
private fun EmojiPickerDialog(
    visible: Boolean,
    emoji: String,
    strings: GithubStrings_Profile,
    common: GithubStrings_Common,
    onEmojiChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val selectedEmoji by rememberStatusEmoji(emoji)
    var catalog by remember(context) { mutableStateOf<Result<List<StatusEmojiCategory>>?>(null) }
    LaunchedEffect(context, visible) {
        if (!visible || catalog?.isSuccess == true) return@LaunchedEffect
        catalog =
            try {
                Result.success(withContext(Dispatchers.IO) { loadStatusEmojiCategories(context) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
    }
    if (visible) GithubLoadFailureToast(catalog?.exceptionOrNull(), strings.EmojiLoadFailed)
    val selectedBackground = GlasmioTheme.colors.accent.copy(alpha = 0.18f)
    GlasmioAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = strings.ChooseEmoji,
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    label = common.Cancel,
                    onClick = onDismiss,
                    role = GlasmioAlertButtonRole.Cancel,
                ),
            ),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 40.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 320.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                GithubEmojiChoiceCell(
                    selected = emoji.isBlank(),
                    contentDescription = strings.NoEmoji,
                    selectedBackground = selectedBackground,
                    onClick = {
                        onEmojiChange("")
                        onDismiss()
                    },
                ) {
                    GithubIcon(
                        Lucide.CircleSlash,
                        contentDescription = null,
                        size = 18.dp,
                        tint = GlasmioTheme.colors.secondaryContent,
                    )
                }
            }
            if (catalog == null) {
                items(23, key = { "emoji-placeholder:$it" }) {
                    Box(Modifier.size(40.dp).animateItem(), contentAlignment = Alignment.Center) {
                        IconTilePlaceholder(22.dp)
                    }
                }
            }
            catalog?.getOrNull()?.forEach { category ->
                item(key = category.category, span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = emojiCategoryLabel(category.category, strings),
                        modifier =
                            Modifier.animateItem()
                                .fillMaxWidth()
                                .padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                        style = GlasmioTheme.textStyles.footnote,
                        color = GlasmioTheme.colors.secondaryContent,
                    )
                }
                items(category.emojis, key = { it }) { item ->
                    Box(Modifier.animateItem()) {
                        GithubEmojiChoiceCell(
                            selected = item == selectedEmoji,
                            contentDescription = item,
                            selectedBackground = selectedBackground,
                            onClick = {
                                onEmojiChange(item)
                                onDismiss()
                            },
                        ) {
                            Text(text = item, style = GlasmioTheme.textStyles.title2, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiPickerButton(
    emoji: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = GlasmioTheme.colors
    val displayEmoji by rememberStatusEmoji(emoji)
    Box(
        modifier =
            Modifier.size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.groupedBackground)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (displayEmoji.isBlank()) {
            GithubIcon(
                Lucide.FaceSlightlySmiling,
                contentDescription = null,
                size = 20.dp,
                tint = colors.secondaryContent,
            )
        } else {
            Text(text = displayEmoji, style = GlasmioTheme.textStyles.title2, maxLines = 1)
        }
    }
}

private fun emojiCategoryLabel(category: String, strings: GithubStrings_Profile): String =
    when (category) {
        "Smileys & Emotion" -> strings.EmojiCategorySmileys
        "People & Body" -> strings.EmojiCategoryPeople
        "Animals & Nature" -> strings.EmojiCategoryNature
        "Food & Drink" -> strings.EmojiCategoryFood
        "Travel & Places" -> strings.EmojiCategoryTravel
        "Activities" -> strings.EmojiCategoryActivities
        "Objects" -> strings.EmojiCategoryObjects
        "Symbols" -> strings.EmojiCategorySymbols
        "Flags" -> strings.EmojiCategoryFlags
        else -> category
    }

private fun expiryLabel(expiry: StatusExpiry, strings: GithubStrings_Profile): String =
    when (expiry) {
        StatusExpiry.Never -> strings.Never
        StatusExpiry.ThirtyMinutes -> strings.Expiry30Minutes
        StatusExpiry.OneHour -> strings.Expiry1Hour
        StatusExpiry.FourHours -> strings.Expiry4Hours
        StatusExpiry.OneDay -> strings.Expiry1Day
        StatusExpiry.OneWeek -> strings.Expiry1Week
    }

private fun StatusEditorState.canSave(): Boolean =
    !submitting && (emoji.isNotBlank() || message.isNotBlank())

private fun StatusEditorState.canClear(): Boolean = !submitting && hadStatus
