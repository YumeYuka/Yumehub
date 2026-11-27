package moe.yumeyuka.yumehub.feature.markdown

import android.content.Context
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.neoruaa.glasmio.core.GlasmioTheme
import java.lang.ref.WeakReference
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubDocumentGeometry
import org.koin.compose.koinInject

private val LocalPresentations = staticCompositionLocalOf<MarkdownPresentations?> { null }

/** Detached presentations belong to this window and account, never to an Activity-retained VM. */
@Composable
fun MarkdownPresentationHost(content: @Composable () -> Unit) {
    val repository: GithubMarkdownRepository = koinInject()
    val session = rememberMarkdownSession(repository)
    val cache = remember(LocalView.current, LocalContext.current, LocalConfiguration.current,
        LocalDensity.current, LocalLayoutDirection.current, GlasmioTheme.colors.isDark, session) {
        MarkdownPresentations(session.isCurrent)
    }
    DisposableEffect(cache) { onDispose { cache.close() } }
    CompositionLocalProvider(LocalPresentations provides cache, content = content)
}

/** Route destruction invalidates detached views even if that route never enters composition again. */
internal class MarkdownPresentationOwner : ViewModel() {
    private var host: WeakReference<MarkdownPresentations>? = null
    var live = true
        private set

    fun attach(cache: MarkdownPresentations) {
        val previous = host?.get()
        if (previous === cache) return
        previous?.remove(this)
        host = WeakReference(cache)
    }

    override fun onCleared() {
        live = false
        host?.get()?.remove(this)
        host = null
    }
}

internal data class MarkdownPresentationKey(
    val owner: MarkdownPresentationOwner,
    val geometry: GithubDocumentGeometry,
    val layout: Any,
    val width: Int,
    val window: IBinder,
    val context: Context,
)

internal class MarkdownPresentations(private val currentSession: () -> Boolean) {
    private data class Parked(val key: MarkdownPresentationKey, val view: MarkdownWebView)
    private val parked = LinkedHashMap<GithubDocumentGeometry, Parked>()
    private var closed = false

    fun take(key: MarkdownPresentationKey, document: NativeMarkdownDocument, height: Int): MarkdownWebView? {
        val entry = parked.remove(key.geometry) ?: return null
        if (entry.key == key && !closed && currentSession() && key.owner.live && height > 0 &&
            entry.view.canRestore(document, key.width, height)) return entry.view
        entry.view.release()
        return null
    }

    fun park(key: MarkdownPresentationKey, view: MarkdownWebView): Boolean {
        if (closed || !currentSession() || !key.owner.live || view.context !== key.context ||
            !view.canPark(key.width)) return false
        key.owner.attach(this)
        view.park()
        parked.remove(key.geometry)?.view?.release()
        parked[key.geometry] = Parked(key, view)
        if (parked.size > 2) {
            val entries = parked.entries.iterator()
            val oldest = entries.next().value
            entries.remove()
            oldest.view.release()
        }
        return true
    }

    fun remove(owner: MarkdownPresentationOwner) {
        val entries = parked.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.value.key.owner === owner) {
                entries.remove()
                entry.value.view.release()
            }
        }
    }

    fun close() {
        closed = true
        parked.values.forEach { it.view.release() }
        parked.clear()
    }
}

/** An exclusive claim: two simultaneous compositions must never reveal or attach the same view. */
internal class MarkdownPresentation(
    private val cache: MarkdownPresentations,
    private val key: MarkdownPresentationKey,
    private var claimed: MarkdownWebView?,
) : RememberObserver {
    val restored = claimed != null

    fun consume(): MarkdownWebView? = claimed.also { claimed = null }
    fun park(view: MarkdownWebView): Boolean = cache.park(key, view)

    override fun onRemembered() = Unit
    override fun onForgotten() { consume()?.release() }
    override fun onAbandoned() { consume()?.release() }
}

@Composable
internal fun rememberMarkdownPresentation(
    document: NativeMarkdownDocument,
    layout: Any,
    width: Int,
    restoredHeight: Int,
): MarkdownPresentation? {
    val cache = LocalPresentations.current ?: return null
    val window = LocalView.current.windowToken ?: return null
    val context = LocalContext.current
    val owner: MarkdownPresentationOwner = viewModel()
    return remember(cache, document.revision, layout, width, window, context, owner) {
        val key = MarkdownPresentationKey(owner, document.geometry, layout, width, window, context)
        MarkdownPresentation(cache, key, cache.take(key, document, restoredHeight))
    }
}
