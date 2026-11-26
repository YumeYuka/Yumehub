package moe.yumeyuka.yumehub.feature.repository

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val repositoryFeatureModule = module {
    viewModel { RepositoryForkViewModel(get(), get()) }
    viewModel { RepositoryListsViewModel(get()) }
    viewModel { (owner: String, repository: String) ->
        RepositoryDetailViewModel(get(), get(), get(), owner, repository)
    }
    viewModel { (owner: String, repository: String) ->
        RepositoryOverviewViewModel(get(), owner, repository, get())
    }
}
