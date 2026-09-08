package com.folderspan.pro.core.datastore

import com.folderspan.pro.core.common.apiDataOrSelf
import com.folderspan.pro.core.common.accessTokenExpiresAtEpochSeconds
import com.folderspan.pro.core.common.normalizeAccessToken
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
    val userUuid: String? = null,
    val userEmail: String? = null,
    val loginData: JsonObject? = null,
    val expiresAtEpochSeconds: Long? = null,
)

fun AuthSession.withUserProfileSnapshot(
    name: String,
    email: String,
    avatar: String?,
): AuthSession {
    val profile = buildJsonObject {
        (loginData?.get(ProfileSnapshotKey) as? JsonObject)?.forEach { (key, value) ->
            put(key, value)
        }
        put("name", name)
        put("email", email)
        put("avatar", avatar?.let(::JsonPrimitive) ?: JsonNull)
    }
    val updatedLoginData = JsonObject(loginData.orEmpty() + (ProfileSnapshotKey to profile))
    return copy(
        userEmail = email,
        loginData = updatedLoginData,
    )
}

object SessionManager {
    private val state = MutableStateFlow<AuthSession?>(null)
    private var sessionStore: AuthSessionStore = InMemoryAuthSessionStore()
    val session: StateFlow<AuthSession?> = state

    fun initialize(store: AuthSessionStore) {
        sessionStore = store
        state.value = store.load()
    }

    fun update(session: AuthSession) {
        sessionStore.save(session)
        state.value = session
    }

    fun clear() {
        sessionStore.clear()
        state.value = null
    }

    fun currentSession(): AuthSession? = state.value

    fun currentToken(): String? = state.value?.accessToken

    fun rememberedEmail(): String? = sessionStore.loadRememberedEmail()
}

fun parseAuthSession(
    payload: JsonElement,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
): AuthSession? {
    val data = payload.apiDataOrSelf() as? JsonObject ?: return null
    val token = normalizeAccessToken(
        data["token"].asString()
        ?: data["accessToken"].asString()
        ?: data["jwt"].asString()
    )
    if (token.isNullOrBlank()) return null
    val refreshToken = data["refreshToken"].asString()
        ?: data["refresh_token"].asString()
    val expires = data["expiresIn"].asLong()
        ?: data["expires_in"].asLong()
    val expiresAt = data["expiresAt"].asLong()?.asEpochSeconds()
        ?: data["expires_at"].asLong()?.asEpochSeconds()
        ?: expires?.let { nowEpochSeconds + it }
    val userUuid = data["uuid"].asString()
        ?: data["userUuid"].asString()
        ?: data["user_id"].asString()
        ?: data["user"].jsonObjectOrNull()?.get("uuid").asString()
    val userEmail = data["email"].asString()
        ?: data["userEmail"].asString()
        ?: data["user"].jsonObjectOrNull()?.get("email").asString()
    return AuthSession(
        accessToken = token,
        refreshToken = refreshToken,
        expiresIn = expires,
        userUuid = userUuid,
        userEmail = userEmail,
        loginData = data,
        expiresAtEpochSeconds = expiresAt,
    )
}

internal fun AuthSession.shouldRefreshAccessToken(
    nowEpochSeconds: Long,
    leewaySeconds: Long = AccessTokenRefreshLeewaySeconds,
): Boolean {
    val expiresAt = expiresAtEpochSeconds
        ?: accessToken.accessTokenExpiresAtEpochSeconds()
        ?: return false
    return nowEpochSeconds >= expiresAt - leewaySeconds
}

private fun JsonElement?.asString(): String? =
    this?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
private fun JsonElement?.asLong(): Long? = this?.jsonPrimitive?.contentOrNull?.toLongOrNull()
private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun Long.asEpochSeconds(): Long =
    if (this > EpochMillisecondsThreshold) this / 1_000 else this

private const val AccessTokenRefreshLeewaySeconds = 30L
private const val EpochMillisecondsThreshold = 10_000_000_000L
private const val ProfileSnapshotKey = "profile"
