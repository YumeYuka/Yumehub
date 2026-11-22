package moe.yumeyuka.yumehub.feature.settings

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val settingsFeatureModule = module { viewModel { SettingsPageViewModel(get(), get()) } }
