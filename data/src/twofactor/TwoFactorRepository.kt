package moe.yumeyuka.yumehub.data.twofactor

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.GraphQlResponse
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.network.SingleSubmission
import moe.yumeyuka.yumehub.data.storage.mmkv.MarkerPhase
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.data.storage.mmkv.TwoFactorRegistrationRecord
import retrofit2.HttpException

interface TwoFactorRepository {
    suspend fun ensureDeviceKey(login: String, generation: Long, force: Boolean)

    suspend fun hasUncertainRegistration(login: String): Boolean = false

    suspend fun acknowledgeUncertainRegistration(login: String) {}

    suspend fun fetchStatus(login: String, generation: Long): MobileAuthStatus

    suspend fun approve(login: String, generation: Long, request: MobileAuthRequest, digits: Int?)

    suspend fun reject(login: String, generation: Long, request: MobileAuthRequest)

    fun forgetDeviceKey(login: String)
}

class GitHubTwoFactorRepository(
    private val graphQlApi: GitHubGraphQlApi,
    private val deviceKeyManager: TwoFactorDeviceKeyStore,
    private val json: Json,
    private val tokens: TokenStore,
    private val cache: CachedDataRepository? = null,
) : TwoFactorRepository {
    private val registrationMutex = Mutex()

    override suspend fun hasUncertainRegistration(login: String): Boolean =
        cache?.twoFactorRegistrations?.load(login)?.phase == MarkerPhase.MAY_HAVE_SENT

    override suspend fun acknowledgeUncertainRegistration(login: String) {
        registrationMutex.withLock {
            val store = cache?.twoFactorRegistrations ?: return@withLock
            val existing = store.load(login) ?: return@withLock
            withContext(Dispatchers.IO) {
                deviceKeyManager.deleteAlias(existing.candidateAlias)
            }
            store.clear(login)
        }
    }

    override suspend fun ensureDeviceKey(
        login: String,
        generation: Long,
        force: Boolean,
    ) = registrationMutex.withLock {
        val regStore = cache?.twoFactorRegistrations
        val existingRecord = regStore?.load(login)
        if (existingRecord != null) {
            when (existingRecord.phase) {
                MarkerPhase.PREPARED -> {
                    withContext(Dispatchers.IO) {
                        deviceKeyManager.deleteAlias(existingRecord.candidateAlias)
                    }
                    regStore.clear(login)
                }
                MarkerPhase.MAY_HAVE_SENT -> {
                    throw TwoFactorRegistrationUncertainException(existingRecord.candidateAlias)
                }
            }
        }
        val registered = withContext(Dispatchers.IO) {
            synchronized(tokens.responseCacheSession.lock) {
                tokens.requireSession(login, generation)
                if (force) tokens.deviceKeyRegistered.set(false)
                !force && tokens.deviceKeyRegistered.value
            }
        }
        if (registered) return@withLock
        val registration = withContext(Dispatchers.IO) { deviceKeyManager.buildRegistration(login) }
        regStore?.put(
            login,
            TwoFactorRegistrationRecord(
                candidateAlias = registration.alias,
                phase = MarkerPhase.PREPARED,
                startedAt = System.currentTimeMillis(),
            ),
        )
        try {
            regStore?.put(
                login,
                TwoFactorRegistrationRecord(
                    candidateAlias = registration.alias,
                    phase = MarkerPhase.MAY_HAVE_SENT,
                    startedAt = System.currentTimeMillis(),
                ),
            )
            withContext(Dispatchers.IO) {
                val variables = buildJsonObject {
                    put("publicKey", registration.publicKey)
                    put("type", "AUTH")
                    put("verificationSignature", registration.verificationSignature)
                    put("verificationMessage", registration.verificationMessage)
                    put("deviceName", registration.deviceName)
                    put("deviceModel", registration.deviceModel)
                    put("isHardwareBacked", registration.isHardwareBacked)
                }
                val response = execute(login, generation, ADD_PUBLIC_KEY_MUTATION, variables)
                if (
                    (response.data as? JsonObject)?.get("addMobileDevicePublicKey") !is JsonObject
                ) {
                    throw TwoFactorUncertainSubmissionException("注册设备密钥失败：GitHub 未确认注册结果")
                }
                synchronized(tokens.responseCacheSession.lock) {
                    tokens.requireSession(login, generation)
                    deviceKeyManager.activateRegistration(login, registration)
                    tokens.deviceKeyRegistered.set(true)
                }
            }
            regStore?.clear(login)
        } catch (error: Exception) {
            val definiteRejection =
                error is TwoFactorException && error.definiteRejection
            if (definiteRejection || regStore == null) {
                try {
                    withContext(Dispatchers.IO) {
                        deviceKeyManager.discardRegistration(registration)
                    }
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                regStore?.clear(login)
                throw error
            }
            if (error is TwoFactorRegistrationUncertainException) throw error
            throw TwoFactorRegistrationUncertainException(
                candidateAlias = registration.alias,
                cause = error,
            )
        }
    }

    override suspend fun fetchStatus(login: String, generation: Long): MobileAuthStatus {
        val response = execute(login, generation, MOBILE_AUTH_REQUESTS_QUERY)
        val data =
            response.data
                ?.takeUnless { it == JsonNull }
                ?.let { json.decodeFromJsonElement(MobileAuthViewerData.serializer(), it) }
        val status =
            data?.viewer?.mobileAuthStatus
                ?: throw TwoFactorException("GitHub 未返回 mobileAuthStatus，无法检查验证请求")
        val active =
            status.activeAuthRequest?.let {
                MobileAuthRequest(
                    id = it.id,
                    payload = it.payload,
                    requiresChallenge = it.challengeRequired,
                    type = MobileAuthRequestType.fromWire(it.type),
                )
            }
        return MobileAuthStatus(
            hasValidDeviceAuthKey = status.hasValidDeviceAuthKey,
            hasExpiredAuthRequest = status.hasExpiredAuthRequest,
            activeRequest = active,
        )
    }

    override suspend fun approve(
        login: String,
        generation: Long,
        request: MobileAuthRequest,
        digits: Int?,
    ) {
        tokens.requireSession(login, generation)
        val challengeDigits = if (request.requiresChallenge) digits else null
        if (request.requiresChallenge && challengeDigits == null) {
            throw TwoFactorException("A confirmation number is required to approve this request")
        }
        val signature =
            withContext(Dispatchers.IO) {
                deviceKeyManager.signApproval(login, request.payload, challengeDigits)
            }
        val variables = buildJsonObject {
            put("requestId", request.id)
            put("signature", signature)
        }
        execute(login, generation, APPROVE_MUTATION, variables)
    }

    override suspend fun reject(login: String, generation: Long, request: MobileAuthRequest) {
        val variables = buildJsonObject { put("requestId", request.id) }
        execute(login, generation, REJECT_MUTATION, variables)
    }

    override fun forgetDeviceKey(login: String) = deviceKeyManager.deleteKey(login)

    private suspend fun execute(
        login: String,
        generation: Long,
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
    ): GraphQlResponse {
        val registration = query == ADD_PUBLIC_KEY_MUTATION
        val isMutation =
            query == ADD_PUBLIC_KEY_MUTATION ||
                query == APPROVE_MUTATION ||
                query == REJECT_MUTATION
        val response =
            try {
                graphQlApi.execute(
                    GraphQlRequest(query = query, variables = variables),
                    headers = tokens.authorization(login, generation),
                    policy = if (isMutation) SingleSubmission else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val http = error as? HttpException
                val isDefiniteHttp =
                    http != null && http.code() in setOf(400, 401, 403, 404, 409, 422)
                if (registration) {
                    val requestId =
                        http?.response()?.headers()?.get("X-GitHub-Request-Id")?.takeIf {
                            it.matches(Regex("[A-Za-z0-9:-]{1,128}"))
                        }
                    val message =
                        "注册设备密钥失败：" +
                            if (http != null) {
                                "HTTP ${http.code()}" + (requestId?.let { "，requestId=$it" } ?: "")
                            } else {
                                error.message ?: error.javaClass.simpleName
                            }
                    throw TwoFactorException(message, error, definiteRejection = isDefiniteHttp)
                }
                if (isMutation && !isDefiniteHttp) {
                    throw TwoFactorUncertainSubmissionException(
                        error.message ?: "提交验证请求状态未知",
                        error,
                    )
                }
                throw TwoFactorException(
                    error.message ?: "GraphQL request failed",
                    error,
                    definiteRejection = isDefiniteHttp,
                )
            }
        tokens.requireSession(login, generation)
        if (response.errors.isNotEmpty()) {
            val error = response.errors.first()
            val message = error.message ?: "GraphQL request failed"
            val code = (error.extensions?.get("code") as? JsonPrimitive)?.contentOrNull
            val classification =
                (error.extensions?.get("classification") as? JsonPrimitive)?.contentOrNull
            throw TwoFactorException(
                if (registration) {
                    "注册设备密钥失败：$message " +
                        "(type=${diagnosticCategory(error.type)}, code=${diagnosticCategory(code)}, " +
                        "classification=${diagnosticCategory(classification)})"
                } else message,
                definiteRejection = true,
            )
        }
        return response
    }

    private companion object {
        private fun diagnosticCategory(value: String?): String =
            when (value) {
                null -> "NONE"
                "FORBIDDEN",
                "UNPROCESSABLE",
                "UNAUTHORIZED",
                "UNAUTHENTICATED",
                "BAD_USER_INPUT",
                "INSUFFICIENT_SCOPES",
                "NOT_FOUND",
                "INTERNAL",
                "INTERNAL_SERVER_ERROR",
                "SERVICE_UNAVAILABLE" -> value
                else -> "OTHER"
            }

        private const val MOBILE_AUTH_REQUESTS_QUERY =
            "query MobileAuthRequests { viewer { email primaryEmail login mobileAuthStatus { " +
                "hasValidDeviceAuthKey hasExpiredAuthRequest activeAuthRequest { id payload " +
                "challengeRequired type } } id __typename } id __typename }"

        private const val ADD_PUBLIC_KEY_MUTATION =
            "mutation AddMobileDevicePublicKey(\$publicKey: String!, \$type: MobileDeviceKeyType!, " +
                "\$verificationSignature: String!, \$verificationMessage: String!, \$deviceName: " +
                "String!, \$deviceModel: String!, \$isHardwareBacked: Boolean!) { " +
                "addMobileDevicePublicKey(input: { publicKey: \$publicKey verificationSignature: " +
                "\$verificationSignature verificationMessage: \$verificationMessage type: \$type " +
                "deviceName: \$deviceName deviceModel: \$deviceModel deviceOs: ANDROID " +
                "isHardwareBacked: \$isHardwareBacked } ) { expiresAt } }"

        private const val APPROVE_MUTATION =
            "mutation ApproveMobileAuthDeviceRequest(\$requestId: Int!, \$signature: String!) { " +
                "approveMobileAuthDeviceRequest(input: { requestId: \$requestId signature: " +
                "\$signature signatureVersion: V1 } ) { clientMutationId } }"

        private const val REJECT_MUTATION =
            "mutation RejectMobileAuthDeviceRequest(\$requestId: Int!) { " +
                "rejectMobileAuthDeviceRequest(input: { requestId: \$requestId } ) { " +
                "clientMutationId } }"
    }
}
