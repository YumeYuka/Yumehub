package moe.yumeyuka.yumehub.data.cache

data class CachedDataState<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: Throwable? = null,
    val updatedAt: Long = 0L,
)
