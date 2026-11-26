package moe.yumeyuka.yumehub.feature.actions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.CommandState
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase

internal class WorkflowCommands(
    private val coordinator: MutationCoordinator,
    private val kind: MutationKind,
    private val scope: CoroutineScope,
) {
    private val outcomes = Channel<WritePhase>(Channel.BUFFERED)
    val results = outcomes.receiveAsFlow()

    private fun command(target: String) = MutationKey(kind, target, CommandAccepted.serializer())

    @Composable
    fun stateFor(target: String?): CommandState<CommandAccepted>? {
        if (target == null) return null
        return key(this, target) {
            remember { coordinator.observeCommand(command(target)) }.collectAsStateWithLifecycle().value
        }
    }

    fun submit(target: String, send: suspend () -> Unit): Boolean {
        val key = command(target)
        val receipt = coordinator.submitCommand(key, WorkflowTags, send = { send(); CommandAccepted }, readBack = { null })
        if (receipt == null) {
            val phase = coordinator.currentCommand(key).phase
            if (phase != WritePhase.PENDING && phase != WritePhase.UNCERTAIN) outcomes.trySend(WritePhase.REJECTED)
            return false
        }
        scope.launch { outcomes.send(receipt.await().phase) }
        return true
    }

    fun acknowledge(target: String, submission: String) {
        val current = coordinator.currentCommand(command(target))
        if (current.phase == WritePhase.UNCERTAIN && current.submissionId == submission) coordinator.acknowledge(submission)
    }
}

private val WorkflowTags = setOf(CacheTag(DataDomain.Work, null))
