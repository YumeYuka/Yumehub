package moe.yumeyuka.yumehub.feature.repositories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.MyRepositoriesPageSize
import moe.yumeyuka.yumehub.data.repository.MyRepositoriesRepository
import moe.yumeyuka.yumehub.data.repository.PagingRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryPagingSource
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.defaultPagingConfig

internal data class RepositoryFilters(
    val type: String = "all",
    val sort: String = "updated",
    val language: String? = null,
    val query: String = "",
)

internal data class RepositoryListing(
    val filters: RepositoryFilters,
    val pages: Flow<PagingData<RepositoryDto>>,
    val revalidation: StateFlow<RevalidationState>,
)

private fun repositoryPagingKey(filter: RepositoryFilters): String =
    "my-repositories:v4:type=${filter.type}:sort=${filter.sort}:" +
        "size=$MyRepositoriesPageSize:language=${filter.language}:query=${filter.query}"

// Pager 的闲置缓存比路由活得久；工厂只捕获数据依赖和查询参数。
private fun repositoryPages(
    repositories: MyRepositoriesRepository,
    login: String,
    pagingRepository: PagingRepository,
    filter: RepositoryFilters,
): Flow<PagingData<RepositoryDto>> {
  val key = repositoryPagingKey(filter)
  return pagingRepository.pages(
      login,
      key,
      defaultPagingConfig(MyRepositoriesPageSize),
      tags = setOf(CacheTag(DataDomain.Repositories, "my-repositories")),
  ) { context ->
    RepositoryPagingSource(
        repositories,
        login,
        MyRepositoriesPageSize,
        pagingRepository.snapshot(
            login,
            key,
            RepositoryDto.serializer(),
            context,
            tags = setOf(CacheTag(DataDomain.Repositories, "my-repositories")),
        ),
        type = filter.type,
        sort = filter.sort,
        query = filter.query,
        language = filter.language,
    )
  }
}

internal sealed interface RepositoryLanguages {
  data object Loading : RepositoryLanguages

  data object Failed : RepositoryLanguages

  data class Loaded(val values: List<String>) : RepositoryLanguages
}

internal class RepositoryViewModel(
    private val repositories: MyRepositoriesRepository,
    private val login: String,
    private val pagingRepository: PagingRepository,
) : ViewModel() {
  private var listingScope = newListingScope()
  private val mutableListing = MutableStateFlow(createListing(RepositoryFilters()))
  val listing = mutableListing.asStateFlow()
  private val mutableLanguages = MutableStateFlow<RepositoryLanguages?>(null)
  val languages = mutableLanguages.asStateFlow()

  init {
    // 进入页面即与首屏共用请求预取语言；菜单只读取状态。
    loadLanguages()
  }

  private fun newListingScope() =
      CoroutineScope(
          viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]),
      )

  private fun createListing(filter: RepositoryFilters) =
      RepositoryListing(
          filter,
          repositoryPages(repositories, login, pagingRepository, filter).cachedIn(listingScope),
          pagingRepository.revalidation(login, repositoryPagingKey(filter)),
      )

  fun retryRevalidation() {
    pagingRepository.retryRevalidation(login, repositoryPagingKey(listing.value.filters))
  }

  private fun changeFilters(next: RepositoryFilters) {
    if (next == mutableListing.value.filters) return
    // 页面只收到完整的新数据源；旧分页收集随本代 scope 一起取消。
    listingScope.cancel()
    listingScope = newListingScope()
    mutableListing.value = createListing(next)
  }

  fun setType(value: String) = changeFilters(listing.value.filters.copy(type = value))

  fun setSort(value: String) = changeFilters(listing.value.filters.copy(sort = value))

  fun setFilters(type: String, sort: String, language: String?) =
      changeFilters(listing.value.filters.copy(type = type, sort = sort, language = language))

  fun setQuery(value: String) = changeFilters(listing.value.filters.copy(query = value.trim()))

  fun refreshLanguages() {
    loadLanguages(force = true)
  }

  fun loadLanguages(force: Boolean = false) {
    if (
        mutableLanguages.value is RepositoryLanguages.Loading ||
            !force && mutableLanguages.value is RepositoryLanguages.Loaded
    )
        return
    mutableLanguages.value = RepositoryLanguages.Loading
    viewModelScope.launch {
      try {
        val languages =
            withContext(Dispatchers.Default) {
              repositories
                  .all(login, force = force)
                  .mapNotNull { it.language?.takeIf(String::isNotBlank) }
                  .toSortedSet(String.CASE_INSENSITIVE_ORDER)
                  .toList()
            }
        mutableLanguages.value = RepositoryLanguages.Loaded(languages)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableLanguages.value = RepositoryLanguages.Failed
      }
    }
  }
}
