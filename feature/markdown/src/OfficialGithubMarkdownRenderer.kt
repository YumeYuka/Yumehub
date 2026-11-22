package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownRenderer
import moe.yumeyuka.yumehub.ui.component.RetainVisibleMarkdownCard
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingOverlay
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.PlaceholderTokens
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubDetailPresentation
import org.koin.compose.koinInject

val officialGithubMarkdownRenderer: GithubMarkdownRenderer = OfficialGithubMarkdownRenderer

internal object OfficialGithubMarkdownRenderer : GithubMarkdownRenderer {
    @Composable
    override fun Render(
        content: GithubMarkdownContent,
        modifier: Modifier,
        actions: GithubMarkdownActions,
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
            MarkdownBody(content, modifier, actions, repository, session.isCurrent)
        }
    }

    @Composable
    override fun Preview(
        content: GithubMarkdownContent,
        visible: Boolean,
        contentPadding: PaddingValues,
        modifier: Modifier,
        actions: GithubMarkdownActions,
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
            MarkdownPreviewPane(
                content = content,
                visible = visible,
                contentPadding = contentPadding,
                modifier = modifier,
                actions = actions,
                repository = repository,
                isCurrentAccount = session.isCurrent,
            )
        }
    }
}

@Composable
private fun MarkdownBody(
    content: GithubMarkdownContent,
    modifier: Modifier,
    actions: GithubMarkdownActions,
    repository: GithubMarkdownRepository,
    isCurrentAccount: () -> Boolean,
) {
    val state = rememberNativeMarkdownDocument(content, repository, isCurrentAccount)
    val strings = LocalGithubStrings.current.Markdown
    val presentation = LocalGithubDetailPresentation.current
    val token = content
    DisposableEffect(presentation, token) {
        presentation?.documentVisible(token, false)
        onDispose { presentation?.removeDocument(token) }
    }
    var bounds by remember(token) { mutableStateOf(Rect.Zero) }
    val inViewport by remember(presentation, token) {
        derivedStateOf {
            val viewport = presentation?.viewport
            !bounds.isEmpty && (viewport == null || bounds.overlaps(viewport))
        }
    }
    LaunchedEffect(presentation, token, inViewport) {
        presentation?.documentVisible(token, inViewport)
    }
    var readyRevision by remember { mutableStateOf<NativeMarkdownRevision?>(null) }
    var failedRevision by remember { mutableStateOf<NativeMarkdownRevision?>(null) }
    val document = state.document
    val revealed =
        document != null &&
            failedRevision !== document.revision &&
            readyRevision === document.revision
    RetainVisibleMarkdownCard(revealed && inViewport)
    val failureMessage =
        when {
            failedRevision === state.revision || state.failure == NativeMarkdownFailure.PARSE ->
                strings.RenderingFailed
            state.failure == NativeMarkdownFailure.FETCH -> strings.LoadFailed
            else -> null
        }
    LaunchedEffect(failureMessage, presentation, token) {
        if (failureMessage != null) {
            presentation?.documentFailed(token) { if (isCurrentAccount()) state.retry() }
        }
    }
    LaunchedEffect(revealed, presentation, token, state.revision) {
        if (revealed && document?.revision === state.revision && isCurrentAccount()) {
            presentation?.documentReady(token)
        }
    }
    Column(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
        GithubLoadingOverlay(
            loading = !revealed,
            matchPlaceholderToContent = !content.readme,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(
                        min =
                            if (!revealed && failureMessage == null)
                                PlaceholderTokens.ReadmeMinHeight
                            else 0.dp,
                    ),
            placeholder = { if (failureMessage == null) GithubMarkdownPlaceholder(compact = true) },
        ) {
            if (document != null) {
                MarkdownWebDocument(
                    state = state,
                    document = document,
                    modifier = Modifier.fillMaxWidth(),
                    actions = actions,
                    isCurrentAccount = isCurrentAccount,
                    visible = revealed && inViewport && presentation?.interactive != false,
                    scrollToAnchor = content.scrollToAnchor,
                    onReady = { revision ->
                        if (revision === state.revision && isCurrentAccount()) {
                            readyRevision = revision
                            presentation?.documentReady(token)
                        }
                    },
                    onRenderFailure = { revision -> failedRevision = revision },
                )
            }
        }
        if (failureMessage != null && isCurrentAccount())
            GithubLoadFailureToast(state.failure ?: failureMessage, failureMessage)
    }
}

@Composable
internal fun MarkdownWebDocument(
    state: NativeMarkdownDocumentState,
    document: NativeMarkdownDocument,
    modifier: Modifier,
    actions: GithubMarkdownActions,
    isCurrentAccount: () -> Boolean,
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
        actions =
            GithubMarkdownActions(
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
