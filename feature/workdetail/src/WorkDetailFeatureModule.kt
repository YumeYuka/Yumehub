package moe.yumeyuka.yumehub.feature.workdetail

import moe.yumeyuka.yumehub.core.model.CollaborationKind
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val workDetailFeatureModule = module {
    viewModel {
        (owner: String, repository: String, kind: CollaborationKind, number: Int, viewer: String) ->
        CollaborationDetailViewModel(
            get(),
            owner,
            repository,
            kind,
            number,
            viewer,
            get(),
            get(),
            get(),
            get(),
            viewer,
        )
    }
    viewModel {
        (owner: String, repository: String, kind: CollaborationKind, number: Int, viewer: String) ->
        WorkInfoViewModel(get(), owner, repository, kind, number, viewer)
    }
}
