package moe.yumeyuka.yumehub.feature.profilecollections

import moe.yumeyuka.yumehub.ui.navigation.GithubRoute

data class ProfileCollectionsRoute(
    val login: String,
    val kind: Kind,
    val organization: Boolean = false,
    val instance: String = java.util.UUID.randomUUID().toString(),
) : GithubRoute {
    enum class Kind {
        Repositories,
        Starred,
        Organizations,
    }
}
