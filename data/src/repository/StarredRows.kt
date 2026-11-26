package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase

/** Row metadata is retained in-session only; membership always comes from the shared write slot. */
data class StarredRowChange(
    val repository: RepositoryDto?,
    val member: Boolean?,
    val phase: WritePhase,
)

internal class RepositoryRowWrites<T : Any>(
    private val coordinator: MutationCoordinator,
    private val key: (String) -> MutationKey<T>,
) {
    private data class Candidate(val row: RepositoryDto?, val lists: Set<String>?)

    private val candidates = MutableStateFlow<Map<String, Candidate>>(emptyMap())

    @Synchronized
    fun track(slug: String, row: RepositoryDto?, lists: Set<String>? = null) {
        val id = key(slug).target
        val previous = candidates.value
        val next = LinkedHashMap(previous)
        val retained = next.remove(id)
        next[id] = Candidate(row ?: retained?.row, lists?.let { retained?.lists.orEmpty() + it })
        if (next.size > 64)
            next.keys
                .firstOrNull {
                    it != id &&
                        coordinator.current(key(it)).phase.let { phase ->
                            phase == WritePhase.IDLE || phase == WritePhase.REJECTED
                        }
                }
                ?.let(next::remove)
        candidates.value = next
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun changes(
        listId: String? = null,
        member: (T) -> Boolean,
    ): Flow<Map<String, StarredRowChange>> = candidates.flatMapLatest { rows ->
        val sources = rows.mapNotNull { (id, candidate) ->
            if (listId != null && listId !in candidate.lists.orEmpty()) return@mapNotNull null
            coordinator.observe(key(id)).map { write ->
                (candidate.row?.fullName?.lowercase(Locale.ROOT) ?: id) to
                    StarredRowChange(candidate.row, write.visible?.let(member), write.phase)
            }
        }
        if (sources.isEmpty()) flowOf(emptyMap()) else combine(sources) { it.toMap() }
    }
}

internal fun CachedDataRepository.starredRows(account: String): RepositoryRowWrites<Boolean> =
    sessionResource(account, "starred-row-metadata") {
        RepositoryRowWrites(mutations(account)) { slug ->
            MutationKey(MutationKind.STAR, slug.lowercase(Locale.ROOT), Boolean.serializer())
        }
    }

fun repositoryListsKey(id: String) =
    MutationKey(MutationKind.REPO_LISTS, id, ListSerializer(String.serializer()))

internal fun CachedDataRepository.listRows(account: String): RepositoryRowWrites<List<String>> =
    sessionResource(account, "list-row-metadata") {
        RepositoryRowWrites(mutations(account), ::repositoryListsKey)
    }
