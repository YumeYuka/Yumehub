package moe.yumeyuka.yumehub.feature.starred

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val starredFeatureModule = module {
    viewModel { (login: String) -> StarredRepositoriesViewModel(get(), login, get()) }
}
