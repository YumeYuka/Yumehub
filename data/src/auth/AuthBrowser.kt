package moe.yumeyuka.yumehub.data.auth

import android.content.Context
import moe.yumeyuka.yumehub.data.browser.defaultBrowserPackage
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.browser.BrowserMatcher

/** AppAuth 只接受系统默认浏览器，并以普通浏览器页面打开授权页。 */
fun defaultAuthBrowserConfiguration(context: Context): AppAuthConfiguration {
    // 无法解析默认浏览器时退回 AppAuth 自身选择，避免登录完全不可用。
    val browserPackage = defaultBrowserPackage(context) ?: return AppAuthConfiguration.DEFAULT
    return AppAuthConfiguration.Builder()
        .setBrowserMatcher(
            BrowserMatcher { descriptor ->
                descriptor.packageName == browserPackage && descriptor.useCustomTab == false
            },
        )
        .build()
}
