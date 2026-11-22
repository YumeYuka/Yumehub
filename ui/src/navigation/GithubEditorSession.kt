package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import moe.yumeyuka.yumehub.ui.component.MarkdownEditorState

enum class GithubEditorKind {
    Markdown,
    Code,
}

sealed interface GithubFileReadStatus {
    data object Loading : GithubFileReadStatus

    data class Failed(val message: String, val retry: (() -> Unit)?) : GithubFileReadStatus
}

/** 导航中传递同一编辑会话；正文变动立即回写调用方，不在页面副本中积压草稿。 */
class GithubEditorSession(
    title: String,
    val kind: GithubEditorKind,
    source: String,
    language: String? = null,
    editable: Boolean = true,
    submitEnabled: Boolean = false,
    val originalSource: String? = null,
    pageUrl: String? = null,
    repositoryRootUrl: String? = null,
    scrollToAnchor: String? = null,
    private val onSourceChange: (String) -> Unit = {},
    private val onSubmit: (() -> Unit)? = null,
    val onRequestEdit: (() -> Unit)? = null,
    filePath: String? = null,
    val previewOnOpen: Boolean = false,
    val headerContent: (@Composable () -> Unit)? = null,
    val submitLabel: String? = null,
    val submissionSheet: (@Composable (GithubEditorSession) -> Unit)? = null,
    private val onOpenMarkdownLink: ((String) -> Boolean)? = null,
    private val onDismiss: () -> Unit = {},
) {
    private var disposed = false
    var title by mutableStateOf(title)
        private set

    var pageUrl by mutableStateOf(pageUrl)
        private set

    var repositoryRootUrl by mutableStateOf(repositoryRootUrl)
        private set

    var scrollToAnchor by mutableStateOf(scrollToAnchor)
        private set

    var filePath by mutableStateOf(filePath)
        private set

    var fileReadStatus by mutableStateOf<GithubFileReadStatus?>(null)
    var readingOriginal by mutableStateOf(false)
    var readHtml by mutableStateOf<String?>(null)
    val id: String = UUID.randomUUID().toString()
    var markdownState: MarkdownEditorState = MarkdownEditorState(source)
        private set

    fun adoptMarkdownState(state: MarkdownEditorState) {
        if (kind == GithubEditorKind.Markdown && state.value.text == source) markdownState = state
    }

    var source by mutableStateOf(source)
        private set

    var language by mutableStateOf(language)
        private set

    var editable by mutableStateOf(editable)
        private set

    var submitting by mutableStateOf(false)
        private set

    var submitEnabled by mutableStateOf(submitEnabled)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var closeRequested by mutableStateOf(false)
        private set

    var wordWrap by mutableStateOf(kind == GithubEditorKind.Markdown)
        private set

    var lineNumbers by mutableStateOf(kind == GithubEditorKind.Code)
        private set

    var fontSize by mutableStateOf(14)
        private set

    fun changeFontSize(value: Int) {
        if (!submitting && !closeRequested) fontSize = value.coerceIn(10, 24)
    }

    fun changeWordWrap(value: Boolean) {
        if (!submitting) wordWrap = value
    }

    fun changeLineNumbers(value: Boolean) {
        if (!submitting) lineNumbers = value
    }

    fun changeSource(value: String) {
        if (disposed || !editable || submitting || closeRequested) return
        source = value
        markdownState.syncExternal(value)
        onSourceChange(value)
    }

    fun changeLanguage(value: String?) {
        if (submitting || closeRequested) return
        language = value
        if (editable) onSourceChange(source)
    }

    fun submit() {
        if (!disposed && !submitting && submitEnabled && !closeRequested) onSubmit?.invoke()
    }

    fun requestEdit() {
        if (!submitting && !closeRequested) onRequestEdit?.invoke()
    }

    fun update(
        source: String = this.source,
        language: String? = this.language,
        editable: Boolean = this.editable,
        submitting: Boolean = this.submitting,
        submitEnabled: Boolean = this.submitEnabled,
        error: String? = this.error,
    ) {
        this.source = source
        markdownState.syncExternal(source)
        this.language = language
        this.editable = editable
        this.submitting = submitting
        this.submitEnabled = submitEnabled
        this.error = error
    }

    fun openMarkdownDocument(
        title: String,
        filePath: String,
        pageUrl: String,
        repositoryRootUrl: String?,
        scrollToAnchor: String?,
    ) {
        if (disposed || closeRequested) return
        this.title = title
        this.filePath = filePath
        this.pageUrl = pageUrl
        this.repositoryRootUrl = repositoryRootUrl
        this.scrollToAnchor = scrollToAnchor
        readHtml = null
        readingOriginal = false
        update(
            source = "",
            editable = false,
            submitting = false,
            submitEnabled = false,
            error = null,
        )
        fileReadStatus = GithubFileReadStatus.Loading
    }

    fun openMarkdownLink(url: String): Boolean =
        !disposed && !closeRequested && onOpenMarkdownLink?.invoke(url) == true

    fun complete() {
        closeRequested = true
    }

    /** 仅在真实路由出栈时通知草稿拥有者，页面被覆盖不代表编辑结束。 */
    fun dispose() {
        if (disposed) return
        disposed = true
        onDismiss()
    }
}
