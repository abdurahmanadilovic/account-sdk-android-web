package com.schibsted.account.android.webflows.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.schibsted.account.android.webflows.AuthState
import com.schibsted.account.android.webflows.Logging
import com.schibsted.account.android.webflows.MfaType
import com.schibsted.account.android.webflows.activities.AuthorizationManagementActivity
import com.schibsted.account.android.webflows.api.HttpError
import com.schibsted.account.android.webflows.api.SchibstedAccountApi
import com.schibsted.account.android.webflows.persistence.EncryptedSharedPrefsStorage
import com.schibsted.account.android.webflows.persistence.SessionStorage
import com.schibsted.account.android.webflows.persistence.StateStorage
import com.schibsted.account.android.webflows.token.TokenHandler
import com.schibsted.account.android.webflows.token.UserTokens
import com.schibsted.account.android.webflows.user.StoredUserSession
import com.schibsted.account.android.webflows.user.User
import com.schibsted.account.android.webflows.util.ResultOrError
import com.schibsted.account.android.webflows.util.Util
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.*

sealed class LoginError {
    object AuthStateReadError : LoginError()
    object UnsolicitedResponse : LoginError()
    data class AuthenticationErrorResponse(val error: String, val errorDescription: String?) :
        LoginError()

    data class TokenErrorResponse(val message: String) : LoginError()
    data class UnexpectedError(val message: String) : LoginError()
}

sealed class RefreshTokenError {
    object NoRefreshToken : RefreshTokenError()
    object ConcurrentRefreshFailure : RefreshTokenError()
    data class RefreshRequestFailed(val error: HttpError) : RefreshTokenError()
}

typealias LoginResultHandler = (ResultOrError<User, LoginError>) -> Unit

class Client {
    internal val httpClient: OkHttpClient
    internal val schibstedAccountApi: SchibstedAccountApi
    internal val configuration: ClientConfiguration

    private val tokenHandler: TokenHandler
    private val stateStorage: StateStorage
    private val sessionStorage: SessionStorage

    constructor (
        context: Context,
        configuration: ClientConfiguration,
        httpClient: OkHttpClient
    ) {
        this.configuration = configuration
        stateStorage = StateStorage(context.applicationContext)
        sessionStorage = EncryptedSharedPrefsStorage(context.applicationContext)
        schibstedAccountApi =
            SchibstedAccountApi(configuration.serverUrl.toString().toHttpUrl(), httpClient)
        tokenHandler = TokenHandler(configuration, schibstedAccountApi)
        this.httpClient = httpClient
    }

    internal constructor (
        configuration: ClientConfiguration,
        stateStorage: StateStorage,
        sessionStorage: SessionStorage,
        httpClient: OkHttpClient,
        tokenHandler: TokenHandler,
        schibstedAccountApi: SchibstedAccountApi
    ) {
        this.configuration = configuration
        this.stateStorage = stateStorage
        this.sessionStorage = sessionStorage
        this.tokenHandler = tokenHandler
        this.httpClient = httpClient
        this.schibstedAccountApi = schibstedAccountApi
    }

    @JvmOverloads
    fun getAuthenticationIntent(
        context: Context,
        extraScopeValues: Set<String> = setOf(),
        mfa: MfaType? = null
    ): Intent {
        val customTabsIntent = CustomTabsIntent.Builder().build().apply {
            intent.data = Uri.parse(generateLoginUrl(extraScopeValues, mfa))
        }
        return AuthorizationManagementActivity.createStartIntent(context, customTabsIntent.intent)
    }

    @JvmOverloads
    fun launchAuth(
        context: Context,
        extraScopeValues: Set<String> = setOf(),
        mfa: MfaType? = null
    ) {
        CustomTabsIntent.Builder()
            .build()
            .launchUrl(context, Uri.parse(generateLoginUrl(extraScopeValues, mfa)))
    }

