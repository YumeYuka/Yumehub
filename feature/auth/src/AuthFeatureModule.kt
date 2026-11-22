package moe.yumeyuka.yumehub.feature.auth

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val authFeatureModule = module {
    viewModel { AuthViewModel(get(), get()) }
    viewModel { LoginViewModel(get()) }
    viewModel { TwoFactorViewModel(get(), get(), get(), get()) }
}
