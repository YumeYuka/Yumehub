package moe.yumeyuka.yumehub.data.repository

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotStore

/**
 * 分页列表的首屏快照配置。
 *
 * [account] 用于把缓存隔离到当前账号；[key] 区分同一账号下的筛选或仓库；[serializer] 描述原始 DTO，界面模型不进缓存。
 */
class PagingSnapshot<T : Any>(
    val store: PagingSnapshotStore,
    val account: String,
    val key: String,
    val serializer: KSerializer<T>,
    internal val context: PagingContext? = null,
) {
    private val initial = AtomicBoolean(true)

    internal fun bind(context: PagingContext): PagingSnapshot<T> =
        PagingSnapshot(store, account, key, serializer, context)

    suspend fun initialPage(
        pageSize: Int,
        includeRevalidated: Boolean = false,
    ): PagingSnapshotPage<T>? {
        // 无会话上下文的旧调用不能恢复账号级私有快照。
        val bound = context ?: return null
        if (!bound.initialSnapshot && !(includeRevalidated && !bound.forceRefresh)) return null
        if (!initial.compareAndSet(true, false)) return null
        bound.ensureCurrent()
        val diskKey = bound.snapshotKey(key)
        val page =
            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    store.loadPageNow(account, diskKey, serializer, bound::ensureCurrent)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        bound.ensureCurrent()
        return page?.takeIf { it.pageSize == pageSize }
    }

    /** 首屏已可显示时，磁盘快照在同一分页代际后台写入。 */
    fun saveInBackground(page: PagingSnapshotPage<T>) {
        context?.persist { save(page) }
    }

    suspend fun save(page: PagingSnapshotPage<T>) {
        val bound = context ?: return
        val diskKey = bound.snapshotKey(key)
        try {
            withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                coroutine.ensureActive()
                store.savePageNow(account, diskKey, serializer, page) { write ->
                    coroutine.ensureActive()
                    bound.commit(write)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 快照写入失败不能使已成功的网络分页变成错误页。
        }
    }
}
