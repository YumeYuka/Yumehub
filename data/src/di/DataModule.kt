package moe.yumeyuka.yumehub.data.di

import com.tencent.mmkv.MMKV
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.auth.AuthRepository
import moe.yumeyuka.yumehub.data.auth.GitHubAuthRepository
import moe.yumeyuka.yumehub.data.auth.SessionFailures
import moe.yumeyuka.yumehub.data.auth.defaultAuthBrowserConfiguration
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.download.DownloadStore
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.data.network.NetworkModule
import moe.yumeyuka.yumehub.data.repository.CommitRepository
import moe.yumeyuka.yumehub.data.repository.DashboardFeedRepository
import moe.yumeyuka.yumehub.data.repository.DiscoveryRepository
import moe.yumeyuka.yumehub.data.repository.DiscussionRepository
import moe.yumeyuka.yumehub.data.repository.GithubDiffRepository
import moe.yumeyuka.yumehub.data.repository.GraphQlSearchRepository
import moe.yumeyuka.yumehub.data.repository.HomeFavoritesRepository
import moe.yumeyuka.yumehub.data.repository.MyRepositoriesRepository
import moe.yumeyuka.yumehub.data.repository.NotificationsRepository
import moe.yumeyuka.yumehub.data.repository.PagingRepository
import moe.yumeyuka.yumehub.data.repository.ProfileCollectionsRepository
import moe.yumeyuka.yumehub.data.repository.ProfileRepository
import moe.yumeyuka.yumehub.data.repository.ReleasePreviewImages
import moe.yumeyuka.yumehub.data.repository.ReleaseRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryContentRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryForkRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryOverviewRepository
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository
import moe.yumeyuka.yumehub.data.repository.WorkItemSubscriptionRepository
import moe.yumeyuka.yumehub.data.repository.WorkListRepository
import moe.yumeyuka.yumehub.data.repository.WorkflowRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore
import moe.yumeyuka.yumehub.data.storage.mmkv.HomeFavoritesStore
import moe.yumeyuka.yumehub.data.storage.mmkv.MMKVProvider
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotStore
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.data.twofactor.DeviceKeyManager
import moe.yumeyuka.yumehub.data.twofactor.GitHubTwoFactorRepository
import moe.yumeyuka.yumehub.data.twofactor.TwoFactorRepository
import net.openid.appauth.AuthorizationService
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** One named MMKV storage: the Koin qualifier and the on-disk id come from the same argument. */
private fun Module.mmkv(id: String) {
    single<MMKV>(named(id)) { get<MMKVProvider>().getMMKV(id) }
}

/** 共享数据层：网络、持久化、认证与 2FA 仓库。ViewModel 由各 feature 模块自行注册。 */
val dataModule = module {
    single { MMKVProvider() }
    single { CommentDraftStore(get()) }
    mmkv(AUTH_MMKV_ID)
    mmkv(SETTINGS_MMKV_ID)
    mmkv("downloads")
    single { DownloadStore(androidContext(), get(named("downloads")), get(), get(), get(), get()) }

    single { TokenStore(get(named(AUTH_MMKV_ID))) }
    single { SettingsStore(get(named(SETTINGS_MMKV_ID))) }
    single {
        val context = androidContext()
        AuthorizationService(context, defaultAuthBrowserConfiguration(context))
    }
    single {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
    single { SessionFailures() }
    single { NetworkModule.provideOkHttpClient(get(), get()) }
    single { ReleasePreviewImages() }
    single { HomeFavoritesStore(get(), get()) }
    single { PagingSnapshotStore(get(), get()) }
    single { CachedDataRepository(get(), get(), get()) }
    single { PagingRepository(get(), get()) }
    single { MyRepositoriesRepository(get(), get(), get()) }
    single { DashboardFeedRepository(get(), get(), get()) }
    single { DiscoveryRepository(get(), get(), get(), get()) }
    single { GraphQlSearchRepository(get(), get()) }
    single { WorkListRepository(get(), get(), get(), get()) }
    single { CommitRepository(get(), get(), get(), get(), get()) }
    single { DiscussionRepository(get(), get(), get()) }
    single { ReleaseRepository(get(), get(), get(), get()) }
    single { RepositoryActivityRepository(get(), get(), get()) }
    single { WorkflowRepository(get(), get(), get(), get()) }
    single { NetworkModule.provideRetrofit(get(), get(), get()) }
    single { NetworkModule.provideGitHubApi(get()) }
    single { NetworkModule.provideGitHubGraphQlApi(get()) }
    single { GithubMarkdownRepository(get(), get(), get()) }
    single { RepositoryContentRepository(get(), get(), get(), get()) }
    single { WorkspacesRepository(get(), get()) }
    single { RepositoryOverviewRepository(get(), get(), get(), get(), get(), get()) }
    single { RepositoryForkRepository(get(), get(), get(), get(), get()) }
    single { GithubDiffRepository(get(), get()) }
    single { WorkDetailRepository(get(), get(), get(), get(), get()) }
    single { WorkItemSubscriptionRepository(get(), get(), get()) }
    single { ProfileRepository(get(), get(), get(), get()) }
    single { ProfileCollectionsRepository(get(), get(), get()) }
    single { NotificationsRepository(get(), get(), get()) }
    single { HomeFavoritesRepository(get(), get(), get()) }
    single { DeviceKeyManager(androidContext(), get()) }
    single<AuthRepository> { GitHubAuthRepository(get(), get(), get(), get(), get(), get()) }
    single<TwoFactorRepository> { GitHubTwoFactorRepository(get(), get(), get(), get()) }
}

private const val AUTH_MMKV_ID = "auth"
private const val SETTINGS_MMKV_ID = "settings"
