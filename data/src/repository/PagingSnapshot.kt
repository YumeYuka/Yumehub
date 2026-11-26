package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.diskValue
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotStore
import moe.yumeyuka.yumehub.data.storage.mmkv.ResponseCacheStore

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
    val tags: Set<CacheTag> = emptySet(),
) {
    private var session: String? = null
    private var diskKey: String? = null
    private var revision = -1L
    private var diskTags: List<String> = emptyList()

    internal fun bind(context: PagingContext): PagingSnapshot<T> =
        PagingSnapshot(store, account, key, serializer, context, tags)

    suspend fun initialPage(pageSize: Int): PagingSnapshotPage<T>? {
        val bound = context ?: return null
        bound.ensureCurrent()
        val id = bound.sessionId()
        val identity =
            ResponseCacheStore.key(
                id,
                account.lowercase(),
                key,
                pageSize.toString(),
                serializer.descriptor.toString(),
            )
        val labels = (bound.snapshotTags + tags).map { it.diskValue() }
        session = id
        diskKey = identity
        diskTags = labels
        val page =
            try {
                withContext(Dispatchers.IO) {
                    revision = bound.cache.invalidationStore.revision(account, labels)
                    bound.state.frame<T>(revision)
                        ?: if (bound.persistenceEnabled()) {
                            store.load(id, identity, serializer, revision) { bound.active() }
                        } else null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        bound.ensureCurrent()
        if (page == null || page.pageSize != pageSize) return null
        return when {
            page.boundaryInvalid -> page.copy(prevKey = null, nextKey = null, nextCursor = null)
            !bound.initialSnapshot && bound.forceRefresh -> page.copy(updatedAt = 0L)
            else -> page
        }
    }

    /** 首屏已可显示时，磁盘快照在同一分页代际后台写入。 */
    fun saveInBackground(page: PagingSnapshotPage<T>) {
        val bound = context ?: return
        val sequence = bound.commit { bound.state.remember(page, revision) }
        val id = session ?: return
        val key = diskKey ?: return
        if (revision < 0 || !bound.persistenceEnabled()) return
        store.save(bound.cache.writeQueue, id, key, serializer, page, diskTags, revision) {
            bound.validFrame(sequence)
        }
    }

    fun save(page: PagingSnapshotPage<T>) = saveInBackground(page)

    internal suspend fun discardDenied(error: Throwable) {
        if (!error.invalidatesCachedContent()) return
        val bound = context ?: return
        val sequence = bound.commit { bound.state.forget() }
        val id = session ?: return
        val key = diskKey ?: return
        bound.cache.writeQueue.run {
            store.disk.remove(id, key) { bound.validFrame(sequence) }
        }
    }
}
