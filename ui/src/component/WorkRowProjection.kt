package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository
import moe.yumeyuka.yumehub.data.repository.WorkRowProjection
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import org.koin.compose.koinInject

private val NoWorkWrite = WorkRowProjection()

@Composable
fun rememberWorkRowProjection(target: WorkTarget?): WorkRowProjection {
    if (target == null) return NoWorkWrite
    val tokens: TokenStore = koinInject()
    val generation by tokens.sessionGeneration.collectAsStateWithLifecycle()
    val account = remember(tokens, generation) { tokens.accountLogin.value }
    if (account.isBlank()) return NoWorkWrite
    val repo: WorkDetailRepository = koinInject()
    return key(account, generation, target) {
        val overlay by remember(repo) { repo.rowProjection(account, target) }.collectAsStateWithLifecycle(NoWorkWrite)
        overlay
    }
}

@Composable
fun workMergeProgress(phase: WritePhase): String? = when (phase) {
    WritePhase.PENDING -> LocalGithubStrings.current.WorkDetail.Merging
    WritePhase.UNCERTAIN -> LocalGithubStrings.current.Common.MutationUncertain
    else -> null
}
