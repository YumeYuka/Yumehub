package moe.yumeyuka.yumehub.data.cache

import java.security.MessageDigest
import java.util.Locale

/**
 * 缓存失效标签。[subject] 是规范化的稳定业务 ID；null 表示该 domain 的全域视图。
 *
 * 磁盘只保存 domain、subject 类型标记和 SHA-256 摘要，不保存 token、URL 或原始业务 key。 subject 失效会同时影响该 subject 和该 domain
 * 的全域视图；null 失效影响整个 domain。
 */
data class CacheTag(val domain: DataDomain, val subject: String? = null) {
    init {
        require(subject == null || subject.isNotBlank()) { "A cache tag subject must not be blank" }
    }

    val digest: String
        get() = tagDigest(domain, subject)
}

internal fun tagDigest(domain: DataDomain, subject: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(domain.name.toByteArray(Charsets.UTF_8))
    digest.update(0)
    subject?.let { digest.update(it.toByteArray(Charsets.UTF_8)) }
    return digest.digest().toHexString()
}

/** 磁盘表示：domain 与 subject 类型用于判定全域/具体视图，摘要隐藏原始业务 ID。 */
internal fun CacheTag.diskValue(): String =
    "${domain.name}|${if (subject == null) NullSubject else Subject}|$digest"

internal fun diskDomain(value: String): String = value.substringBefore('|')

internal fun diskDigest(value: String): String = value.substringAfterLast('|')

internal fun diskIsGlobal(value: String): Boolean =
    value.substringBeforeLast('|').endsWith("|$NullSubject")

private const val NullSubject = "0"
private const val Subject = "1"

/** 仓库视图使用小写 `owner/repo`。 */
fun repositoryCacheTag(owner: String, name: String): CacheTag =
    CacheTag(
        DataDomain.Repositories,
        "${owner.lowercase(Locale.ROOT)}/${name.lowercase(Locale.ROOT)}",
    )

fun workListCacheTag(owner: String, repository: String): CacheTag =
    CacheTag(
        DataDomain.Work,
        "${owner.lowercase(Locale.ROOT)}/${repository.lowercase(Locale.ROOT)}",
    )

/** 关系状态（star/follow）按具体对象区分。 */
fun relationshipCacheTag(subject: String): CacheTag =
    CacheTag(DataDomain.Relationships, subject.lowercase(Locale.ROOT))

/** Work 使用 `kind:owner/repo:number`，kind 为稳定的枚举名。 */
fun workCacheTag(kind: String, owner: String, repository: String, number: Int): CacheTag =
    CacheTag(
        DataDomain.Work,
        "${kind.lowercase(Locale.ROOT)}:${owner.lowercase(Locale.ROOT)}/" +
            "${repository.lowercase(Locale.ROOT)}:$number",
    )

/** 一个失效标签是否覆盖另一个（资源自身）标签。 */
internal fun CacheTag.covers(other: CacheTag): Boolean =
    domain == other.domain && (subject == null || other.subject == null || subject == other.subject)
