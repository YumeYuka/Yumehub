package moe.yumeyuka.yumehub.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore

internal enum class HomeWorkItem {
    Issues,
    PullRequests,
    Discussions,
    Projects,
    Repositories,
    Organizations,
    Starred;

    companion object {
        fun decode(names: List<String>): List<HomeWorkItem> =
            names.mapNotNull { name -> entries.firstOrNull { it.name == name } }.distinct()
    }
}

/** 两组始终互斥且覆盖全部入口；旧版设置顺序作为迁移来源。 */
internal data class HomeWorkLayout(
    val shown: List<HomeWorkItem>,
    val hidden: List<HomeWorkItem>,
) {
    fun toggle(item: HomeWorkItem): HomeWorkLayout =
        if (item in shown) copy(shown = shown - item, hidden = hidden + item)
        else if (item in hidden) copy(shown = shown + item, hidden = hidden - item) else this

    fun reorder(items: List<HomeWorkItem>, visible: Boolean): HomeWorkLayout {
        val current = if (visible) shown else hidden
        if (items.distinct().size != items.size || items.toSet() != current.toSet()) return this
        return if (visible) copy(shown = items.toList()) else copy(hidden = items.toList())
    }

    fun encode(): List<String> = shown.map { it.name } + "|" + hidden.map { it.name }

    companion object {
        fun decode(stored: List<String>, legacy: List<String>): HomeWorkLayout {
            val divider = stored.indexOf("|")
            val shown = HomeWorkItem.decode(if (divider < 0) legacy else stored.take(divider))
            val orderedHidden =
                if (divider < 0) emptyList() else HomeWorkItem.decode(stored.drop(divider + 1))
            val hidden = (orderedHidden + HomeWorkItem.entries).distinct().filterNot { it in shown }
            return HomeWorkLayout(shown, hidden)
        }
    }
}

/** 确认后一次保存两组顺序；编辑草稿由面板持有，取消不写设置。 */
internal class HomeWorkViewModel(private val settings: SettingsStore) : ViewModel() {
    val layout =
        combine(settings.homeWorkLayout.state, settings.homeWorkItems.state) { stored, legacy ->
                HomeWorkLayout.decode(stored, legacy)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                HomeWorkLayout.decode(
                    settings.homeWorkLayout.state.value,
                    settings.homeWorkItems.state.value,
                ),
            )

    fun save(layout: HomeWorkLayout) {
        settings.homeWorkLayout.set(HomeWorkLayout.decode(layout.encode(), emptyList()).encode())
    }
}
