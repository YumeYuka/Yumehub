package moe.yumeyuka.yumehub.feature.search

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val searchFeatureModule = module {
    single { ExplorePagingRepository(get(), get(), get()) }
    viewModel { ExploreViewModel(get(), get()) }
}
