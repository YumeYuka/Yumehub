package moe.yumeyuka.yumehub.feature.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.paging.filter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.repository.DiscoveryLanguages
import moe.yumeyuka.yumehub.data.repository.DiscoveryRepository
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.TrendingFilter
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val discoveryFeatureModule = module {
    viewModel { (account: String, route: DiscoveryRoute) -> DiscoveryViewModel(get(), account, route) }
}

internal data class DiscoveryFilters(
    val trending: TrendingFilter = TrendingFilter(),
    val recentlyUpdated: Boolean = false,
)

internal sealed interface LanguageState {
    data object Loading : LanguageState
    data object Failed : LanguageState
    data class Ready(val options: DiscoveryLanguages) : LanguageState
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscoveryViewModel(
    private val repository: DiscoveryRepository,
    private val account: String,
    private val route: DiscoveryRoute,
) : ViewModel() {
    private val mutableFilters = MutableStateFlow(DiscoveryFilters())
    val filters = mutableFilters.asStateFlow()
    private val mutableQuery = MutableStateFlow("")
    val query = mutableQuery.asStateFlow()
    private val mutableLanguages = MutableStateFlow<LanguageState?>(null)
    val languages = mutableLanguages.asStateFlow()

    private val pages =
        if (route == DiscoveryRoute.Trending) {
            val ranking = filters.flatMapLatest { repository.trending(account, it.trending) }
                .cachedIn(viewModelScope)
            ranking.combine(query) { page, text ->
                if (text.isEmpty()) page else page.filter {
                    it.fullName.orEmpty().contains(text, ignoreCase = true) ||
                        it.description.orEmpty().contains(text, ignoreCase = true)
                }
            }
        } else {
            combine(filters, query) { filter, text -> filter to text }.flatMapLatest { (filter, text) ->
                repository.featured(account, text, filter.trending.language, filter.recentlyUpdated)
            }
        }
    val repositories = pages.cachedIn(viewModelScope)

    val revalidation: StateFlow<RevalidationState> =
        if (route == DiscoveryRoute.Trending) {
            filters.flatMapLatest {
                repository.revalidation(account, repository.trendingKey(it.trending))
            }
        } else {
            combine(filters, query) { filter, text -> filter to text }.flatMapLatest { (filter, text) ->
                repository.revalidation(
                    account,
                    repository.featuredKey(text, filter.trending.language, filter.recentlyUpdated),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RevalidationState.Idle)

    fun retryRevalidation() {
        val key =
            if (route == DiscoveryRoute.Trending) {
                repository.trendingKey(filters.value.trending)
            } else {
                val filter = filters.value
                repository.featuredKey(query.value, filter.trending.language, filter.recentlyUpdated)
            }
        repository.retryRevalidation(account, key)
    }

    fun search(value: String) { mutableQuery.value = value.trim() }

    fun apply(value: DiscoveryFilters) { mutableFilters.value = value }

    fun loadLanguages() {
        if (languages.value is LanguageState.Ready || languages.value == LanguageState.Loading) return
        mutableLanguages.value = LanguageState.Loading
        viewModelScope.launch {
            mutableLanguages.value = try {
                LanguageState.Ready(repository.languages(account, route == DiscoveryRoute.Trending))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                LanguageState.Failed
            }
        }
    }
}
