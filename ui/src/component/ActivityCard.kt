package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList

internal val ActivityCardSpacing = 14.dp

/** 统一卡片宽度、内容边距与操作区。可选封面的底部间距由封面内容提供。 */
@Composable
fun ActivityCard(
    header: @Composable () -> Unit,
    summary: (@Composable () -> Unit)? = null,
    metadata: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    modifier: Modifier = Modifier,
    cover: (@Composable () -> Unit)? = null,
) {
    GlasmioGroupedList(modifier = modifier) {
        ActivityCardContent(
            header,
            summary,
            metadata,
            onClick,
            action,
            onLongClick,
            onLongClickLabel,
            cover,
        )
    }
}

@Composable
internal fun ActivityCardContent(
    header: @Composable () -> Unit,
    summary: (@Composable () -> Unit)? = null,
    metadata: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    cover: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                        onLongClickLabel = onLongClickLabel,
                    )
                else if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(16.dp),
    ) {
        cover?.invoke()
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ActivityCardSpacing),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                header()
            }
            summary?.invoke()
            metadata?.invoke()
        }
        action?.let {
            Column(
                Modifier.fillMaxWidth()
                    .padding(top = ActivityCardSpacing)
                    .heightIn(min = 48.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                it()
            }
        }
    }
}
