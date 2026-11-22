package moe.yumeyuka.yumehub.feature.notifications

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val notificationsFeatureModule = module { viewModel { NotificationsViewModel(get()) } }
