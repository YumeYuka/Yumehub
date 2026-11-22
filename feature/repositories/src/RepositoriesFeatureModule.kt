package moe.yumeyuka.yumehub.feature.repositories

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val repositoriesFeatureModule = module {
    viewModel { (login: String) -> RepositoryViewModel(get(), login, get()) }
}
