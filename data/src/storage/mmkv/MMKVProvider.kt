package moe.yumeyuka.yumehub.data.storage.mmkv

import com.tencent.mmkv.MMKV

interface KeyValueStorage {
    fun decodeString(key: String): String?

    fun encode(key: String, value: String): Boolean

    fun decodeBool(key: String, defaultValue: Boolean = false): Boolean

    fun encode(key: String, value: Boolean): Boolean

    fun decodeInt(key: String, defaultValue: Int = 0): Int

    fun encode(key: String, value: Int): Boolean

    fun decodeLong(key: String, defaultValue: Long = 0L): Long

    fun encode(key: String, value: Long): Boolean

    fun decodeFloat(key: String, defaultValue: Float = 0f): Float

    fun encode(key: String, value: Float): Boolean

    fun decodeStringSet(key: String): Set<String>?

    fun encode(key: String, value: Set<String>): Boolean

    fun removeValueForKey(key: String)

    fun removeValuesForKeys(keys: Array<String>)

    fun allKeys(): Array<String>?

    fun getValueActualSize(key: String): Int

    fun sync()
}

internal class RealMMKVStorage(private val mmkv: MMKV) : KeyValueStorage {
    override fun decodeString(key: String): String? = mmkv.decodeString(key)

    override fun encode(key: String, value: String): Boolean = mmkv.encode(key, value)

    override fun decodeBool(key: String, defaultValue: Boolean): Boolean =
        mmkv.decodeBool(key, defaultValue)

    override fun encode(key: String, value: Boolean): Boolean = mmkv.encode(key, value)

    override fun decodeInt(key: String, defaultValue: Int): Int =
        mmkv.decodeInt(key, defaultValue)

    override fun encode(key: String, value: Int): Boolean = mmkv.encode(key, value)

    override fun decodeLong(key: String, defaultValue: Long): Long =
        mmkv.decodeLong(key, defaultValue)

    override fun encode(key: String, value: Long): Boolean = mmkv.encode(key, value)

    override fun decodeFloat(key: String, defaultValue: Float): Float =
        mmkv.decodeFloat(key, defaultValue)

    override fun encode(key: String, value: Float): Boolean = mmkv.encode(key, value)

    override fun decodeStringSet(key: String): Set<String>? = mmkv.decodeStringSet(key)

    override fun encode(key: String, value: Set<String>): Boolean = mmkv.encode(key, value)

    override fun removeValueForKey(key: String) {
        mmkv.removeValueForKey(key)
    }

    override fun removeValuesForKeys(keys: Array<String>) {
        mmkv.removeValuesForKeys(keys)
    }

    override fun allKeys(): Array<String>? = mmkv.allKeys()

    override fun getValueActualSize(key: String): Int = mmkv.getValueActualSize(key)

    override fun sync() {
        mmkv.sync()
    }
}

class MMKVProvider
internal constructor(
    private val storageFactory: ((String) -> KeyValueStorage)?,
) {
    constructor() : this(null)

    // 应用是单进程，默认单进程模式省去跨进程锁开销。
    fun getMMKV(id: String): MMKV = MMKV.mmkvWithID(id)

    internal fun getStorage(id: String): KeyValueStorage =
        storageFactory?.invoke(id) ?: RealMMKVStorage(getMMKV(id))

    companion object {
        fun inMemory(factory: (String) -> KeyValueStorage): MMKVProvider = MMKVProvider(factory)
    }
}
