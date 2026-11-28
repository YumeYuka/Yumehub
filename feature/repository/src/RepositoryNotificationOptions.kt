package moe.yumeyuka.yumehub.feature.repository

import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.placeholders.CardSizeSpec

@Composable
internal fun RepositoryNotificationOptions(
    subscription: String?,
    subscriptionTypes: Set<String>,
    discussionsEnabled: Boolean,
    visible: Boolean,
    onSubscriptionChange: (String, Set<String>) -> Unit,
) {
    val strings = LocalAppStrings.current.Repository
    val colors = GlasmioTheme.colors
    val custom = subscription == "CUSTOM" || subscription == "RELEASES_ONLY"
    var expanded by remember { mutableStateOf(custom) }
    LaunchedEffect(visible) { if (visible) expanded = custom }
    val selectedTypes = if (subscription == "RELEASES_ONLY") setOf("RELEASE") else subscriptionTypes
    GlasmioGroupedList {
        listOf(
                "UNSUBSCRIBED" to strings.WatchParticipating,
                "SUBSCRIBED" to strings.WatchAll,
                "IGNORED" to strings.WatchIgnoring,
            )
            .forEachIndexed { index, (value, label) ->
                val selected = subscription == value
                GlasmioListRow(
                    title = label,
                    modifier =
                        Modifier.semantics {
                            role = Role.RadioButton
                            this.selected = selected
                        },
                    showTopSeparator = index > 0,
                    onClick = { onSubscriptionChange(value, emptySet()) },
                    showDisclosure = false,
                    trailing = {
                        if (selected)
                            AppIcon(
                                Lucide.Check,
                                contentDescription = null,
                                tint = colors.accent,
                            )
                    },
                )
            }
    }
    GlasmioGroupedList(modifier = Modifier.animateContentSize(CardSizeSpec)) {
        GlasmioListRow(
            title = strings.WatchCustom,
            modifier =
                Modifier.semantics {
                    selected = custom
                    stateDescription = if (expanded) strings.Collapse else strings.More
                },
            showTopSeparator = false,
            onClick = { expanded = !expanded },
            showDisclosure = false,
            trailing = {
                if (custom)
                    AppIcon(
                        Lucide.Check,
                        contentDescription = null,
                        tint = colors.accent,
                    )
            },
        )
        if (expanded) {
            buildList {
                add("ISSUE" to strings.WatchIssues)
                add("PULL_REQUEST" to strings.WatchPullRequests)
                if (discussionsEnabled || "DISCUSSION" in selectedTypes)
                    add("DISCUSSION" to strings.Discussions)
                add("RELEASE" to strings.Releases)
                add("SECURITY_ALERT" to strings.WatchSecurityAlerts)
            }
                .forEach { (type, label) ->
                    val checked = custom && type in selectedTypes
                    GlasmioListRow(
                        title = label,
                        modifier =
                            Modifier.semantics {
                                role = Role.Checkbox
                                toggleableState =
                                    if (checked) ToggleableState.On else ToggleableState.Off
                            },
                        onClick = {
                            val current = if (custom) selectedTypes else emptySet()
                            val updated = if (checked) current - type else current + type
                            onSubscriptionChange(
                                if (updated.isEmpty()) "UNSUBSCRIBED" else "CUSTOM",
                                updated,
                            )
                        },
                        showDisclosure = false,
                        trailing = {
                            if (checked)
                                AppIcon(
                                    Lucide.Check,
                                    contentDescription = null,
                                    tint = colors.accent,
                                )
                        },
                    )
                }
        }
    }
}
