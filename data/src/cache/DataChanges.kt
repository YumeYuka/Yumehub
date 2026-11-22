package moe.yumeyuka.yumehub.data.cache

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.yumeyuka.yumehub.core.model.WorkTarget

/** 失效版本属于账号会话，离开组合的页面重新订阅时仍能发现变化。 */
enum class DataDomain {
    Work,
    Notifications,
    Repositories,
    Relationships,
    Profile,
    Content,
}

class DataChanges {
    private val mutableVersions = MutableStateFlow<Map<DataDomain, Long>>(emptyMap())
    val versions = mutableVersions.asStateFlow()
    private val merged = LinkedHashSet<WorkTarget>()

    @Synchronized
    fun publish(vararg domains: DataDomain) {
        val next = mutableVersions.value.toMutableMap()
        domains.forEach { next[it] = (next[it] ?: 0L) + 1L }
        mutableVersions.value = next
    }

    @Synchronized
    fun confirmMerged(target: WorkTarget) {
        merged.add(target.normalized())
        // 有界保留近期不可逆的合并结果，防止延迟的搜索索引覆盖刚确认的状态。
        if (merged.size > 512) merged.remove(merged.first())
        publish(DataDomain.Work, DataDomain.Notifications, DataDomain.Repositories)
    }

    @Synchronized fun isMerged(target: WorkTarget): Boolean = target.normalized() in merged

    private fun WorkTarget.normalized() =
        copy(
            owner = owner.lowercase(Locale.ROOT),
            repository = repository.lowercase(Locale.ROOT),
        )
}

object DataFreshness {
    const val ActiveMillis = 60_000L
}
