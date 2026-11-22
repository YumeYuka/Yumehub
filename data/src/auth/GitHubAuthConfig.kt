package moe.yumeyuka.yumehub.data.auth

import android.net.Uri

object GitHubAuthConfig {
    const val CLIENT_ID = "3f8b8834a91f0caad392"
    const val CLIENT_SECRET = "00e76fc8358899d7795a46cd04ace865fcdc0165"
    const val REDIRECT_URI = "github://com.github.android/oauth"
    const val SCOPE =
        "user repo notifications admin:org read:discussion user:assets project workflow"
    const val AUTHORIZATION_ENDPOINT = "https://github.com/login/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"
    const val METADATA_ENDPOINT =
        "https://github.com/.well-known/oauth-authorization-server/login/oauth"
    const val ISSUER = "https://github.com/login/oauth"
    const val ALLOW_SIGN_UP = false

    val redirectUri: Uri = Uri.parse(REDIRECT_URI)
    val authorizationEndpoint: Uri = Uri.parse(AUTHORIZATION_ENDPOINT)
    val tokenEndpoint: Uri = Uri.parse(TOKEN_ENDPOINT)
    val revocationEndpoint: String = "https://api.github.com/applications/$CLIENT_ID/token"
}
