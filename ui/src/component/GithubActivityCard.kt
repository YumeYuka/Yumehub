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

/** 统一卡片宽度、内容边距与操作区，正文按类型和实际内容自然增高。 */
@Composable
fun GithubActivityCard(
    header: @Composable () -> Unit,
    summary: (@Composable () -> Unit)? = null,
    metadata: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    cover: (@Composable () -> Unit)? = null,
) {
    GlasmioGroupedList {
        GithubActivityCardContent(
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
internal fun GithubActivityCardContent(
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            cover?.invoke()
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                header()
            }
            summary?.let { it() }
            metadata?.let { it() }
        }
        action?.let {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                it()
            }
        }
    }
}
