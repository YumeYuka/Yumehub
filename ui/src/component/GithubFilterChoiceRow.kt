package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide

@Composable
fun GithubFilterChoiceRow(
    title: String,
    checked: Boolean,
    detail: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    showTopSeparator: Boolean = false,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = title,
        detail = detail,
        subtitle = subtitle,
        modifier = Modifier.semantics { selected = checked },
        enabled = enabled,
        leading = leading,
        trailing = {
            if (checked) GithubIcon(Lucide.Check, contentDescription = null, size = 20.dp)
        },
        showDisclosure = false,
        showTopSeparator = showTopSeparator,
        onClick = onClick,
    )
}
