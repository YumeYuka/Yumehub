package moe.yumeyuka.yumehub.feature.explore

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val exploreFeatureModule = module {
    viewModel { FeedViewModel(get(), get(), get(), get(), get(), get()) }
}
