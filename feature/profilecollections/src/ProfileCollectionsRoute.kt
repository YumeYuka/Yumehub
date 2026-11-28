package moe.yumeyuka.yumehub.feature.profilecollections

import moe.yumeyuka.yumehub.ui.navigation.AppRoute

data class ProfileCollectionsRoute(
    val login: String,
    val kind: Kind,
    val organization: Boolean = false,
    val instance: String = java.util.UUID.randomUUID().toString(),
) : AppRoute {
    enum class Kind {
        Repositories,
        Starred,
        Organizations,
    }
}
