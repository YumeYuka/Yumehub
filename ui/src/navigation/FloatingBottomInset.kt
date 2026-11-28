package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep floating controls off the window edge even if the system reports no bottom inset. */
fun floatingBottomInset(bottomInset: Dp): Dp = bottomInset.coerceAtLeast(12.dp)
