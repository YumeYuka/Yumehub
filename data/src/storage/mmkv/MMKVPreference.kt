package moe.yumeyuka.yumehub.data.storage.mmkv

import com.tencent.mmkv.MMKV
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

abstract class MMKVPreference(
    mmkvID: String? = null,
    externalMmkv: MMKV? = null,
) {
    @PublishedApi
    internal val mmkv: MMKV =
        externalMmkv ?: mmkvID?.let { MMKV.mmkvWithID(it) } ?: MMKV.defaultMMKV()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    protected open fun persistPreference(key: String, write: () -> Unit) = write()

    protected fun bool(default: Boolean = false) =
        MMKVProperty(
            default = default,
            getter = { key, defaultValue -> mmkv.decodeBool(key, defaultValue) },
            setter = { key, value -> mmkv.encode(key, value) },
        )

    protected fun boolFlow(default: Boolean = false) =
        MMKVFlowProperty(
            default = default,
            getter = { key, defaultValue -> mmkv.decodeBool(key, defaultValue) },
            setter = { key, value -> mmkv.encode(key, value) },
        )

    protected fun strFlow(default: String = "") =
        MMKVFlowProperty(
            default = default,
            getter = { key, defaultValue -> mmkv.decodeString(key) ?: defaultValue },
            setter = { key, value -> mmkv.encode(key, value) },
        )

    protected fun intFlow(default: Int = 0) =
        MMKVFlowProperty(
            default = default,
            getter = { key, defaultValue -> mmkv.decodeInt(key, defaultValue) },
            setter = { key, value -> mmkv.encode(key, value) },
        )

    protected fun longFlow(default: Long = 0L) =
        MMKVFlowProperty(
            default = default,
            getter = { key, defaultValue -> mmkv.decodeLong(key, defaultValue) },
            setter = { key, value -> mmkv.encode(key, value) },
        )

    protected fun floatFlow(default: Float = 0f) =
        MMKVFlowProperty(
            default = default,
            getter = { key, defaultValue -> mmkv.decodeFloat(key, defaultValue) },
            setter = { key, value -> mmkv.encode(key, value) },
        )

    protected inline fun <reified T : Enum<T>> enumFlow(default: T) =
        MMKVFlowProperty(
            default = default,
            getter = { key, defaultValue ->
                mmkv.decodeString(key)?.let { stored ->
                    enumValues<T>().firstOrNull { it.name.equals(stored, ignoreCase = true) }
                } ?: defaultValue
            },
            setter = { key, value -> mmkv.encode(key, value.name) },
        )

    protected fun stringSetFlow(default: Set<String> = emptySet()) =
        MMKVFlowProperty(
            default = default,
            getter = { key, defaultValue -> mmkv.decodeStringSet(key) ?: defaultValue },
            setter = { key, value -> mmkv.encode(key, value) },
            skipEqualityCheck = true,
        )

    protected fun stringListFlow(default: List<String> = emptyList()) =
        jsonListFlow(
            default = default,
            decode = { source -> decodeFromString<List<String>>(source) },
            encode = { value -> encodeToString(value) },
        )

    protected fun intListFlow(default: List<Int> = emptyList()) =
        jsonListFlow(
            default = default,
            decode = { source -> decodeFromString<List<Int>>(source) },
            encode = { value -> encodeToString(value) },
        )

    protected fun <T> jsonListFlow(
        default: List<T> = emptyList(),
        decode: Json.(String) -> List<T>,
        encode: Json.(List<T>) -> String,
    ) =
        MMKVFlowProperty(
            default = default,
            getter = { key, defaultValue ->
                runCatching {
                        mmkv.decodeString(key)?.let { source -> json.decode(source) }
                            ?: defaultValue
                    }
                    .getOrDefault(defaultValue)
            },
            setter = { key, value -> mmkv.encode(key, json.encode(value)) },
            skipEqualityCheck = true,
        )

    protected class MMKVProperty<T>(
        private val default: T,
        private val getter: (key: String, default: T) -> T,
        private val setter: (key: String, value: T) -> Unit,
        private val skipEqualityCheck: Boolean = false,
    ) : ReadOnlyProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T =
            getter(property.name, default)

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (!skipEqualityCheck && value == getter(property.name, default)) return
            setter(property.name, value)
        }
    }

    protected class MMKVFlowProperty<T>(
        private val default: T,
        private val getter: (key: String, default: T) -> T,
        private val setter: (key: String, value: T) -> Unit,
        private val skipEqualityCheck: Boolean = false,
    ) : ReadOnlyProperty<MMKVPreference, Preference<T>> {
        private var cached: Preference<T>? = null

        @Synchronized
        override fun getValue(thisRef: MMKVPreference, property: KProperty<*>): Preference<T> =
            cached
                ?: run {
                    val key = property.name
                    val initialValue = getter(key, default)
                    val flow = MutableStateFlow(initialValue)
                    Preference(
                            state = flow.asStateFlow(),
                            update = { value ->
                                synchronized(this) {
                                    if (skipEqualityCheck || value != flow.value) {
                                        thisRef.persistPreference(key) { setter(key, value) }
                                        flow.value = value
                                    }
                                }
                            },
                            get = { flow.value },
                            refreshState = {
                                val latest = getter(key, default)
                                if (skipEqualityCheck || latest != flow.value) flow.value = latest
                            },
                        )
                        .also { cached = it }
                }
    }
}

data class Preference<T>(
    val state: StateFlow<T>,
    private val update: (T) -> Unit,
    private val get: () -> T,
    private val refreshState: () -> Unit = { update(get()) },
) {
    val value: T
        get() = get()

    fun set(value: T) = update(value)

    fun refresh() = refreshState()

    fun invalidate() = refresh()
}
