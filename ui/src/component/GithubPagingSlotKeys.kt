package moe.yumeyuka.yumehub.ui.component

/** 首屏骨架逐槽交给真实条目；追加占位交给首个新条目，其余项按业务 ID 跟随重排。 */
class GithubPagingSlotKeys(private val prefix: String) {
    private val aliases = mutableMapOf<String, String>()
    private var previousIds = emptySet<String>()
    private var pendingKey: String? = null
    private var initialKeys = emptyList<String>()
    private var sequence = 0L

    fun update(
        ids: List<String?>,
        loading: Boolean,
        initialLoading: Boolean = false,
    ): List<String> {
        if (initialLoading) {
            if (initialKeys.isEmpty()) initialKeys = List(4) { "$prefix:initial:${sequence++}" }
            return initialKeys
        }
        val currentIds = ids.filterNotNull().toSet()
        aliases.keys.retainAll(currentIds)
        initialKeys.forEachIndexed { index, key -> ids.getOrNull(index)?.let { aliases[it] = key } }
        initialKeys = emptyList()
        pendingKey?.let { key ->
            ids.firstOrNull { it != null && it !in previousIds }
                ?.let { id ->
                    aliases[id] = key
                    pendingKey = null
                }
        }
        if (loading) {
            if (pendingKey == null) pendingKey = "$prefix:loading:${sequence++}"
        } else pendingKey = null
        previousIds = currentIds
        return buildList {
            ids.forEachIndexed { index, id ->
                add(
                    if (id == null) "$prefix:unloaded:$index"
                    else aliases[id] ?: "$prefix:item:$id",
                )
            }
            pendingKey?.let(::add)
        }
    }
}
