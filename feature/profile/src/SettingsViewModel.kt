package moe.yumeyuka.yumehub.feature.profile

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.data.storage.mmkv.ThemeMode

class SettingsViewModel(private val settingsStore: SettingsStore) : ViewModel() {
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

    fun setThemeMode(mode: ThemeMode) {
        settingsStore.themeMode.set(mode)
    }

    fun setAccentColor(argb: Int) {
        settingsStore.accentColor.set(argb)
    }

    fun setLiquidGlassEnabled(enabled: Boolean) {
        settingsStore.liquidGlassEnabled.set(enabled)
    }

    fun setReduceTransparency(enabled: Boolean) {
        settingsStore.reduceTransparency.set(enabled)
    }

    fun setPredictiveBackEnabled(enabled: Boolean) {
        settingsStore.predictiveBackEnabled.set(enabled)
    }

    fun setPredictiveBackMaxProgress(progress: Float) {
        settingsStore.predictiveBackMaxProgress.set(progress)
    }

    fun setKeepBottomBarExpanded(enabled: Boolean) {
        settingsStore.keepBottomBarExpanded.set(enabled)
    }

    fun setGlassHdrEnabled(enabled: Boolean) {
        settingsStore.glassHdrEnabled.set(enabled)
    }

    fun setFpsCounter(enabled: Boolean) {
        settingsStore.fpsCounter.set(enabled)
    }
}
