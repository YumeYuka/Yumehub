package moe.yumeyuka.yumehub.feature.workspaces

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val workspacesFeatureModule = module {
    viewModel { (route: CommitHistoryRoute) -> CommitHistoryViewModel(get(), get(), route) }
    viewModel { (route: WorkspacesRoute) -> WorkspacesViewModel(get(), get(), get(), route, get()) }
}
