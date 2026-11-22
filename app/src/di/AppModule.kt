package moe.yumeyuka.yumehub.di

import moe.yumeyuka.yumehub.data.di.dataModule
import moe.yumeyuka.yumehub.data.download.DownloadStarter
import moe.yumeyuka.yumehub.data.twofactor.TwoFactorNotifier
import moe.yumeyuka.yumehub.feature.actions.actionsFeatureModule
import moe.yumeyuka.yumehub.feature.auth.authFeatureModule
import moe.yumeyuka.yumehub.feature.commitdetail.commitDetailFeatureModule
import moe.yumeyuka.yumehub.feature.discovery.discoveryFeatureModule
import moe.yumeyuka.yumehub.feature.discussiondetail.discussionDetailFeatureModule
import moe.yumeyuka.yumehub.feature.editor.editorFeatureModule
import moe.yumeyuka.yumehub.feature.explore.exploreFeatureModule
import moe.yumeyuka.yumehub.feature.home.homeFeatureModule
import moe.yumeyuka.yumehub.feature.issuesearch.issueSearchFeatureModule
import moe.yumeyuka.yumehub.feature.markdown.markdownFeatureModule
import moe.yumeyuka.yumehub.feature.notifications.notificationsFeatureModule
import moe.yumeyuka.yumehub.feature.profile.profileFeatureModule
import moe.yumeyuka.yumehub.feature.profilecollections.profileCollectionsFeatureModule
import moe.yumeyuka.yumehub.feature.pullrequestcommits.pullRequestCommitsFeatureModule
import moe.yumeyuka.yumehub.feature.pullrequestdiff.pullRequestDiffFeatureModule
import moe.yumeyuka.yumehub.feature.releases.releasesFeatureModule
import moe.yumeyuka.yumehub.feature.repositories.repositoriesFeatureModule
import moe.yumeyuka.yumehub.feature.repository.repositoryFeatureModule
import moe.yumeyuka.yumehub.feature.repositoryactivity.repositoryActivityFeatureModule
import moe.yumeyuka.yumehub.feature.search.searchFeatureModule
import moe.yumeyuka.yumehub.feature.settings.settingsFeatureModule
import moe.yumeyuka.yumehub.feature.starred.starredFeatureModule
import moe.yumeyuka.yumehub.feature.workdetail.workDetailFeatureModule
import moe.yumeyuka.yumehub.feature.worklist.workListFeatureModule
import moe.yumeyuka.yumehub.feature.workspaces.workspacesFeatureModule
import moe.yumeyuka.yumehub.notifier.AndroidTwoFactorNotifier
import moe.yumeyuka.yumehub.notifier.DownloadService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    includes(
        dataModule,
        authFeatureModule,
        homeFeatureModule,
        markdownFeatureModule,
        notificationsFeatureModule,
        exploreFeatureModule,
        discoveryFeatureModule,
        searchFeatureModule,
        profileFeatureModule,
        profileCollectionsFeatureModule,
        settingsFeatureModule,
        repositoriesFeatureModule,
        repositoryFeatureModule,
        workspacesFeatureModule,
        starredFeatureModule,
        issueSearchFeatureModule,
        workListFeatureModule,
        workDetailFeatureModule,
        pullRequestDiffFeatureModule,
        pullRequestCommitsFeatureModule,
        discussionDetailFeatureModule,
        releasesFeatureModule,
        commitDetailFeatureModule,
        repositoryActivityFeatureModule,
        actionsFeatureModule,
        editorFeatureModule,
    )

    // 2FA 通知需要 Android 通知渠道与文案，只能由 :app 绑定。
    single<TwoFactorNotifier> { AndroidTwoFactorNotifier(androidContext()) }
    single<DownloadStarter> {
        val app = androidContext()
        DownloadStarter { id -> DownloadService.start(app, id) }
    }
}
