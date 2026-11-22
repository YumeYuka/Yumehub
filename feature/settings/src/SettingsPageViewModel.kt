package moe.yumeyuka.yumehub.feature.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import moe.yumeyuka.yumehub.data.download.DownloadStore
import moe.yumeyuka.yumehub.data.storage.mmkv.MaxPredictiveBackProgress
import moe.yumeyuka.yumehub.data.storage.mmkv.MinPredictiveBackProgress
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.data.storage.mmkv.ThemeMode

internal class SettingsPageViewModel(
    private val settingsStore: SettingsStore,
    private val downloadStore: DownloadStore,
) : ViewModel() {
    val downloadDirectory = downloadStore.directory

    fun setDownloadDirectory(value: String?) = downloadStore.setDirectory(value)

    val themeMode: StateFlow<ThemeMode> = settingsStore.themeMode.state
    val accentColor: StateFlow<Int> = settingsStore.accentColor.state
    val liquidGlassEnabled: StateFlow<Boolean> = settingsStore.liquidGlassEnabled.state
    val reduceTransparency: StateFlow<Boolean> = settingsStore.reduceTransparency.state
    val topBarBlurEnabled: StateFlow<Boolean> = settingsStore.topBarBlurEnabled.state
    val predictiveBackEnabled: StateFlow<Boolean> = settingsStore.predictiveBackEnabled.state
    val predictiveBackMaxProgress: StateFlow<Float> = settingsStore.predictiveBackMaxProgress.state
    val keepBottomBarExpanded: StateFlow<Boolean> = settingsStore.keepBottomBarExpanded.state
    val glassHdrEnabled: StateFlow<Boolean> = settingsStore.glassHdrEnabled.state
    val fpsCounter: StateFlow<Boolean> = settingsStore.fpsCounter.state

    fun setThemeMode(value: ThemeMode) {
        settingsStore.themeMode.set(value)
    }

    fun setAccentColor(value: Int) {
        settingsStore.accentColor.set(value)
    }

    fun setLiquidGlassEnabled(value: Boolean) {
        settingsStore.liquidGlassEnabled.set(value)
    }

    fun setReduceTransparency(value: Boolean) {
        settingsStore.reduceTransparency.set(value)
    }

    fun setTopBarBlurEnabled(value: Boolean) {
        settingsStore.topBarBlurEnabled.set(value)
    }

    fun setPredictiveBackEnabled(value: Boolean) {
        settingsStore.predictiveBackEnabled.set(value)
    }

    fun setPredictiveBackMaxProgress(value: Float) {
        settingsStore.predictiveBackMaxProgress.set(
            value.coerceIn(MinPredictiveBackProgress, MaxPredictiveBackProgress),
        )
    }

    fun setKeepBottomBarExpanded(value: Boolean) {
        settingsStore.keepBottomBarExpanded.set(value)
    }

    fun setGlassHdrEnabled(value: Boolean) {
        settingsStore.glassHdrEnabled.set(value)
    }

    fun setFpsCounter(value: Boolean) {
        settingsStore.fpsCounter.set(value)
    }
}
