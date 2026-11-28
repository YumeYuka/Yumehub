package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.storage.mmkv.AccountAvatar

private val CompactAvatarSize = 36.dp

@Composable
fun AccountAvatars(
    accounts: List<AccountAvatar>,
    activeAvatarUrl: String?,
    size: Dp,
    compactProgress: State<Float>,
    modifier: Modifier = Modifier,
) {
    val active = accounts.firstOrNull()
    val compactScale = CompactAvatarSize / size
    if (accounts.size < 2) {
        UserAvatar(
            activeAvatarUrl ?: active?.avatarUrl,
            active?.login,
            size = size,
            modifier =
                modifier.graphicsLayer {
                    val progress = compactProgress.value.coerceIn(0f, 1f)
                    scaleX = 1f + (compactScale - 1f) * progress
                    scaleY = scaleX
                },
        )
        return
    }
    val spread = size * 0.25f
    val paired = accounts.size == 2
    val ring = GlasmioTheme.colors.groupedBackground
    Box(
        modifier.size(size + spread * (if (paired) 1 else 2), size),
        contentAlignment = Alignment.Center,
    ) {
        for (index in accounts.lastIndex downTo 0) {
            val account = accounts[index]
            val x =
                when (index) {
                    0 -> if (paired) -spread / 2 else size * 0f
                    1 -> if (paired) spread / 2 else spread
                    else -> -spread
                }
            val diameter = if (index == 0) size else size * 0.88f
            UserAvatar(
                url = if (index == 0) activeAvatarUrl ?: account.avatarUrl else account.avatarUrl,
                login = account.login.takeIf { index == 0 },
                size = diameter,
                modifier =
                    Modifier.graphicsLayer {
                            val progress = compactProgress.value.coerceIn(0f, 1f)
                            translationX = x.toPx() * (1f - progress)
                            if (index == 0) {
                                scaleX = 1f + (compactScale - 1f) * progress
                                scaleY = scaleX
                            } else {
                                alpha = 1f - progress
                            }
                        }
                        .border(size * 0.045f, ring, CircleShape),
            )
        }
    }
}
