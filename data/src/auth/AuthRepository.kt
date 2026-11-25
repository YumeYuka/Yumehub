package moe.yumeyuka.yumehub.data.auth

import android.content.Intent
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.network.useResponse
import moe.yumeyuka.yumehub.data.storage.mmkv.HomeFavoritesStore
import moe.yumeyuka.yumehub.data.storage.mmkv.MaxSavedAccounts
import moe.yumeyuka.yumehub.data.storage.mmkv.SavedAccount
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

interface AuthRepository {
    val session: Flow<AuthSession?>

    fun createAuthorizationIntent(): Intent

    suspend fun completeLogin(result: Intent): AuthSession

    suspend fun recoverSession(rejectedToken: String): SessionRecovery

    suspend fun switchAccount(login: String)

    suspend fun signOut()
}

class GitHubAuthRepository(
    private val authorizationService: AuthorizationService,
    private val graphQl: GitHubGraphQlApi,
    private val tokenStore: TokenStore,
    private val favoritesStore: HomeFavoritesStore,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : AuthRepository {
    private val secureRandom = SecureRandom()
    private val sessionMutex = Mutex()

    override val session = tokenStore.authSession

    override suspend fun switchAccount(login: String) = sessionMutex.withLock {
        withContext(Dispatchers.IO) { tokenStore.selectAccount(login) }
    }

    override fun createAuthorizationIntent(): Intent {
        check(tokenStore.accounts.value.size < MaxSavedAccounts)
        return authorizationService.getAuthorizationRequestIntent(createAuthorizationRequest())
    }

    override suspend fun completeLogin(result: Intent): AuthSession = sessionMutex.withLock {
        val exception = AuthorizationException.fromIntent(result)
        if (exception != null) throw exception.toAuthException()

        val response = AuthorizationResponse.fromIntent(result)
        if (response == null) throw AuthException("Authorization response is missing")

        validateAuthorizationResponse(response)

        val metadata = fetchAuthorizationMetadata()
        validateMetadata(metadata, response)

        val tokenResponse = exchangeAuthorizationCode(response)
        val accessToken = tokenResponse.requireAccessToken()
        val identity =
            graphQl.execute(
                GraphQlRequest(
                    "query LoginViewer { viewer { login avatarUrl } }",
                    operationName = "LoginViewer",
                ),
                headers = mapOf("Authorization" to "Bearer $accessToken"),
            )
        if (identity.errors.isNotEmpty()) throw AuthException("Unable to verify GitHub account")
        val viewer = identity.data?.jsonObject?.get("viewer")?.jsonObject
        val login = viewer?.get("login")?.jsonPrimitive?.contentOrNull
        if (login.isNullOrBlank()) throw AuthException("GitHub did not return the viewer login")
        val scope = tokenResponse.scope ?: response.request.scope ?: GitHubAuthConfig.SCOPE
        withContext(Dispatchers.IO) {
            tokenStore.saveAccount(
                SavedAccount(
                    login = login,
                    avatarUrl = viewer["avatarUrl"]?.jsonPrimitive?.contentOrNull,
                    accessToken = accessToken,
                    refreshToken = tokenResponse.refreshToken.orEmpty(),
                    scope = scope,
                ),
            )
        }
    }

    override suspend fun recoverSession(rejectedToken: String): SessionRecovery =
        sessionMutex.withLock {
            if (tokenStore.accessToken.value != rejectedToken)
                return@withLock SessionRecovery.Ignored

            val refreshToken = tokenStore.refreshToken.value
            if (refreshToken.isBlank()) {
                clearSessionData()
                return@withLock SessionRecovery.Expired
            }

            val tokenRequest =
                TokenRequest.Builder(
                        createAuthorizationServiceConfiguration(),
                        GitHubAuthConfig.CLIENT_ID,
                    )
                    .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                    .setRefreshToken(refreshToken)
                    .build()

            val tokenResponse =
                try {
                    performTokenRequest(tokenRequest)
                } catch (error: AuthException) {
                    val oauthError = (error.cause as? AuthorizationException)?.error
                    if (oauthError !in INVALID_REFRESH_ERRORS) throw error
                    clearSessionData()
                    return@withLock SessionRecovery.Expired
                }
            val accessToken = tokenResponse.requireAccessToken()
            if (accessToken == rejectedToken)
                throw AuthException("GitHub did not renew the access token")
            withContext(Dispatchers.IO) {
                tokenStore.updateTokens(accessToken, tokenResponse.refreshToken, tokenResponse.scope)
            }
            SessionRecovery.Refreshed
        }

    override suspend fun signOut(): Unit = sessionMutex.withLock {
        val accessToken = tokenStore.accessToken.value
        clearSessionData()
        if (accessToken.isNotBlank()) {
            try {
                revokeTokenRemotely(accessToken)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Local sign-out must still succeed when the network call fails.
            }
        }
    }

    /** 会话失效时连同该账号的收藏快照一起清理，避免私有仓库信息残留。 */
    private suspend fun clearSessionData() = withContext(Dispatchers.IO) {
        tokenStore.accountLogin.value.takeIf { it.isNotBlank() }?.let(favoritesStore::clear)
        tokenStore.removeActiveAccount()
    }

    private fun createAuthorizationRequest(): AuthorizationRequest {
        val verifier = generateRandomUrlSafeValue(PKCE_VERIFIER_BYTES)
        val challenge = createCodeChallenge(verifier)

        return AuthorizationRequest.Builder(
                createAuthorizationServiceConfiguration(),
                GitHubAuthConfig.CLIENT_ID,
                ResponseTypeValues.CODE,
                GitHubAuthConfig.redirectUri,
            )
            .setScope(GitHubAuthConfig.SCOPE)
            .setState(generateRandomUrlSafeValue(STATE_BYTES))
            .setNonce(generateRandomUrlSafeValue(NONCE_BYTES))
            .setCodeVerifier(
                verifier,
                challenge,
                AuthorizationRequest.CODE_CHALLENGE_METHOD_S256,
            )
            .apply { if (tokenStore.accounts.value.isNotEmpty()) setPrompt("select_account") }
            .setAdditionalParameters(
                mapOf("allow_signup" to GitHubAuthConfig.ALLOW_SIGN_UP.toString()),
            )
            .build()
    }

    private fun createAuthorizationServiceConfiguration() =
        AuthorizationServiceConfiguration(
            GitHubAuthConfig.authorizationEndpoint,
            GitHubAuthConfig.tokenEndpoint,
        )

    private fun validateAuthorizationResponse(response: AuthorizationResponse) {
        val request = response.request
        if (response.authorizationCode.isNullOrBlank()) {
            throw AuthException("Authorization response does not contain a code")
        }
        if (response.state.isNullOrBlank() || request.state.isNullOrBlank()) {
            throw AuthException("Authorization response state is missing")
        }
        if (response.state != request.state) throw AuthException("Authorization state mismatch")
        if (request.clientId != GitHubAuthConfig.CLIENT_ID) {
            throw AuthException("Authorization client ID mismatch")
        }
        if (request.redirectUri != GitHubAuthConfig.redirectUri) {
            throw AuthException("Authorization redirect URI mismatch")
        }
        if (request.codeVerifier.isNullOrBlank()) {
            throw AuthException("Authorization PKCE verifier is missing")
        }
        if (
            request.codeVerifierChallengeMethod != AuthorizationRequest.CODE_CHALLENGE_METHOD_S256
        ) {
            throw AuthException("Authorization must use PKCE S256")
        }
        if (request.configuration.authorizationEndpoint != GitHubAuthConfig.authorizationEndpoint) {
            throw AuthException("Authorization endpoint mismatch")
        }
        if (request.configuration.tokenEndpoint != GitHubAuthConfig.tokenEndpoint) {
            throw AuthException("Token endpoint mismatch")
        }
    }

    private suspend fun fetchAuthorizationMetadata(): AuthorizationMetadata {
        val request =
            Request.Builder()
                .url(GitHubAuthConfig.METADATA_ENDPOINT)
                .header("Accept", "application/json")
                .build()
        try {
            return okHttpClient.newCall(request).useResponse { response ->
                if (
                    !response.isSuccessful || response.body.source().request(MAX_METADATA_BYTES + 1)
                ) {
                    throw AuthException("Failed to load OAuth metadata")
                }
                val body = response.body.string()
                if (body.isBlank()) throw AuthException("OAuth metadata response is empty")
                json.decodeFromString(AuthorizationMetadata.serializer(), body)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw AuthException("Failed to load OAuth metadata", error)
        }
    }

    private fun validateMetadata(
        metadata: AuthorizationMetadata,
        response: AuthorizationResponse,
    ) {
        if (metadata.issuer != GitHubAuthConfig.ISSUER) {
            throw AuthException("OAuth issuer mismatch")
        }
        if (metadata.authorizationEndpoint != GitHubAuthConfig.AUTHORIZATION_ENDPOINT) {
            throw AuthException("OAuth authorization endpoint mismatch")
        }
        if (metadata.tokenEndpoint != GitHubAuthConfig.TOKEN_ENDPOINT) {
            throw AuthException("OAuth token endpoint mismatch")
        }

        val issuerParameter = response.additionalParameters["iss"]
        if (metadata.authorizationResponseIssParameterSupported) {
            if (issuerParameter.isNullOrBlank() || issuerParameter != GitHubAuthConfig.ISSUER) {
                throw AuthException("Authorization issuer mismatch")
            }
        } else if (!issuerParameter.isNullOrBlank() && issuerParameter != GitHubAuthConfig.ISSUER) {
            throw AuthException("Authorization issuer mismatch")
        }
    }

    private suspend fun exchangeAuthorizationCode(response: AuthorizationResponse): TokenResponse =
        performTokenRequest(response.createTokenExchangeRequest())

    private suspend fun performTokenRequest(tokenRequest: TokenRequest): TokenResponse =
        withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine { continuation ->
                authorizationService.performTokenRequest(
                    tokenRequest,
                    ClientSecretPost(GitHubAuthConfig.CLIENT_SECRET),
                ) { tokenResponse, exception ->
                    if (!continuation.isActive) return@performTokenRequest
                    when {
                        tokenResponse != null -> continuation.resume(tokenResponse)
                        exception != null ->
                            continuation.resumeWithException(exception.toAuthException())
                        else ->
                            continuation.resumeWithException(
                                AuthException("Token exchange returned no response"),
                            )
                    }
                }
            }
        } ?: throw AuthException("Token exchange timed out")

    private fun TokenResponse.requireAccessToken(): String =
        accessToken?.takeIf { it.isNotBlank() }
            ?: throw AuthException("Token exchange did not return an access token")

    private suspend fun revokeTokenRemotely(accessToken: String) {
        val requestBody =
            JSONObject().put("access_token", accessToken).toString().toRequestBody(JSON_MEDIA_TYPE)
        val request =
            Request.Builder()
                .url(GitHubAuthConfig.revocationEndpoint)
                .delete(requestBody)
                .header(
                    "Authorization",
                    Credentials.basic(GitHubAuthConfig.CLIENT_ID, GitHubAuthConfig.CLIENT_SECRET),
                )
                .header("Accept", "application/vnd.github+json")
                .build()
        okHttpClient.newCall(request).useResponse { response ->
            if (!response.isSuccessful) throw AuthException("Failed to revoke the access token")
        }
    }

    private fun generateRandomUrlSafeValue(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, base64UrlSafeFlags)
    }

    private fun createCodeChallenge(verifier: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.ISO_8859_1))
        return Base64.encodeToString(digest, base64UrlSafeFlags)
    }

    private fun AuthorizationException.toAuthException(): AuthException =
        AuthException(
            message = errorDescription ?: error ?: "Authorization failed",
            cause = this,
            isCanceled = code == USER_CANCELED_ERROR_CODE,
        )

    private companion object {
        private const val MAX_METADATA_BYTES = 64 * 1024L
        private const val PKCE_VERIFIER_BYTES = 64
        private const val STATE_BYTES = 16
        private const val NONCE_BYTES = 16
        private const val USER_CANCELED_ERROR_CODE = 1002
        private val INVALID_REFRESH_ERRORS = setOf("bad_refresh_token", "invalid_grant")
        private val base64UrlSafeFlags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
