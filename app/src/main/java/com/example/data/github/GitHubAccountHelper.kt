package com.example.data.github

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class TokenValidationStatus(val label: String) {
    UNCHECKED("Not Verified"),
    VALID("Valid"),
    INVALID_OR_EXPIRED("Invalid / Expired"),
    INSUFFICIENT_SCOPE("Insufficient Permissions"),
    RATE_LIMITED("Rate Limited"),
    NETWORK_ERROR("Network Error")
}

data class TokenValidationResult(
    val status: TokenValidationStatus,
    val user: GitHubUser? = null,
    val scopes: List<String> = emptyList(),
    val isFineGrained: Boolean = false,
    val message: String,
    val missingRecommendedScopes: List<String> = emptyList()
)

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

sealed class DeviceFlowPollResult {
    data class Authorized(val accessToken: String, val scope: String) : DeviceFlowPollResult()
    data class Pending(val message: String) : DeviceFlowPollResult()
    data class SlowDown(val nextIntervalSeconds: Int) : DeviceFlowPollResult()
    data class Error(val message: String) : DeviceFlowPollResult()
}

object GitHubAccountHelper {

    const val URL_GITHUB_SIGNUP = "https://github.com/signup"
    const val URL_CREATE_FINE_GRAINED_PAT = "https://github.com/settings/personal-access-tokens/new"
    const val URL_CREATE_CLASSIC_PAT = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=Nexa+IDE"
    const val URL_GITHUB_OAUTH_APPS = "https://github.com/settings/developers"

    private val oauthClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Classifies GitHub token format purely locally without sending over network.
     */
    fun classifyTokenFormat(rawToken: String): String {
        val token = rawToken.trim()
        return when {
            token.startsWith("github_pat_") -> "Fine-grained PAT"
            token.startsWith("ghp_") -> "Classic Personal Access Token"
            token.startsWith("gho_") -> "OAuth Access Token"
            token.startsWith("ghu_") -> "GitHub App User Token"
            else -> "Custom / Legacy Token"
        }
    }

    /**
     * Evaluates an HTTP response from GET https://api.github.com/user cleanly (also unit-testable).
     */
    fun evaluateUserResponse(
        httpCode: Int,
        user: GitHubUser?,
        oauthScopesHeader: String?,
        rateLimitRemaining: String?,
        errorBodyText: String? = null,
        rawToken: String = ""
    ): TokenValidationResult {
        val scopes = oauthScopesHeader
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val isFineGrained = rawToken.trim().startsWith("github_pat_") || oauthScopesHeader == null

        return when (httpCode) {
            200 -> {
                if (user == null) {
                    TokenValidationResult(
                        status = TokenValidationStatus.NETWORK_ERROR,
                        message = "Received HTTP 200 but GitHub user payload was empty."
                    )
                } else {
                    // For classic PAT / OAuth tokens GitHub returns X-OAuth-Scopes header.
                    // Fine-grained PATs omit X-OAuth-Scopes (fine-grained permissions are per-resource).
                    val missing = if (oauthScopesHeader != null) {
                        buildList {
                            if (!scopes.contains("repo") && !scopes.contains("public_repo")) add("repo")
                            if (!scopes.contains("workflow")) add("workflow")
                        }
                    } else {
                        emptyList()
                    }
                    if (missing.isNotEmpty()) {
                        TokenValidationResult(
                            status = TokenValidationStatus.INSUFFICIENT_SCOPE,
                            user = user,
                            scopes = scopes,
                            isFineGrained = false,
                            missingRecommendedScopes = missing,
                            message = "Authenticated as @${user.login}, but token lacks recommended scopes: ${missing.joinToString(", ")}. Cloud APK builds require 'repo' and 'workflow'."
                        )
                    } else {
                        val scopeNote = if (oauthScopesHeader == null) {
                            "Fine-grained PAT active (ensure Contents: Read/Write, Workflows: Read/Write, Actions: Read/Write on selected repo)."
                        } else {
                            "Scopes verified: ${scopes.ifEmpty { listOf("default") }.joinToString(", ")}"
                        }
                        TokenValidationResult(
                            status = TokenValidationStatus.VALID,
                            user = user,
                            scopes = scopes,
                            isFineGrained = oauthScopesHeader == null,
                            message = "Connected as @${user.login}. $scopeNote"
                        )
                    }
                }
            }
            401 -> TokenValidationResult(
                status = TokenValidationStatus.INVALID_OR_EXPIRED,
                message = "Token rejected by GitHub (HTTP 401 Unauthorized). It may be expired or revoked."
            )
            403 -> {
                val isRateLimited = rateLimitRemaining == "0" ||
                    (errorBodyText?.contains("rate limit", ignoreCase = true) == true)
                if (isRateLimited) {
                    TokenValidationResult(
                        status = TokenValidationStatus.RATE_LIMITED,
                        message = "GitHub API rate limit exceeded (HTTP 403). Please wait before retrying."
                    )
                } else {
                    TokenValidationResult(
                        status = TokenValidationStatus.INSUFFICIENT_SCOPE,
                        message = "GitHub denied access (HTTP 403 Forbidden). Check token permissions."
                    )
                }
            }
            429 -> TokenValidationResult(
                status = TokenValidationStatus.RATE_LIMITED,
                message = "GitHub API rate limit exceeded (HTTP 429). Please retry later."
            )
            else -> TokenValidationResult(
                status = TokenValidationStatus.NETWORK_ERROR,
                message = "Unexpected GitHub API response HTTP $httpCode${errorBodyText?.let { ": $it" } ?: ""}"
            )
        }
    }