    internal fun generateLoginUrl(
        extraScopeValues: Set<String> = setOf(),
        mfa: MfaType? = null
    ): String {
        val state = Util.randomString(10)
        val nonce = Util.randomString(10)
        val codeVerifier = Util.randomString(60)

        stateStorage.setValue(AUTH_STATE_KEY, AuthState(state, nonce, codeVerifier, mfa))

        val scopes = extraScopeValues.union(setOf("openid", "offline_access"))
        val scopeString = scopes.joinToString(" ")

        val codeChallenge = computeCodeChallenge(codeVerifier)
        val authParams: MutableMap<String, String> = mutableMapOf(
            "client_id" to configuration.clientId,
            "redirect_uri" to configuration.redirectUri,
            "response_type" to "code",
            "state" to state,
            "scope" to scopeString,
            "nonce" to nonce,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        )

        if (mfa != null) {
            authParams["acr_values"] = mfa.value
        } else {
            authParams["prompt"] = "select_account"
        }

        val loginUrl = "${configuration.serverUrl}/oauth/authorize?${Util.queryEncode(authParams)}"
        Log.d(Logging.SDK_TAG, "Login url: $loginUrl")
        return loginUrl
    }

    private fun computeCodeChallenge(codeVerifier: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(codeVerifier.toByteArray())
        val digest = md.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun handleAuthenticationResponse(intent: Intent, callback: LoginResultHandler) {
        val authResponse = intent.data?.query ?: return callback(
            ResultOrError.Failure(
                LoginError.UnexpectedError("No authentication response")
            )
        )
        handleAuthenticationResponse(authResponse, callback)
    }

    private fun handleAuthenticationResponse(
        authResponseParameters: String,
        callback: LoginResultHandler
    ) {
        val authResponse = Util.parseQueryParameters(authResponseParameters)
        val stored = stateStorage.getValue(AUTH_STATE_KEY, AuthState::class)
            ?: return callback(ResultOrError.Failure(LoginError.AuthStateReadError))

        if (stored.state != authResponse["state"]) {
            callback(ResultOrError.Failure(LoginError.UnsolicitedResponse))
            return
        }
        stateStorage.removeValue(AUTH_STATE_KEY)

        val error = authResponse["error"]
        if (error != null) {
            val oauthError =
                LoginError.AuthenticationErrorResponse(error, authResponse["error_description"])
            callback(ResultOrError.Failure(oauthError))
            return
        }

        val authCode = authResponse["code"]
            ?: return callback(ResultOrError.Failure(LoginError.UnexpectedError("Missing authorization code in authentication response")))

        tokenHandler.makeTokenRequest(
            authCode,
            stored
        ) { result ->
            result.onSuccess { tokenResponse ->
                Log.d(Logging.SDK_TAG, "Token response: $tokenResponse")
                val userSession = StoredUserSession(
                    configuration.clientId,
                    tokenResponse.userTokens,
                    Date()
                )
                sessionStorage.save(userSession)
                callback(ResultOrError.Success(User(this, tokenResponse.userTokens)))
            }.onFailure { err ->
                Log.d(Logging.SDK_TAG, "Token error response: $err")
                callback(ResultOrError.Failure(LoginError.TokenErrorResponse(err.toString())))
            }
        }
    }

    fun resumeLastLoggedInUser(): User? {
        val session = sessionStorage.get(configuration.clientId) ?: return null
        return User(this, session.userTokens)
    }

    internal fun destroySession() {
        sessionStorage.remove(configuration.clientId)
    }

    internal fun refreshTokensForUser(user: User): ResultOrError<UserTokens, RefreshTokenError> {
        val refreshToken = user.tokens.refreshToken ?: return ResultOrError.Failure(
            RefreshTokenError.NoRefreshToken
        )

        val result = tokenHandler.makeTokenRequest(refreshToken, scope = null)
        return when (result) {
            is ResultOrError.Success -> {
                val newAccessToken = result.value.access_token
                val newRefreshToken = result.value.refresh_token ?: refreshToken
                val userTokens = user.tokens.copy(
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken
                )
                user.tokens = userTokens
                val userSession =
                    StoredUserSession(configuration.clientId, userTokens, Date())
                sessionStorage.save(userSession)
                Log.i(Logging.SDK_TAG, "Refreshed user tokens: $result")
                ResultOrError.Success(userTokens)
            }
            is ResultOrError.Failure -> {
                Log.e(Logging.SDK_TAG, "Failed to refresh token: $result")
                ResultOrError.Failure(RefreshTokenError.RefreshRequestFailed(result.error.cause))
            }
        }
    }

    internal companion object {
        const val AUTH_STATE_KEY = "AuthState"
    }
}
