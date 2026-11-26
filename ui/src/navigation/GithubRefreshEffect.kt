package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.DataFreshness
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import org.koin.compose.koinInject

/** 只有真实可见页面校验数据；预测返回露出的下层不会触发刷新。 */
@Composable
fun GithubRefreshEffect(
    owner: Any,
    vararg domains: DataDomain,
    refreshOnFirstActivation: Boolean = true,
    enabled: Boolean = true,
    pollWhileVisible: Boolean = false,
    onRefresh: () -> Unit,
) {
    val host = LocalGithubPageHost.current
    val page = LocalGithubPageState.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val cache: CachedDataRepository = koinInject()
    val tokens: TokenStore = koinInject()
    val account = tokens.accountLogin.value
    if (account.isBlank()) return
    val generation = tokens.sessionGeneration.value
    val changes = remember(cache, account, generation) { cache.changes(account) }
    val gate =
        remember(page, owner, changes) { page?.refreshGate(owner to changes) ?: PageRefreshGate() }
    val refresh by rememberUpdatedState(onRefresh)
    val dependencies by rememberUpdatedState(domains.toSet())
    val refreshEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(owner, lifecycle, host, changes, pollWhileVisible) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            combine(
                    snapshotFlow { host?.isCurrent != false && refreshEnabled },
                    changes.versions,
                ) { active, versions ->
                    active to versions.filterKeys { it in dependencies }
                }
                .distinctUntilChanged()
                .collectLatest { (active, versions) ->
                    if (!active) return@collectLatest
                    do {
                        if (gate.acquire(monotonicMillis(), versions, refreshOnFirstActivation)) {
                            refresh()
                        }
                        if (pollWhileVisible) {
                            delay(gate.millisUntilNextCheck(monotonicMillis()).coerceAtLeast(1L))
                        }
                    } while (pollWhileVisible)
                }
        }
    }
}

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

internal class PageRefreshGate {
    private var checkedAt: Long? = null
    private var versions: Map<DataDomain, Long> = emptyMap()

    fun millisUntilNextCheck(now: Long): Long {
        val previous = checkedAt ?: return 0L
        val elapsed = now - previous
        return if (elapsed < 0L) 0L else (DataFreshness.ActiveMillis - elapsed).coerceAtLeast(0L)
    }

    fun acquire(
        now: Long,
        current: Map<DataDomain, Long>,
        refreshInitially: Boolean = true,
    ): Boolean {
        val previous = checkedAt
        if (millisUntilNextCheck(now) > 0L && current == versions) return false
        checkedAt = now
        versions = current.toMap()
        return previous != null || refreshInitially
    }
}
