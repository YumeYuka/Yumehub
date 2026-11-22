package moe.yumeyuka.yumehub.data.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** 解析系统默认浏览器；无主机名 https 只匹配通用浏览器，不会命中域名专属应用。 */
fun defaultBrowserPackage(context: Context): String? =
    resolveBrowserPackage(context, Intent(Intent.ACTION_VIEW, Uri.parse("https:")))
        ?: resolveBrowserPackage(
            context,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
        )

/** 打开外部链接；网页链接固定交给系统默认浏览器，其他协议保持普通 ACTION_VIEW。 */
fun openExternalUri(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    val webUrl = isWebUri(uri)
    val intent =
        Intent(Intent.ACTION_VIEW, uri).apply {
            if (webUrl) addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    if (webUrl) {
        intent.setPackage(defaultBrowserPackage(context) ?: return false)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun resolveBrowserPackage(context: Context, intent: Intent): String? =
    context.packageManager
        .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?.packageName
        ?.takeUnless(::isSystemResolver)

private fun isWebUri(uri: Uri): Boolean =
    uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)

private fun isSystemResolver(packageName: String): Boolean = packageName in SYSTEM_RESOLVER_PACKAGES

private val SYSTEM_RESOLVER_PACKAGES = setOf("android", "com.android.intentresolver")
