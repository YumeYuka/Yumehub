package moe.yumeyuka.yumehub.data.storage.mmkv

import com.tencent.mmkv.MMKV

class MMKVProvider {
    // 应用是单进程，默认单进程模式省去跨进程锁开销。
    fun getMMKV(id: String): MMKV = MMKV.mmkvWithID(id)
}
