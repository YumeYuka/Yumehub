package moe.yumeyuka.yumehub.data.repository

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 仅加载 GitHub 返回的可信社交预览地址，不自行拼图片端点。 */
class ReleasePreviewImages {
    fun trustedUrl(imageUrl: String?): String? {
        val image = imageUrl?.toHttpUrlOrNull() ?: return null
        if (
            !image.isHttps ||
                image.host !in ImageHosts ||
                image.port != 443 ||
                image.username.isNotEmpty() ||
                image.password.isNotEmpty()
        )
            return null
        return image.toString()
    }

    private companion object {
        val ImageHosts =
            setOf(
                "opengraph.githubassets.com",
                "repository-images.githubusercontent.com",
                "opengraph-images.githubusercontent.com",
            )
    }
}
