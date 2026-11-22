package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.storage.mmkv.AccountAvatar

@Composable
fun GithubAccountAvatars(
    accounts: List<AccountAvatar>,
    activeAvatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val active = accounts.firstOrNull()
    if (accounts.size < 2) {
        GithubAvatar(
            activeAvatarUrl ?: active?.avatarUrl,
            active?.login,
            size = size,
            modifier = modifier,
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
            GithubAvatar(
                url = if (index == 0) activeAvatarUrl ?: account.avatarUrl else account.avatarUrl,
                login = account.login.takeIf { index == 0 },
                size = diameter,
                modifier = Modifier.offset(x = x).border(size * 0.045f, ring, CircleShape),
            )
        }
    }
}
