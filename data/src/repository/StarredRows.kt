package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase

/** Row metadata is retained in-session only; membership always comes from the shared write slot. */
data class StarredRowChange(val repository: RepositoryDto?, val member: Boolean?, val phase: WritePhase)

internal class StarredRows(private val coordinator: MutationCoordinator) {
    private val candidates = MutableStateFlow<Map<String, RepositoryDto?>>(emptyMap())
    private fun key(slug: String) = MutationKey(MutationKind.STAR, slug, Boolean.serializer())

    @Synchronized fun track(slug: String, row: RepositoryDto?) {
        val id = slug.lowercase(Locale.ROOT)
        val previous = candidates.value
        val next = LinkedHashMap(previous)
        val retained = next.remove(id)
        next[id] = row ?: retained
        if (next.size > 64) next.keys.firstOrNull { it != id && coordinator.current(key(it)).phase.let { phase -> phase == WritePhase.IDLE || phase == WritePhase.REJECTED } }?.let(next::remove)
        candidates.value = next
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val changes: Flow<Map<String, StarredRowChange>> = candidates.flatMapLatest { rows ->
        if (rows.isEmpty()) flowOf(emptyMap()) else combine(rows.map { (slug, row) ->
            coordinator.observe(key(slug)).map { write ->
                slug to StarredRowChange(row, write.visible, write.phase)
            }
        }) { it.toMap() }
    }
}

internal fun CachedDataRepository.starredRows(account: String): StarredRows =
    sessionResource(account, "starred-row-metadata") { StarredRows(mutations(account)) }
