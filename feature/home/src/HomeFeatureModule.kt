package moe.yumeyuka.yumehub.feature.home

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val homeFeatureModule = module {
    viewModel { HomeWorkViewModel(get()) }
    viewModel { (login: String) -> HomeFavoritesViewModel(get(), get(), get(), get(), login) }
}
