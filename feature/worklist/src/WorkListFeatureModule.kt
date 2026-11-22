package moe.yumeyuka.yumehub.feature.worklist

import moe.yumeyuka.yumehub.core.model.CollaborationKind
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val workListFeatureModule = module {
    viewModel { (owner: String, repository: String, kind: CollaborationKind, login: String) ->
        CollaborationListViewModel(get(), get(), owner, repository, kind, login)
    }
}
