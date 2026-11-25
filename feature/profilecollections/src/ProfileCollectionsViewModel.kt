package moe.yumeyuka.yumehub.feature.profilecollections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.repository.ProfileCollectionsRepository
import moe.yumeyuka.yumehub.data.repository.RevalidationState

internal sealed interface ProfileCollectionEntry {
    data class Repository(val value: RepositoryDto) : ProfileCollectionEntry

    data class Organization(val value: UserDto) : ProfileCollectionEntry
}

internal class ProfileCollectionsViewModel(
    private val repository: ProfileCollectionsRepository,
    route: ProfileCollectionsRoute,
) : ViewModel() {
    private val account = repository.account
    private val pagingKey =
        when (route.kind) {
            ProfileCollectionsRoute.Kind.Repositories ->
                repository.repositoriesKey(route.login, route.organization)
            ProfileCollectionsRoute.Kind.Starred ->
                repository.starredKey(account, route.login)
            ProfileCollectionsRoute.Kind.Organizations ->
                repository.organizationsKey(route.login)
        }
    val revalidation: StateFlow<RevalidationState> = repository.revalidation(account, pagingKey)

    fun retryRevalidation() {
        repository.retryRevalidation(account, pagingKey)
    }

    val entries: Flow<PagingData<ProfileCollectionEntry>> =
        when (route.kind) {
            ProfileCollectionsRoute.Kind.Repositories ->
                repository.repositories(account, route.login, route.organization).map { pages ->
                    pages.map<RepositoryDto, ProfileCollectionEntry> {
                        ProfileCollectionEntry.Repository(it)
                    }
                }
            ProfileCollectionsRoute.Kind.Starred ->
                repository.starred(account, route.login).map { pages ->
                    pages.map<RepositoryDto, ProfileCollectionEntry> {
                        ProfileCollectionEntry.Repository(it)
                    }
                }
            ProfileCollectionsRoute.Kind.Organizations ->
                repository.organizations(account, route.login).map { pages ->
                    pages.map<UserDto, ProfileCollectionEntry> {
                        ProfileCollectionEntry.Organization(it)
                    }
                }
        }.cachedIn(viewModelScope)
}
