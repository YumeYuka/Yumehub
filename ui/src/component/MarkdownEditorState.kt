package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

enum class MarkdownEditAction {
    Bold,
    Italic,
    Strike,
    InlineCode,
    Link,
    Quote,
    BulletedList,
    NumberedList,
    TaskList,
    Heading,
    CodeBlock,
}

class MarkdownEditorState(initialText: String) {
    private var currentValue by mutableStateOf(TextFieldValue(initialText))
    private var undoAvailable by mutableStateOf(false)
    private var redoAvailable by mutableStateOf(false)

    val value: TextFieldValue
        get() = currentValue

    val canUndo: Boolean
        get() = undoAvailable

    val canRedo: Boolean
        get() = redoAvailable

    private val undoValues = ArrayDeque<TextFieldValue>()
    private val redoValues = ArrayDeque<TextFieldValue>()
    private var lastEditNanos: Long? = null

    fun edit(value: TextFieldValue) {
        val next = value.copy(selection = markdownSelection(value.text, value.selection))
        if (next.text == this.value.text) {
            if (next.selection != this.value.selection) lastEditNanos = null
            currentValue = next
            return
        }
        val now = System.nanoTime()
        val previousTime = lastEditNanos
        if (previousTime == null || now - previousTime !in 0..800_000_000L) {
            rememberValue(undoValues, this.value)
        }
        redoValues.clear()
        currentValue = next
        lastEditNanos = now
        publishHistory()
    }

    fun syncExternal(source: String) {
        if (source == value.text) return
        currentValue = TextFieldValue(source, markdownSelection(source, value.selection))
        undoValues.clear()
        redoValues.clear()
        lastEditNanos = null
        publishHistory()
    }

    fun apply(action: MarkdownEditAction) {
        lastEditNanos = null
        val next = transformMarkdown(value.copy(composition = null), action)
        if (next.text != value.text) {
            rememberValue(undoValues, value)
            redoValues.clear()
        }
        currentValue = next
        publishHistory()
    }

    fun undo() {
        lastEditNanos = null
        if (undoValues.isEmpty()) return
        val previous = undoValues.removeLast()
        rememberValue(redoValues, value)
        currentValue = previous
        publishHistory()
    }

    fun redo() {
        lastEditNanos = null
        if (redoValues.isEmpty()) return
        val next = redoValues.removeLast()
        rememberValue(undoValues, value)
        currentValue = next
        publishHistory()
    }

    private fun rememberValue(history: ArrayDeque<TextFieldValue>, snapshot: TextFieldValue) {
        if (snapshot.text.length > MaxHistoryChars) {
            // 无法保留相邻状态时截断该方向，避免跳过这一事务进行撤销或重做。
            history.clear()
            return
        }
        history.addLast(snapshot.copy(composition = null))
    }

    private fun publishHistory() {
        var characters =
            undoValues.sumOf { it.text.length.toLong() } +
                redoValues.sumOf { it.text.length.toLong() }
        while (
            undoValues.size + redoValues.size > MaxHistoryEntries || characters > MaxHistoryChars
        ) {
            val removed =
                if (undoValues.isNotEmpty()) undoValues.removeFirst() else redoValues.removeFirst()
            characters -= removed.text.length
        }
        undoAvailable = undoValues.isNotEmpty()
        redoAvailable = redoValues.isNotEmpty()
    }
}

private const val MaxHistoryEntries = 150
private const val MaxHistoryChars = 2_000_000L
