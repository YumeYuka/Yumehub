package moe.yumeyuka.yumehub.data.markdown

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.utf8Size

/** 键只记录会话代际与内容摘要，不保留令牌或 Markdown 原文。 */
internal data class MarkdownHtmlKey(
    val generation: Long,
    val sourceVersion: String,
    val page: MarkdownRepositoryUrl?,
    val context: String?,
    val nodeId: String?,
    val readme: Boolean,
    val ref: String?,
    val preview: Boolean,
)

// UTF-16 保留原始 String 的每个代码单元，避免非法代理字符经 UTF-8 替换后碰撞。
internal fun markdownSourceVersion(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (character in text) {
        digest.update((character.code ushr 8).toByte())
        digest.update(character.code.toByte())
    }
    return digest.digest().toHexString()
}

/** 只保留正在处理或有人等待的 key；不同正文即使哈希碰撞也不共用网络锁。 */
internal class MarkdownRequestLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val entries = mutableMapOf<MarkdownHtmlKey, Entry>()

    suspend fun <T> withKey(key: MarkdownHtmlKey, read: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { read() }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}

/** 纯 JVM 内存 LRU；所有会话、容量与条目操作共用同一把短锁，不在锁内请求网络。 */
internal class MarkdownHtmlCache(
    initialLogin: String,
    initialToken: String,
    initialSession: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private class Entry(val html: String, val bytes: Int, val createdAt: Long)

    // 凭据只参与会话有效性比较；不要生成 toString、日志或持久化数据。
    private var login = initialLogin
    private var token = initialToken
    private var session = initialSession
    private val revision = MutableStateFlow(0L)
    val generation = revision.asStateFlow()
    private val entries = LinkedHashMap<MarkdownHtmlKey, Entry>(16, 0.75f, true)
    private var htmlBytes = 0L

    @Synchronized
    fun observeLogin(value: String) {
        if (login != value) {
            login = value
            invalidate()
        }
    }

    @Synchronized
    fun observeToken(value: String) {
        if (token != value) {
            token = value
            invalidate()
        }
    }

    @Synchronized
    fun observeSession(value: Long) {
        if (session != value) {
            session = value
            invalidate()
        }
    }

    @Synchronized fun isCurrent(value: Long): Boolean = generation.value == value

    @Synchronized
    fun get(key: MarkdownHtmlKey): String? {
        if (key.generation != generation.value) return null
        val entry = entries[key] ?: return null
        if (nowNanos() - entry.createdAt >= TtlNanos) {
            remove(key)
            return null
        }
        return entry.html
    }

    @Synchronized
    fun remove(key: MarkdownHtmlKey) {
        entries.remove(key)?.let { htmlBytes -= it.bytes }
    }

    /** 只计算 UTF-8 长度，无需创建字节副本；取得锁后再次验证代际。 */
    fun put(key: MarkdownHtmlKey, html: String): Boolean {
        if (html.length > MaxHtmlBytes) throw oversizedHtml()
        val bytes = html.utf8Size().toInt()
        if (bytes > MaxHtmlBytes) throw oversizedHtml()
        return synchronized(this) {
            if (key.generation != generation.value) return@synchronized false
            remove(key)
            val now = nowNanos()
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (now - entry.createdAt >= TtlNanos) {
                    htmlBytes -= entry.bytes
                    iterator.remove()
                }
            }
            entries[key] = Entry(html, bytes, now)
            htmlBytes += bytes
            val oldest = entries.entries.iterator()
            while (entries.size > MaxEntries || htmlBytes > MaxTotalBytes) {
                htmlBytes -= oldest.next().value.bytes
                oldest.remove()
            }
            true
        }
    }

    private fun invalidate() {
        entries.clear()
        htmlBytes = 0
        revision.value++
    }

    private companion object {
        const val TtlNanos = 60_000_000_000L
        const val MaxEntries = 16
        const val MaxTotalBytes = 8 * 1024 * 1024
    }
}
