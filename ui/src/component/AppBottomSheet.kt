package moe.yumeyuka.yumehub.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioSheetState
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.rememberGlasmioSheetState
import moe.yumeyuka.yumehub.ui.component.placeholders.CardSizeSpec

/** Keep the pinned header outside the scroll viewport. Only fitted panels follow body size. */
@Composable
fun AppBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    visible: Boolean = true,
    fitContent: Boolean = false,
    skipPartiallyExpanded: Boolean = true,
    sheetState: GlasmioSheetState = rememberGlasmioSheetState(skipPartiallyExpanded),
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    topBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    GlasmioBottomSheet(
        title = title,
        onDismissRequest = onDismissRequest,
        visible = visible,
        fitContent = fitContent,
        sheetState = sheetState,
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        topBar = topBar,
    ) {
        val viewport = if (fitContent) Modifier.fillMaxWidth().animateContentSize(CardSizeSpec)
            else Modifier.fillMaxSize()
        Box(viewport.padding(LocalGlasmioSheetContentPadding.current).clipToBounds()) { content() }
    }
}
