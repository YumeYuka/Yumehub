package moe.yumeyuka.yumehub.feature.profilecollections

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val profileCollectionsFeatureModule = module {
    viewModel { (route: ProfileCollectionsRoute) -> ProfileCollectionsViewModel(get(), route) }
}
