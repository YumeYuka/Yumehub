package moe.yumeyuka.yumehub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlinx.coroutines.flow.first
import moe.yumeyuka.yumehub.feature.auth.AuthUiState
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler

@Composable
internal fun AccountSwitchTransition(
    session: AuthUiState.Authenticated,
    switchAccount: suspend (String) -> Boolean,
    content: @Composable BoxScope.((String) -> Unit) -> Unit,
) {
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val latestSession by rememberUpdatedState(session)
    val cover = remember { Animatable(if (target == null) 0f else 1f) }
    val requestSwitch: (String) -> Unit = remember {
        { login ->
            if (target == null && !login.equals(latestSession.login, ignoreCase = true)) {
                target = login
            }
        }
    }
    LaunchedEffect(target) {
        val login = target ?: return@LaunchedEffect
        val source = latestSession
        if (!login.equals(source.login, ignoreCase = true)) {
            cover.animateTo(1f, tween(140))
            if (latestSession.generation == source.generation && switchAccount(login)) {
                // 等新会话的页面进入组合再揭开，不等待网络，也不保留旧页面做交叉淡化。
                snapshotFlow { latestSession.generation }.first { it != source.generation }
            }
        }
        cover.animateTo(0f, tween(220))
        target = null
    }

    val switching = target != null
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { switching }) {
        Box(
            Modifier.matchParentSize()
                .then(if (switching) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            content(requestSwitch)
        }
        if (switching) {
            val background = GlasmioTheme.colors.groupedBackground
            Box(
                Modifier.matchParentSize()
                    .drawBehind { drawRect(background, alpha = cover.value) }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
            GithubBackHandler {}
        }
    }
}
