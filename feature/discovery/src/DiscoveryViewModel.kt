package moe.yumeyuka.yumehub.feature.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.filter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.repository.DiscoveryLanguages
import moe.yumeyuka.yumehub.data.repository.DiscoveryRepository
import moe.yumeyuka.yumehub.data.repository.TrendingFilter
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val discoveryFeatureModule = module {
    viewModel { (account: String, route: DiscoveryRoute) -> DiscoveryViewModel(get(), account, route) }
}

internal data class DiscoveryFilters(
    val trending: TrendingFilter = TrendingFilter(),
    val recentlyUpdated: Boolean = false,
) {
    companion object {
        val Default = DiscoveryFilters()
    }
}

internal sealed interface LanguageState {
    data object Loading : LanguageState
    data object Failed : LanguageState
    data class Ready(val options: DiscoveryLanguages) : LanguageState
}

internal class DiscoveryViewModel(
    private val repository: DiscoveryRepository,
    private val account: String,
    private val route: DiscoveryRoute,
) : ViewModel() {
    private val mutableFilters = MutableStateFlow(DiscoveryFilters.Default)
    val filters = mutableFilters.asStateFlow()
    private val mutableQuery = MutableStateFlow("")
    val query = mutableQuery.asStateFlow()
    private val mutableLanguages = MutableStateFlow<LanguageState?>(null)
    val languages = mutableLanguages.asStateFlow()

    fun repositoriesFor(filter: DiscoveryFilters, query: String) =
        if (route == DiscoveryRoute.Trending) {
            repository.trending(account, filter.trending).map { page ->
                if (query.isEmpty()) page else page.filter {
                    it.fullName.orEmpty().contains(query, ignoreCase = true) ||
                        it.description.orEmpty().contains(query, ignoreCase = true)
                }
            }
        } else repository.featured(account, query, filter.trending.language, filter.recentlyUpdated)

    fun sourceKey(filter: DiscoveryFilters, query: String) =
        if (route == DiscoveryRoute.Trending) repository.trendingKey(filter.trending)
        else repository.featuredKey(query, filter.trending.language, filter.recentlyUpdated)

    fun revalidation(key: String) = repository.revalidation(account, key)

    fun retryRevalidation(key: String) = repository.retryRevalidation(account, key)

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
