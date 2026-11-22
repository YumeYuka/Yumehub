package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator

/** 原生列表行的间距封装：图标与头像共用槽位、文字起点及分隔线缩进。 */
@Composable
fun GithubListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    showTopSeparator: Boolean = false,
    leading: @Composable () -> Unit,
) {
    val horizontalPadding = GlasmioListRowDefaults.horizontalPadding
    val leadingSize = 32.dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minHeight = (if (subtitle == null) 52.dp else 72.dp) * fontScale
    GlasmioListRow(
        title = title,
        subtitle = subtitle,
        modifier =
            modifier
                .heightIn(min = minHeight)
                .glasmioGroupedListSeparator(
                    color = GlasmioTheme.colors.separator,
                    visible = showTopSeparator,
                    horizontalInset = horizontalPadding + leadingSize + 8.dp,
                ),
        leading = {
            Box(
                Modifier.size(leadingSize),
                contentAlignment = Alignment.Center,
            ) {
                leading()
            }
        },
        onClick = onClick,
        showDisclosure = onClick != null,
        showTopSeparator = false,
    )
}
