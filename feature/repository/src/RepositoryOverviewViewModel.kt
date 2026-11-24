package moe.yumeyuka.yumehub.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.repository.RepositoryFavoritesLimitException
import moe.yumeyuka.yumehub.data.repository.RepositoryOverview
import moe.yumeyuka.yumehub.data.repository.RepositoryOverviewRepository
import moe.yumeyuka.yumehub.locale.GithubTxt

internal const val RepositoryDescriptionMaxLength = 350

internal enum class RepositorySheet {
    Description,
    Notifications,
}

internal data class RepositoryManagementState(
    val sheet: RepositorySheet,
    val description: String = "",
    val originalDescription: String = description,
    val subscription: RepositorySubscriptionSelection? = null,
    val originalSubscription: RepositorySubscriptionSelection? = subscription,
    val pinned: Boolean? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal data class RepositorySubscriptionSelection(val value: String, val types: Set<String>)

internal data class RepositoryOverviewState(
    val overview: RepositoryOverview? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
    /** 首页收藏状态；进入页面时按需查询，未查询完成前为 null。 */
    val pinned: Boolean? = null,
    val management: RepositoryManagementState? = null,
    val pendingSubscription: RepositorySubscriptionSelection? = null,
)

/** 订阅等轻量操作失败后经一次性事件提示，不阻塞浮层交互。 */
internal sealed interface RepositoryOverviewEvent {
    data class Failed(val message: String) : RepositoryOverviewEvent
}

internal class RepositoryOverviewViewModel(
    private val source: RepositoryOverviewRepository,
    private val owner: String,
    private val name: String,
) : ViewModel() {
    val account = source.account
    private val resource = source.resource(account, owner, name)
    private val mutableState = MutableStateFlow(RepositoryOverviewState(overview = resource.cached))
    val uiState = mutableState.asStateFlow()
    private val mutableEvents = Channel<RepositoryOverviewEvent>(Channel.BUFFERED)
    val events = mutableEvents.receiveAsFlow()
    private var sheetJob: Job? = null
    private val subscriptionMutex = Mutex()

    init {
        viewModelScope.launch {
            resource.state.collect {
                mutableState.value =
                    mutableState.value.copy(
                        overview = it.data,
                        loading = it.loading,
                        error = it.error?.let { GithubTxt.current.Repository.OperationFailed },
                    )
            }
        }
        refresh()
    }

    private fun active() =
        source.account.equals(account, true) && source.resource(account, owner, name) === resource

    fun refresh(force: Boolean = false) {
        if (active()) resource.refresh(force) { source.load(account, owner, name) }
    }

    /** 读取首页 pin 状态；已获取或进行中时不重复请求。 */
    fun loadPinned() {
        if (!active() || mutableState.value.busy || mutableState.value.pinned != null) return
        val overview = mutableState.value.overview ?: return
        sheetJob?.cancel()
        sheetJob = viewModelScope.launch {
            try {
                val pinned = source.isPinned(account, overview.id)
                if (active()) mutableState.value = mutableState.value.copy(pinned = pinned)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 查询失败时保持未知，菜单不显示固定项，不阻塞其他入口。
            }
        }
    }

    fun open(sheet: RepositorySheet, repository: RepositoryDto) {
        if (!active() || (mutableState.value.busy && sheet != RepositorySheet.Notifications)) return
        val overview = mutableState.value.overview ?: return
        if (sheet == RepositorySheet.Description && !overview.canEditDescription) return
        sheetJob?.cancel()
        mutableState.value =
            mutableState.value.copy(
                management =
                    RepositoryManagementState(
                        sheet = sheet,
                        description = repository.description.orEmpty(),
                        subscription =
                            if (sheet == RepositorySheet.Notifications)
                                mutableState.value.pendingSubscription
                                    ?: overview.subscription?.let { value ->
                                        RepositorySubscriptionSelection(
                                            value,
                                            overview.subscriptionTypes.toSet(),
                                        )
                                    }
                            else null,
                    ),
            )
    }

    private fun updateSheet(
        sheet: RepositorySheet,
        update: (RepositoryManagementState) -> RepositoryManagementState,
    ) {
        if (!active()) return
        val editor = mutableState.value.management?.takeIf { it.sheet == sheet } ?: return
        mutableState.value = mutableState.value.copy(management = update(editor))
    }

    fun dismiss() {
        if (
            mutableState.value.busy &&
                mutableState.value.management?.sheet != RepositorySheet.Notifications
        )
            return
        sheetJob?.cancel()
        mutableState.value = mutableState.value.copy(management = null)
    }

    fun description(value: String) {
        if (value.length <= RepositoryDescriptionMaxLength && !mutableState.value.busy)
            updateSheet(RepositorySheet.Description) { it.copy(description = value, error = null) }
    }

    fun changeSubscription(value: String, types: Set<String>) {
        updateSheet(RepositorySheet.Notifications) {
            it.copy(subscription = RepositorySubscriptionSelection(value, types.toSet()))
        }
    }

    fun star() {
        val state = mutableState.value.overview ?: return
        perform { source.setStarred(account, owner, name, !state.starred) }
    }

    fun pin(repository: RepositoryDto, selected: Boolean) {
        val state = mutableState.value.overview ?: return
        perform {
            source.setPinned(account, repository, state.id, selected)
            if (active()) mutableState.value = mutableState.value.copy(pinned = selected)
        }
    }

    private fun saveSubscription(selection: RepositorySubscriptionSelection) {
        if (!active() || mutableState.value.management?.sheet != RepositorySheet.Notifications)
            return
        sheetJob?.cancel()
        mutableState.value =
            mutableState.value.copy(management = null, pendingSubscription = selection)
        viewModelScope.launch {
            subscriptionMutex.withLock {
                if (!active()) return@withLock
                try {
                    source.setSubscription(account, owner, name, selection.value, selection.types)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (active())
                        mutableEvents.send(
                            RepositoryOverviewEvent.Failed(
                                GithubTxt.current.Repository.OperationFailed,
                            ),
                        )
                } finally {
                    if (active() && mutableState.value.pendingSubscription === selection)
                        mutableState.value =
                            mutableState.value.copy(
                                overview = resource.cached ?: mutableState.value.overview,
                                pendingSubscription = null,
                            )
                }
            }
        }
    }

    fun save() {
        if (!active()) return
        val editor = mutableState.value.management ?: return
        when (editor.sheet) {
            RepositorySheet.Description ->
                perform(close = true) {
                    source.saveDescription(account, owner, name, editor.description.trim())
                }
            RepositorySheet.Notifications -> {
                val selection = editor.subscription
                if (selection == null || selection == editor.originalSubscription) dismiss()
                else saveSubscription(selection)
            }
        }
    }

    private fun perform(close: Boolean = false, operation: suspend () -> Unit) {
        if (!active() || mutableState.value.busy) return
        mutableState.value =
            mutableState.value.copy(
                busy = true,
                error = null,
                management = if (close) null else mutableState.value.management?.copy(error = null),
            )
        viewModelScope.launch {
            try {
                operation()
                if (active()) mutableState.value = mutableState.value.copy(busy = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!active()) return@launch
                val message =
                    if (error is RepositoryFavoritesLimitException)
                        GithubTxt.current.Repository.FavoriteLimitReached
                    else GithubTxt.current.Repository.OperationFailed
                mutableState.value = mutableState.value.copy(busy = false)
                mutableEvents.send(RepositoryOverviewEvent.Failed(message))
            }
        }
    }
}
