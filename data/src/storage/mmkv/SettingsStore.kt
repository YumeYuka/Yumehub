package moe.yumeyuka.yumehub.data.storage.mmkv

import android.util.Log
import com.tencent.mmkv.MMKV
import kotlin.properties.ReadOnlyProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

enum class ThemeMode {
    System,
    Light,
    Dark,
}

/** GitHub green; kept as a plain ARGB int so the storage layer stays UI-free. */
const val DefaultAccentColorArgb: Int = 0xFF3FB950.toInt()

/** Predictive back amplitude, as a percentage of the full gesture distance. */
const val DefaultPredictiveBackProgress: Float = 50f
const val MinPredictiveBackProgress: Float = 1f
const val MaxPredictiveBackProgress: Float = 100f

class SettingsStore internal constructor(storage: KeyValueStorage) :
    MMKVPreference(mmkv = storage) {
    constructor(externalMmkv: MMKV) : this(RealMMKVStorage(externalMmkv))

    companion object {
        fun forTesting(storage: KeyValueStorage): SettingsStore = SettingsStore(storage)
    }
    private val pendingWrites = mutableMapOf<String, () -> Unit>()
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            for (signal in writeSignal) {
                val writes = synchronized(pendingWrites) {
                    pendingWrites.values.toList().also { pendingWrites.clear() }
                }
                for (write in writes) {
                    try {
                        write()
                    } catch (error: RuntimeException) {
                        Log.w("SettingsStore", "Unable to persist setting", error)
                    }
                }
            }
        }
    }

    override fun persistPreference(key: String, write: () -> Unit) {
        synchronized(pendingWrites) { pendingWrites[key] = write }
        writeSignal.trySend(Unit)
    }

    val themeMode by enumFlow(ThemeMode.System)
    val accentColor by intFlow(DefaultAccentColorArgb)
    val liquidGlassEnabled by boolFlow(false)
    // 默认开启：玻璃合成在低端机/长列表滚动下开销明显，默认走纯色更稳。
    val reduceTransparency by boolFlow(true)
    val topBarBlurEnabled by boolFlow(false)
    val predictiveBackEnabled by boolFlow(false)
    val predictiveBackMaxProgress by
        normalizedFloatFlow(DefaultPredictiveBackProgress) { value ->
            if (value.isFinite()) {
                value.coerceIn(MinPredictiveBackProgress, MaxPredictiveBackProgress)
            } else {
                DefaultPredictiveBackProgress
            }
        }
    val keepBottomBarExpanded by boolFlow(false)
    val glassHdrEnabled by boolFlow(false)
    val fpsCounter by boolFlow(false)
    val profileBackgroundUri by strFlow("")
    val exploreFeedFilters by
        stringSetFlow(
            setOf("Announcements", "Releases", "Stars", "Repositories", "Follows"),
        )
    val exploreFeedSort by strFlow("Newest")
    val homeWorkItems by
        stringListFlow(
            listOf("Issues", "PullRequests", "Repositories", "Organizations", "Starred"),
        )
    val homeWorkLayout by stringListFlow()

    /** 预读首帧及设置页所需的偏好；由应用启动任务在 IO 调度器调用。 */
    fun loadUiPreferences() {
        themeMode
        accentColor
        liquidGlassEnabled
        reduceTransparency
        topBarBlurEnabled
        predictiveBackEnabled
        predictiveBackMaxProgress
        keepBottomBarExpanded
        glassHdrEnabled
        fpsCounter
        homeWorkItems
        homeWorkLayout
    }

    private fun normalizedFloatFlow(default: Float, normalize: (Float) -> Float) =
        normalizedFlow(
            default = default,
            getter = { key, fallback -> mmkv.decodeFloat(key, fallback) },
            setter = { key, value -> mmkv.encode(key, value) },
            normalize = normalize,
        )

    // 读取时直接归一化，不依赖协程或回写；写入前也归一化，保证 StateFlow 只发布合法值
    private fun <T> normalizedFlow(
        default: T,
        getter: (String, T) -> T,
        setter: (String, T) -> Unit,
        normalize: (T) -> T,
    ): ReadOnlyProperty<MMKVPreference, Preference<T>> {
        val delegate =
            MMKVFlowProperty(
                default = default,
                getter = { key, fallback -> normalize(getter(key, fallback)) },
                setter = setter,
            )
        var cached: Preference<T>? = null
        return ReadOnlyProperty { thisRef, property ->
            cached
                ?: delegate.getValue(thisRef, property).let { preference ->
                    Preference(
                            state = preference.state,
                            update = { preference.set(normalize(it)) },
                            get = { preference.value },
                            refreshState = { preference.refresh() },
                        )
                        .also { cached = it }
                }
        }
    }
}
