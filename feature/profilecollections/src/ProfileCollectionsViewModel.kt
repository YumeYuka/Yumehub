package moe.yumeyuka.yumehub.feature.profilecollections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.repository.ProfileCollectionsRepository

internal sealed interface ProfileCollectionEntry {
    data class Repository(val value: RepositoryDto) : ProfileCollectionEntry

    data class Organization(val value: UserDto) : ProfileCollectionEntry
}

internal class ProfileCollectionsViewModel(
    repository: ProfileCollectionsRepository,
    route: ProfileCollectionsRoute,
) : ViewModel() {
    private val account = repository.account
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
