package moe.yumeyuka.yumehub.feature.issuesearch

import moe.yumeyuka.yumehub.core.model.CollaborationKind
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val issueSearchFeatureModule = module {
    viewModel { (login: String, kind: CollaborationKind) ->
        IssueSearchViewModel(get(), login, kind)
    }
}
