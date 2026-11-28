package moe.yumeyuka.yumehub.ui.component

import moe.yumeyuka.yumehub.ui.navigation.SkeletonEntrance

/** 首屏骨架逐槽交给真实条目；追加占位交给首个新条目，其余项按业务 ID 跟随重排。 */
class PagingSlotKeys(private val prefix: String) {
    internal val entrance = SkeletonEntrance()
    private val aliases = mutableMapOf<String, String>()
    private var previousIds = emptySet<String>()
    private var pendingKey: String? = null
    private var initialKeys = emptyList<String>()
    private var sequence = 0L
    internal var rejectedFrame: java.lang.ref.WeakReference<List<*>>? = null
        private set

    internal fun rejectFrame(frame: List<*>) {
        if (rejectedFrame?.get() !== frame) rejectedFrame = java.lang.ref.WeakReference(frame)
    }

    fun update(
        ids: List<String?>,
        loading: Boolean,
        initialLoading: Boolean = false,
    ): List<String> {
        if (initialLoading && ids.isEmpty()) {
            if (initialKeys.isEmpty()) initialKeys = List(4) { "$prefix:initial:${sequence++}" }
            return initialKeys
        }
        val currentIds = ids.filterNotNullTo(mutableSetOf())
        aliases.keys.retainAll(currentIds)
        initialKeys.forEachIndexed { index, key -> ids.getOrNull(index)?.let { aliases[it] = key } }
        initialKeys = emptyList()
        pendingKey?.let { key ->
            // A prepended entity must never inherit the append slot.
            val lastExisting = ids.indexOfLast { it != null && it in previousIds }
            if (lastExisting >= 0) {
                for (index in lastExisting + 1 until ids.size) {
                    val id = ids[index] ?: continue
                    aliases[id] = key
                    pendingKey = null
                    break
                }
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
