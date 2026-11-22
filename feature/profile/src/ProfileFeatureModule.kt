package moe.yumeyuka.yumehub.feature.profile

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val profileFeatureModule = module {
    viewModel { ProfileViewModel(get()) }
    viewModel { (login: String) -> FollowViewModel(get(), login, get()) }
    viewModel { parameters ->
        UserProfileViewModel(
            get(),
            parameters.get<String>(0),
            parameters.values.getOrNull(1) as? String,
            get(),
        )
    }
    viewModel { SettingsViewModel(get()) }
}
