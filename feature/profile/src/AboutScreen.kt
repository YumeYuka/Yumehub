package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.ui.component.GithubSection

internal fun LazyListScope.AboutScreen() {
    item(key = "about-app") { AboutAppSection() }
    item(key = "about-credits") {
        GithubSection("开源组件") {
            GlasmioListRow(title = "Glasmio", subtitle = "玻璃材质与 iOS 风格组件", detail = "0.1.2")
            GlasmioListRow(title = "Miuix", subtitle = "文本组件", detail = "0.9.4")
            GlasmioListRow(title = "Lucide", subtitle = "图标集", detail = "1.0.0")
            GlasmioListRow(title = "Jetpack Compose", subtitle = "UI 运行时", detail = "M3")
        }
    }
    item(key = "about-note") {
        GithubSection("说明") {
            GlasmioListRow(
                title = "GitHub 客户端",
                subtitle = "非官方客户端，数据来自 GitHub REST API",
                systemName = null,
            )
        }
    }
}

@Composable
private fun AboutAppSection() {
    val context = LocalContext.current
    val versionName =
        remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
                .getOrNull() ?: "1.0"
        }

    GithubSection("应用") {
        GlasmioListRow(title = "名称", detail = "Github")
        GlasmioListRow(title = "版本", detail = versionName)
        GlasmioListRow(title = "包名", detail = context.packageName, subtitle = null)
    }
}
