package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class ThreadPage<T>(val items: List<T>, val before: String?, val after: String?)

internal fun JsonObject.threadCursor(previous: Boolean): String? {
    val flag = if (previous) "hasPreviousPage" else "hasNextPage"
    val more = (get(flag) as? JsonPrimitive)?.booleanOrNull ?: throw GithubPageException()
    return if (more) graphRequiredText(if (previous) "startCursor" else "endCursor") else null
}

enum class ThreadDirection {
    Latest,
    Older,
    Newer,
}

data class ThreadWindowState<T>(
    val items: List<T> = emptyList(),
    val before: String? = null,
    val after: String? = null,
    val ready: Boolean = false,
    val loading: ThreadDirection? = null,
    val failed: ThreadDirection? = null,
    val error: Throwable? = null,
)

/** 页面主线程拥有窗口。刷新换代，翻页只由用户操作触发。 */
class ThreadWindow<T>
internal constructor(
    private val scope: CoroutineScope,
    private val identity: (T) -> String,
    private val fetch: suspend (ThreadDirection, String?) -> ThreadPage<T>,
) {
    private val mutable = MutableStateFlow(ThreadWindowState<T>())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private val olderCursors = mutableSetOf<String>()
    private val newerCursors = mutableSetOf<String>()

    fun load(direction: ThreadDirection) {
        val old = mutable.value
        val cursor =
            when (direction) {
                ThreadDirection.Latest -> {
                    request?.cancel()
                    null
                }
                ThreadDirection.Older -> old.before ?: return
                ThreadDirection.Newer -> old.after ?: return
            }
        if (direction != ThreadDirection.Latest && old.loading != null) return
        mutable.value = old.copy(loading = direction, failed = null, error = null)
        request = scope.launch {
            try {
                val page = fetch(direction, cursor)
                currentCoroutineContext().ensureActive()
                val items =
                    when (direction) {
                        ThreadDirection.Latest -> page.items
                        ThreadDirection.Older -> page.items + old.items
                        ThreadDirection.Newer -> old.items + page.items
                    }.distinctBy(identity)
                if (direction == ThreadDirection.Latest) {
                    olderCursors.clear()
                    newerCursors.clear()
                }
                val before = if (direction == ThreadDirection.Newer) old.before else page.before
                val after = if (direction == ThreadDirection.Older) old.after else page.after
                if (
                    (direction != ThreadDirection.Newer &&
                        before != null &&
                        !olderCursors.add(before)) ||
                        (direction != ThreadDirection.Older &&
                            after != null &&
                            !newerCursors.add(after))
                )
                    throw GithubPageException()
                mutable.value = ThreadWindowState(items, before, after, ready = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (error.invalidatesCachedContent()) {
                    olderCursors.clear()
                    newerCursors.clear()
                    mutable.value =
                        ThreadWindowState(failed = ThreadDirection.Latest, error = error)
                } else mutable.value = old.copy(loading = null, failed = direction, error = error)
            }
        }
    }

    fun updateItems(update: (T) -> T) {
        val current = mutable.value
        if (current.items.isNotEmpty()) {
            mutable.value = current.copy(items = current.items.map(update))
        }
    }
}
