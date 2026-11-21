package com.example.data.ai.agent

import android.content.Context
import com.example.data.ai.AiFailoverPolicy
import com.example.data.ai.AiFailoverSettings
import com.example.data.ai.AiModelConfig
import com.example.data.ai.AiProviderType
import com.example.data.ai.AiRuntime
import com.example.data.ai.AiUsageStore
import com.example.data.ai.ApiHttpException
import com.example.data.ai.FailoverShift
import com.example.data.ai.GeminiAiService
import com.example.data.security.SecureTokenManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun protocolFor(type: AiProviderType): AgentProtocol = when (type) {
    AiProviderType.GOOGLE_GEMINI -> GeminiProtocol
    AiProviderType.ANTHROPIC -> AnthropicProtocol
    AiProviderType.OPENAI_COMPATIBLE, AiProviderType.OLLAMA -> OpenAiProtocol
}

/**
 * HTTP transport for the agent loop with model failover (429/402/401/403/404/5xx/timeout -> next model).
 * Failover is decided per request, before any tool of that step has run, so no file operation can repeat.
 * Prompts/answers are never logged; API keys travel only in headers.
 */
class AgentModelClient(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build(),
    private val maxOutputTokens: Int = 8192
) : AgentTransport {

    override suspend fun complete(system: String, messages: List<AgentMessage>, tools: List<ToolSpec>): ModelTurn {
        AiUsageStore.load(context)
        val configs = SecureTokenManager.getAiModelConfigs(context)
        val byId: Map<String, AiModelConfig>
        val keys = HashMap<String, String?>()
        val candidates: List<String>
        if (configs.isEmpty()) {
            val legacyKey = GeminiAiService(context).getEffectiveApiKey()
            if (legacyKey.isNullOrBlank()) throw IllegalStateException("No AI API Key found. Add a model in Settings -> API.")
            val legacy = AiModelConfig(
                id = LEGACY_ID, displayName = "Gemini Flash", providerType = AiProviderType.GOOGLE_GEMINI.label,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta", modelName = "gemini-2.5-flash"
            )
            byId = mapOf(LEGACY_ID to legacy); keys[LEGACY_ID] = legacyKey; candidates = listOf(LEGACY_ID)
        } else {
            byId = configs.associateBy { it.id }
            val primary = SecureTokenManager.getActiveAiModel(context)!!.first.id
            candidates = AiFailoverPolicy.orderCandidates(
                primaryId = primary, allConfiguredIds = configs.map { it.id },
                backupOrder = AiFailoverSettings.getBackupOrder(context),
                failoverEnabled = AiFailoverSettings.isEnabled(context), tracker = AiRuntime.cooldowns
            )
            candidates.forEach { keys[it] = SecureTokenManager.getAiModelApiKey(context, it) }
        }

        val shifts = mutableListOf<FailoverShift>()
        var last: Throwable? = null
        for ((index, id) in candidates.withIndex()) {
            val config = byId[id] ?: continue
            AiRuntime.usage.onAttempt(id)
            try {
                val turn = callOnce(config, keys[id].orEmpty(), system, messages, tools)
                AiRuntime.cooldowns.clear(id)
                AiUsageStore.save(context)
                val notice = shifts.firstOrNull()?.let {
                    AiFailoverPolicy.noticeBn(byId[it.fromId]?.displayName ?: "Primary", config.displayName, it.kind)
                }
                return turn.copy(modelLabel = config.displayName, failoverNotice = notice)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val kind = AiFailoverPolicy.classify(t)
                AiRuntime.usage.onFailure(id, kind)
                last = t
                if (!AiFailoverPolicy.shouldFailover(kind)) throw t
                AiRuntime.cooldowns.markFailed(id, kind, (t as? ApiHttpException)?.retryAfterSeconds)
                val next = candidates.getOrNull(index + 1) ?: throw t
                val shift = FailoverShift(id, next, kind)
                shifts.add(shift); AiRuntime.usage.onShift(shift)
            }
        }
        AiUsageStore.save(context)
        throw last ?: IllegalStateException("No AI model available.")
    }

    private suspend fun callOnce(
        config: AiModelConfig, key: String, system: String, messages: List<AgentMessage>, tools: List<ToolSpec>
    ): ModelTurn {
        if (config.requiresApiKey && key.isBlank()) {
            throw ApiHttpException(401, "", null, "API key is missing for ${config.displayName}. Configure it in Settings -> API.")
        }
        val protocol = protocolFor(AiProviderType.fromLabel(config.providerType))
        val req = protocol.buildRequest(config.baseUrl, config.modelName, key, system, messages, if (config.supportsTools) tools else emptyList(), maxOutputTokens)
        val builder = Request.Builder().url(req.url).post(req.body.toRequestBody("application/json".toMediaType()))
        req.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        val response = http.newCall(builder.build()).await()
        return response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw ApiHttpException(r.code, body, AiFailoverPolicy.parseRetryAfter(r.header("Retry-After")),
                    "${config.displayName} API error (${r.code}): ${errorMessage(body)}")
            }
            val turn = try { protocol.parseResponse(body) } catch (e: Exception) {
                throw IllegalStateException("Could not read the ${config.displayName} response: ${e.message}")
            }
            AiRuntime.usage.addEstimatedTokens(config.id, req.body.length, body.length)
            turn
        }
    }

    private fun errorMessage(body: String): String = try {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: o.optString("message").takeIf { it.isNotBlank() } ?: body.take(180)
    } catch (_: Exception) { body.take(180) }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { try { cancel() } catch (_: Throwable) {} }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    companion object { const val LEGACY_ID = "legacy-gemini" }
}