    suspend fun verifyTokenLive(service: GitHubService, rawToken: String): TokenValidationResult {
        val trimmed = rawToken.trim()
        if (trimmed.isBlank()) {
            return TokenValidationResult(
                status = TokenValidationStatus.INVALID_OR_EXPIRED,
                message = "Token cannot be empty."
            )
        }
        return try {
            val response = service.verifyAuthenticatedUser("Bearer $trimmed")
            val headers = response.headers()
            val errorText = if (!response.isSuccessful) response.errorBody()?.string()?.take(200) else null
            evaluateUserResponse(
                httpCode = response.code(),
                user = response.body(),
                oauthScopesHeader = headers["X-OAuth-Scopes"],
                rateLimitRemaining = headers["X-RateLimit-Remaining"],
                errorBodyText = errorText,
                rawToken = trimmed
            )
        } catch (e: IOException) {
            TokenValidationResult(
                status = TokenValidationStatus.NETWORK_ERROR,
                message = "Network error while contacting api.github.com: ${e.message ?: "Check internet connection"}"
            )
        } catch (e: Exception) {
            TokenValidationResult(
                status = TokenValidationStatus.NETWORK_ERROR,
                message = "Verification error: ${e.message}"
            )
        }
    }

    /**
     * Initiates real GitHub OAuth 2.0 Device Authorization Grant (RFC 8628).
     * Requires a genuine GitHub OAuth App / GitHub App Client ID configured by the developer/user.
     */
    fun requestOAuthDeviceCode(clientId: String, scope: String = "repo workflow read:user"): Result<DeviceCodeResponse> {
        val cleanId = clientId.trim()
        if (cleanId.isBlank()) {
            return Result.failure(IllegalArgumentException("A GitHub OAuth App Client ID is required."))
        }
        return try {
            val body = FormBody.Builder()
                .add("client_id", cleanId)
                .add("scope", scope)
                .build()
            val req = Request.Builder()
                .url("https://github.com/login/device/code")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()
            oauthClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("GitHub Device Flow HTTP ${resp.code}: $raw"))
                }
                val json = JSONObject(raw)
                if (json.has("error")) {
                    val desc = json.optString("error_description", json.optString("error"))
                    return Result.failure(IllegalStateException(desc))
                }
                Result.success(
                    DeviceCodeResponse(
                        deviceCode = json.getString("device_code"),
                        userCode = json.getString("user_code"),
                        verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
                        expiresInSeconds = json.optInt("expires_in", 900),
                        intervalSeconds = json.optInt("interval", 5)
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Polls GitHub's official Device Flow token exchange endpoint once.
     */
    fun pollOAuthDeviceToken(clientId: String, deviceCode: String): DeviceFlowPollResult {
        return try {
            val body = FormBody.Builder()
                .add("client_id", clientId.trim())
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val req = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()
            oauthClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return DeviceFlowPollResult.Error("GitHub OAuth returned HTTP ${resp.code}")
                }
                val json = JSONObject(raw)
                val token = json.optString("access_token", "")
                if (token.isNotBlank()) {
                    return DeviceFlowPollResult.Authorized(
                        accessToken = token,
                        scope = json.optString("scope", "")
                    )
                }
                when (val err = json.optString("error", "unknown_error")) {
                    "authorization_pending" -> DeviceFlowPollResult.Pending("Waiting for approval on github.com...")
                    "slow_down" -> DeviceFlowPollResult.SlowDown(json.optInt("interval", 10))
                    else -> DeviceFlowPollResult.Error(json.optString("error_description", err))
                }
            }
        } catch (e: Exception) {
            DeviceFlowPollResult.Error("Network error: ${e.message}")
        }
    }

    fun formatErrorBody(httpCode: Int, rawErrorBody: String?): String {
        val parsedMsg = try {
            if (!rawErrorBody.isNullOrBlank()) {
                val obj = JSONObject(rawErrorBody)
                val msg = obj.optString("message", "")
                val errorsArr = obj.optJSONArray("errors")
                val firstErr = errorsArr?.optJSONObject(0)?.optString("message", "")
                listOf(msg, firstErr).filter { !it.isNullOrBlank() }.joinToString(" — ")
            } else ""
        } catch (_: Exception) {
            ""
        }
        return when (httpCode) {
            401 -> "Authentication expired or invalid (HTTP 401). Please update your GitHub token."
            403 -> if (parsedMsg.contains("rate limit", ignoreCase = true)) {
                "GitHub API rate limit exceeded. Wait a moment before retrying."
            } else {
                "Permission denied (HTTP 403): ${parsedMsg.ifBlank { "Ensure token has 'repo' and 'workflow' permissions." }}"
            }
            404 -> parsedMsg.ifBlank { "Resource or branch not found (HTTP 404)." }
            409 -> "Repository is empty — push initial files to create the first commit."
            422 -> "Validation error (HTTP 422): ${parsedMsg.ifBlank { "Invalid request parameters or resource already exists." }}"
            else -> parsedMsg.ifBlank { "GitHub API error (HTTP $httpCode)" }
        }
    }

    fun formatThrowable(e: Throwable): String {
        return when (e) {
            is retrofit2.HttpException -> {
                val errBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                formatErrorBody(e.code(), errBody)
            }
            is IOException -> "Network connection error: ${e.message ?: "Check your internet connection"}"
            else -> e.message ?: "Unexpected GitHub error"
        }
    }
}
