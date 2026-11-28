package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.ui.component.MarkdownActions
import moe.yumeyuka.yumehub.ui.component.MarkdownContent
import moe.yumeyuka.yumehub.ui.component.MarkdownRenderer
import org.koin.compose.koinInject

val officialGithubMarkdownRenderer: MarkdownRenderer = OfficialGithubMarkdownRenderer

internal object OfficialGithubMarkdownRenderer : MarkdownRenderer {
    @Composable
    override fun Render(
        content: MarkdownContent,
        modifier: Modifier,
        actions: MarkdownActions,
    ) {
        val repository: GithubMarkdownRepository = koinInject()
        val session = rememberMarkdownSession(repository)
        key(
            repository,
            session.revision,
            content.nodeId,
            content.pageUrl,
            content.repositoryRootUrl,
            content.ref,
            content.readme,
            content.preview,
            GlasmioTheme.colors.isDark,
            actions.onTaskChanged != null,
            actions.onCommitSuggestion != null,
        ) {
            MarkdownSurface(content, modifier, actions, repository, session.isCurrent)
        }
    }

    @Composable
    override fun Preview(
        content: MarkdownContent,
        visible: Boolean,
        contentPadding: PaddingValues,
        modifier: Modifier,
        actions: MarkdownActions,
    ) {
        val repository: GithubMarkdownRepository = koinInject()
        val session = rememberMarkdownSession(repository)
        key(
            repository,
            session.revision,
            content.pageUrl,
            content.repositoryRootUrl,
            GlasmioTheme.colors.isDark,
        ) {
            MarkdownSurface(
                content = content,
                visible = visible,
                contentPadding = contentPadding,
                viewport = true,
                modifier = modifier,
                actions = actions,
                repository = repository,
                isCurrentAccount = session.isCurrent,
            )
        }
    }
}

@Composable
internal fun MarkdownWebDocument(
    state: NativeMarkdownDocumentState,
    document: NativeMarkdownDocument,
    modifier: Modifier,
    actions: MarkdownActions,
    isCurrentAccount: () -> Boolean,
    presentation: MarkdownPresentation?,
    initialHeightPx: Int,
    visible: Boolean,
    viewport: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    scrollToAnchor: String? = null,
    onReady: (NativeMarkdownRevision) -> Unit,
    onRenderFailure: (NativeMarkdownRevision) -> Unit,
) {
    val binding =
        rememberNativeMarkdownBinding(
            state = state,
            document = document,
            actions = actions,
            isCurrentAccount = isCurrentAccount,
            visible = visible,
            scrollToAnchor = scrollToAnchor,
            onReady = onReady,
            onRenderFailure = onRenderFailure,
        )
    MarkdownWebContent(
        content = document.request.copy(scrollToAnchor = binding.scrollToAnchor),
        requested = state.request,
        allowInteraction = binding.allowsInteraction(),
        html = document.html,
        modifier = modifier,
        presentation = presentation,
        initialHeightPx = initialHeightPx,
        actions =
            MarkdownActions(
                onOpenLink = binding.actions.onOpenLink,
                onTaskChanged =
                    actions.onTaskChanged?.let {
                        { position, checked ->
                            binding.actions.onTaskChanged?.invoke(position, checked)
                        }
                    },
                onCommitSuggestion =
                    actions.onCommitSuggestion?.let {
                        { id, html -> binding.actions.onCommitSuggestion?.invoke(id, html) }
                    },
            ),
        isCurrentAccount = binding.ownsDocument,
        onReady = binding.actions.onReady,
        viewportScrolling = viewport,
        contentPadding = contentPadding,
        visible = visible,
        resourceRevision = document.revision,
        viewerLogin = document.viewerLogin,
        onRenderFailure = { if (binding.ownsDocument()) onRenderFailure(document.revision) },
    )
}
