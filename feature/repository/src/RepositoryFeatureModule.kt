package moe.yumeyuka.yumehub.feature.repository

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val repositoryFeatureModule = module {
    viewModel { RepositoryForkViewModel(get(), get()) }
    viewModel { RepositoryListsViewModel(get()) }
    viewModel { (owner: String, repository: String, initialPage: RepositoryDetailPage) ->
        RepositoryDetailViewModel(get(), get(), get(), owner, repository, initialPage)
    }
    viewModel { (owner: String, repository: String) ->
        RepositoryOverviewViewModel(get(), owner, repository, get())
    }
}
