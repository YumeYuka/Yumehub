package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.runtime.compositionLocalOf

/** New forward entries defer heavy content until the moving page has settled. */
val LocalGithubRouteEntryReady = compositionLocalOf { true }

/** Only settled, active routes play card animations. */
val LocalGithubCardMotionEnabled = compositionLocalOf { true }
