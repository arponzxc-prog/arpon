package com.example.data.ai

import android.content.Context
import com.example.BuildConfig
import com.example.data.security.SecureTokenManager
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GeminiPart(val text: String)

@JsonClass(generateAdapter = true)
data class GeminiContent(val role: String = "user", val parts: List<GeminiPart>)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    @Json(name = "system_instruction") val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(val content: GeminiContent?)

@JsonClass(generateAdapter = true)
data class GeminiResponse(val candidates: List<GeminiCandidate>?)

data class AiChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val proposal: AiCodeProposal? = null,
    val executionResult: AiExecutionResult? = null
)

data class AiCodeProposal(
    val originalCode: String,
    val proposedCode: String,
    val summary: String,
    val targetFileName: String? = null
)

enum class DiffLineType { UNCHANGED, ADDED, REMOVED }
data class DiffLine(val type: DiffLineType, val text: String, val oldLineNumber: Int?, val newLineNumber: Int?)

/**
 * Nexa AI Service
 *
 * Supports multi-provider AI routing configured in Settings -> API:
 * - OpenAI / Compatible (OpenRouter, APIQIK, WusRouter, Xingchi, OpenAI, Ollama, LM Studio, Custom)
 * - Google Gemini API
 * - Anthropic Claude Messages API
 * Falls back cleanly to legacy Keystore Gemini API key or BuildConfig if no multi-provider model is configured.
 */
class GeminiAiService(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val requestAdapter = moshi.adapter(GeminiRequest::class.java)
    private val responseAdapter = moshi.adapter(GeminiResponse::class.java)


    /** Human-readable notice for the last request that shifted to a backup model (null otherwise). */
    @Volatile var lastFailoverNotice: String? = null
        private set

    private fun callConfig(
        config: AiModelConfig,
        key: String?,
        systemText: String,
        history: List<AiChatMessage>,
        contextualPrompt: String
    ): String {
        if (config.requiresApiKey && key.isNullOrBlank()) {
            throw IllegalStateException("API key is missing for ${config.displayName}. Please configure it in Settings -> API.")
        }
        return when (AiProviderType.fromLabel(config.providerType)) {
            AiProviderType.GOOGLE_GEMINI -> callGemini(config.baseUrl, config.modelName, key.orEmpty(), systemText, history, contextualPrompt)
            AiProviderType.ANTHROPIC -> callAnthropic(config.baseUrl, config.modelName, key.orEmpty(), systemText, history, contextualPrompt)
            AiProviderType.OPENAI_COMPATIBLE,
            AiProviderType.OLLAMA -> callOpenAiCompatible(config.baseUrl, config.modelName, key.orEmpty(), systemText, history, contextualPrompt)
        }
    }

    /**
     * Runs the request on the active model and shifts to backup models on 429/402/401/403/404/5xx/timeout.
     * Cooldowns and usage counters are process-wide ([AiRuntime]); no content is stored.
     */
    private fun routeWithFailover(
        primaryId: String,
        call: (AiModelConfig, String?) -> String
    ): String {
        lastFailoverNotice = null
        AiUsageStore.load(context)
        val configs = SecureTokenManager.getAiModelConfigs(context)
        val byId = configs.associateBy { it.id }
        val candidates = AiFailoverPolicy.orderCandidates(
            primaryId = primaryId,
            allConfiguredIds = configs.map { it.id },
            backupOrder = AiFailoverSettings.getBackupOrder(context),
            failoverEnabled = AiFailoverSettings.isEnabled(context),
            tracker = AiRuntime.cooldowns
        )
        val runner = AiFailoverRunner(AiRuntime.cooldowns, AiRuntime.usage)
        val result = runner.run(candidates) { id ->
            val config = byId[id] ?: throw IllegalStateException("AI model configuration not found.")
            call(config, SecureTokenManager.getAiModelApiKey(context, id))
        }
        AiRuntime.usage.addEstimatedTokens(result.usedId, 0, result.value.length)
        AiUsageStore.save(context)
        result.shifts.lastOrNull()?.let { last ->
            val from = byId[result.shifts.first().fromId]?.displayName ?: "Primary"
            val to = byId[result.usedId]?.displayName ?: "Backup"
            lastFailoverNotice = AiFailoverPolicy.noticeBn(from, to, result.shifts.first().kind)
        }
        return result.value
    }

    fun getEffectiveApiKey(): String? {
        val activeModel = SecureTokenManager.getActiveAiModel(context)
        if (activeModel != null) {
            val (config, key) = activeModel
            if (!config.requiresApiKey) return "local-no-key-required"
            if (!key.isNullOrBlank()) return key
        }

        val storedKey = SecureTokenManager.getGeminiApiKey(context)?.trim()
        if (!storedKey.isNullOrBlank()) return storedKey

        // Fallback to BuildConfig if present and not empty placeholder
        return try {
            val buildKey = BuildConfig::class.java.getField("GEMINI_API_KEY").get(null) as? String
            buildKey?.takeIf { it.isNotBlank() && !it.contains("MY_GEMINI_API_KEY") && !it.contains("YOUR_") }
        } catch (e: Exception) {
            null
        }
    }

    fun getActiveProviderSummary(): String {
        val activeModel = SecureTokenManager.getActiveAiModel(context)
        if (activeModel != null) {
            val (config, _) = activeModel
            return "${config.displayName} • ${config.modelName}"
        }
        return if (!getEffectiveApiKey().isNullOrBlank()) "Gemini Flash • Ready" else "API Key Required"
    }

    suspend fun sendMessage(
        prompt: String,
        history: List<AiChatMessage> = emptyList(),
        currentFileName: String? = null,
        currentFileContent: String? = null
    ): String = withContext(Dispatchers.IO) {
        IdentityResponder.respond(prompt)?.let { return@withContext it }
        val activeModelPair = SecureTokenManager.getActiveAiModel(context)

        val systemText = "You are Nexa AI, a friendly and highly skilled Android & Web software engineer embedded inside Nexa IDE. " +
            "Your job is to assist the developer with Kotlin, Java, XML, Gradle, Jetpack Compose, HTML, CSS, JavaScript, and APK building. " +
            "Always provide precise, clean code. When proposing code changes, output the complete updated code block so the developer can review and apply it directly." + IdentityResponder.SYSTEM_PROMPT_RULE

        val contextualPrompt = buildString {
            if (!currentFileName.isNullOrBlank() && !currentFileContent.isNullOrBlank()) {
                append("Active File Context: '$currentFileName'\n```\n")
                append(currentFileContent.take(4000))
                append("\n```\n\nUser Question / Task:\n")
            }
            append(prompt)
        }

        if (activeModelPair != null) {
            val reply = routeWithFailover(activeModelPair.first.id) { config, key ->
                callConfig(config, key, systemText, history, contextualPrompt)
            }
            return@withContext lastFailoverNotice?.let { "$reply\n\n$it" } ?: reply
        }

        // Legacy fallback to Gemini
        val apiKey = getEffectiveApiKey()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("No AI API Key found. Please add an API model in Settings -> API or enter your Gemini API key.")
        }

        return@withContext callGemini(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            systemText = systemText,
            history = history,
            contextualPrompt = contextualPrompt
        )
    }

    suspend fun sendAgentMessage(
        prompt: String,
        history: List<AiChatMessage> = emptyList(),
        agentModeEnabled: Boolean = true,
        projectContextSummary: String? = null,
        currentFileName: String? = null,
        currentFileContent: String? = null
    ): String = withContext(Dispatchers.IO) {
        IdentityResponder.respond(prompt)?.let { return@withContext it }
        if (!agentModeEnabled) {
            return@withContext sendMessage(prompt, history, currentFileName, currentFileContent)
        }

        val activeModelPair = SecureTokenManager.getActiveAiModel(context)

        val agentSystemText = """
            You are Nexa AI, an autonomous senior Android & Web software engineer embedded inside Nexa IDE.
            You CAN create brand-new standalone Android (Kotlin, Jetpack Compose, Java, WebView) or Web (HTML/CSS/JS) projects AND write or update multiple complete code files directly inside the user's project workspace!

            WHEN THE USER ASKS TO CREATE A NEW APP/PROJECT OR GENERATE/EDIT PROJECT FILES:
            1. First give a concise, friendly 2-4 sentence summary explaining what you built or changed.
            2. Then output ONE fenced JSON block tagged ```nexa_actions containing:
            ```nexa_actions
            {
              "project": {
                "create": true,
                "name": "My App Name",
                "packageName": "com.nexa.myapp",
                "templateType": "Kotlin Compose App",
                "language": "Kotlin"
              },
              "files": [
                {
                  "path": "app/src/main/java/com/nexa/myapp/MainActivity.kt",
                  "content": "...complete file source code..."
                }
              ],
              "summary": "Brief description of generated project & files"
            }
            ```

            RULES FOR ```nexa_actions:
            - Allowed "templateType" values: "Kotlin Compose App", "Kotlin Android App", "Java Android App", "HTML Single File Project", "HTML Multi-File Project", "Android WebView App".
            - Allowed "language" values: "Kotlin", "Java", "Webapp".
            - Set "project.create": true ONLY when creating a new standalone project; set "create": false (or omit "project") when modifying files inside the currently open project.
            - For Android/Compose projects, ALWAYS match the packageName in package statements, AndroidManifest.xml, and app/build.gradle.kts, and place MainActivity under app/src/main/java/<package_segments>/MainActivity.kt (or .java).
            - For HTML projects, place web assets in app/src/main/assets/index.html (and style.css / script.js for Multi-File) so Live Preview and Android WebView APK builds work immediately.
            - NEVER write TODO placeholders or truncated "...existing code..." comments; output 100% complete, compilable, ready-to-run files.
            - If the user is only asking a general question (not asking to build a project or write files), answer normally without a ```nexa_actions block.
        """.trimIndent() + IdentityResponder.SYSTEM_PROMPT_RULE

        val contextualPrompt = buildString {
            if (!projectContextSummary.isNullOrBlank()) {
                append(projectContextSummary.trim())
                append("\n\n")
            }
            if (!currentFileName.isNullOrBlank() && !currentFileContent.isNullOrBlank()) {
                append("Currently Open Editor File: '$currentFileName'\n```\n")
                append(currentFileContent.take(4000))
                append("\n```\n\n")
            }
            append("User Request:\n")
            append(prompt)
        }

        if (activeModelPair != null) {
            return@withContext routeWithFailover(activeModelPair.first.id) { config, key ->
                callConfig(config, key, agentSystemText, history, contextualPrompt)
            }
        }

        val apiKey = getEffectiveApiKey()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("No AI API Key found. Please add an API model in Settings -> API or enter your Gemini API key.")
        }

        return@withContext callGemini(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            systemText = agentSystemText,
            history = history,
            contextualPrompt = contextualPrompt
        )
    }

    private fun callGemini(
        baseUrl: String,
        modelName: String,
        apiKey: String,
        systemText: String,
        history: List<AiChatMessage>,
        contextualPrompt: String
    ): String {
        val contents = mutableListOf<GeminiContent>()
        val recentHistory = history.takeLast(6)
        for (msg in recentHistory) {
            val role = if (msg.isUser) "user" else "model"
            contents.add(GeminiContent(role = role, parts = listOf(GeminiPart(msg.text))))
        }
        contents.add(GeminiContent(role = "user", parts = listOf(GeminiPart(contextualPrompt))))

        val systemInstruction = GeminiContent(role = "system", parts = listOf(GeminiPart(systemText)))
        val geminiReq = GeminiRequest(contents = contents, systemInstruction = systemInstruction)
        val jsonPayload = requestAdapter.toJson(geminiReq)

        val cleanBase = baseUrl.trimEnd('/')
        val cleanModel = modelName.trim().ifBlank { "gemini-2.5-flash" }
        val url = "$cleanBase/models/$cleanModel:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val retryAfter = AiFailoverPolicy.parseRetryAfter(response.header("Retry-After"))
            val msg = if (response.code == 400 || response.code == 403) {
                "Gemini API Error (${response.code}): Invalid or restricted API key. Please check Settings -> API."
            } else {
                "Gemini API Error (${response.code}): ${extractErrorMessage(responseBody)}"
            }
            throw ApiHttpException(response.code, responseBody, retryAfter, msg)
        }

        val parsed = responseAdapter.fromJson(responseBody)
        val textResult = parsed?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw IllegalStateException("Received empty response from Gemini API.")

        return textResult.trim()
    }

    private fun callOpenAiCompatible(
        baseUrl: String,
        modelName: String,
        apiKey: String,
        systemText: String,
        history: List<AiChatMessage>,
        contextualPrompt: String
    ): String {
        val messagesJson = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemText)
            })
            history.takeLast(6).forEach { msg ->
                put(JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.text)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", contextualPrompt)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", modelName.trim())
            put("messages", messagesJson)
            put("stream", false)
        }.toString()

        val cleanBase = baseUrl.trimEnd('/')
        val endpoint = if (cleanBase.endsWith("/chat/completions")) cleanBase else "$cleanBase/chat/completions"

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        if (cleanBase.contains("openrouter.ai", ignoreCase = true)) {
            reqBuilder.addHeader("HTTP-Referer", "https://nexaide.app")
            reqBuilder.addHeader("X-Title", "Nexa IDE")
        }

        val response = httpClient.newCall(reqBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw ApiHttpException(
                response.code, responseBody,
                AiFailoverPolicy.parseRetryAfter(response.header("Retry-After")),
                "API Error (${response.code}): ${extractErrorMessage(responseBody)}"
            )
        }

        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
        val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        if (content.isNullOrBlank()) {
            throw IllegalStateException("Received empty completion from provider.")
        }
        return content.trim()
    }

    private fun callAnthropic(
        baseUrl: String,
        modelName: String,
        apiKey: String,
        systemText: String,
        history: List<AiChatMessage>,
        contextualPrompt: String
    ): String {
        val messagesJson = JSONArray().apply {
            history.takeLast(6).forEach { msg ->
                put(JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.text)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", contextualPrompt)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", modelName.trim())
            put("max_tokens", 8192)
            put("system", systemText)
            put("messages", messagesJson)
        }.toString()

        val cleanBase = baseUrl.trimEnd('/')
        val endpoint = if (cleanBase.endsWith("/messages")) cleanBase else "$cleanBase/messages"

        val request = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw ApiHttpException(
                response.code, responseBody,
                AiFailoverPolicy.parseRetryAfter(response.header("Retry-After")),
                "Anthropic API Error (${response.code}): ${extractErrorMessage(responseBody)}"
            )
        }

        val root = JSONObject(responseBody)
        val contentArr = root.optJSONArray("content")
        val text = contentArr?.optJSONObject(0)?.optString("text")
        if (text.isNullOrBlank()) {
            throw IllegalStateException("Received empty response from Anthropic API.")
        }
        return text.trim()
    }

    /**
     * Tests connectivity and authentication against the specified provider configuration.
     */
    suspend fun testProviderConnection(config: AiModelConfig, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val cleanBase = config.baseUrl.trim().trimEnd('/')
            if (!cleanBase.startsWith("http://") && !cleanBase.startsWith("https://")) {
                return@withContext Result.failure(IllegalArgumentException("Base URL must start with http:// or https://"))
            }

            when (AiProviderType.fromLabel(config.providerType)) {
                AiProviderType.GOOGLE_GEMINI -> {
                    val url = "$cleanBase/models/${config.modelName.trim()}?key=${apiKey.trim()}"
                    val req = Request.Builder().url(url).get().build()
                    val resp = httpClient.newCall(req).execute()
                    val latency = System.currentTimeMillis() - start
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        Result.success("Connected to Gemini (${latency}ms)")
                    } else {
                        Result.failure(IllegalStateException("HTTP ${resp.code}: ${extractErrorMessage(body)}"))
                    }
                }
                AiProviderType.ANTHROPIC -> {
                    val bodyJson = JSONObject().apply {
                        put("model", config.modelName.trim())
                        put("max_tokens", 8)
                        put("messages", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("content", "ping")
                        }))
                    }.toString()
                    val endpoint = if (cleanBase.endsWith("/messages")) cleanBase else "$cleanBase/messages"
                    val req = Request.Builder()
                        .url(endpoint)
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .addHeader("x-api-key", apiKey.trim())
                        .addHeader("anthropic-version", "2023-06-01")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val latency = System.currentTimeMillis() - start
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        Result.success("Connected to Anthropic (${latency}ms)")
                    } else {
                        Result.failure(IllegalStateException("HTTP ${resp.code}: ${extractErrorMessage(body)}"))
                    }
                }
                AiProviderType.OPENAI_COMPATIBLE,
                AiProviderType.OLLAMA -> {
                    val endpoint = "$cleanBase/models"
                    val reqBuilder = Request.Builder().url(endpoint).get()
                    if (apiKey.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
                    }
                    val resp = httpClient.newCall(reqBuilder.build()).execute()
                    val latency = System.currentTimeMillis() - start
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        Result.success("Endpoint reachable (${latency}ms)")
                    } else {
                        Result.failure(IllegalStateException("HTTP ${resp.code}: ${extractErrorMessage(body)}"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractErrorMessage(body: String): String {
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            err?.optString("message")?.takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: body.take(180)
        } catch (e: Exception) {
            body.take(180)
        }
    }

    suspend fun explainCode(code: String, fileName: String): String {
        return sendMessage(
            prompt = "Please explain the purpose and functionality of this code clearly in bullet points with high-level architecture insights:\n\n```\n$code\n```",
            currentFileName = fileName,
            currentFileContent = code
        )
    }

    suspend fun fixErrors(code: String, errorMessage: String?, fileName: String): AiCodeProposal {
        val prompt = buildString {
            append("Please fix the following code and resolve any errors. ")
            if (!errorMessage.isNullOrBlank()) {
                append("Error description:\n$errorMessage\n\n")
            }
            append("Provide the complete, corrected code in a markdown ``` code block, followed by a brief 2-3 bullet explanation of what was changed.")
        }

        val response = sendMessage(prompt = prompt, currentFileName = fileName, currentFileContent = code)
        val extractedCode = extractCodeBlock(response).ifBlank { code }
        val summary = extractExplanation(response).ifBlank { "Fixed syntax and potential bugs in $fileName" }

        return AiCodeProposal(
            originalCode = code,
            proposedCode = extractedCode,
            summary = summary,
            targetFileName = fileName
        )
    }

    suspend fun refactorCode(code: String, instruction: String, fileName: String): AiCodeProposal {
        val prompt = "Refactor this code according to modern clean architecture and best practices ($instruction). " +
            "Output the complete refactored code in a markdown ``` code block followed by a summary of changes."

        val response = sendMessage(prompt = prompt, currentFileName = fileName, currentFileContent = code)
        val extractedCode = extractCodeBlock(response).ifBlank { code }
        val summary = extractExplanation(response).ifBlank { "Refactored code for cleaner structure and performance." }

        return AiCodeProposal(
            originalCode = code,
            proposedCode = extractedCode,
            summary = summary,
            targetFileName = fileName
        )
    }

    suspend fun codeReview(code: String, fileName: String): String {
        return sendMessage(
            prompt = "Conduct a thorough code review of this file. Identify potential memory leaks, security issues, edge cases, and performance improvements.",
            currentFileName = fileName,
            currentFileContent = code
        )
    }

    /**
     * Computes line-by-line diff between original and proposed code
     */
    fun computeDiff(original: String, proposed: String): List<DiffLine> {
        val oldLines = original.lines()
        val newLines = proposed.lines()
        val diffList = mutableListOf<DiffLine>()

        var oldIdx = 0
        var newIdx = 0

        while (oldIdx < oldLines.size || newIdx < newLines.size) {
            val oldLine = oldLines.getOrNull(oldIdx)
            val newLine = newLines.getOrNull(newIdx)

            if (oldLine == newLine && oldLine != null) {
                diffList.add(DiffLine(DiffLineType.UNCHANGED, oldLine, oldIdx + 1, newIdx + 1))
                oldIdx++
                newIdx++
            } else if (oldLine != null && (newLine == null || !newLines.contains(oldLine))) {
                diffList.add(DiffLine(DiffLineType.REMOVED, oldLine, oldIdx + 1, null))
                oldIdx++
            } else if (newLine != null) {
                diffList.add(DiffLine(DiffLineType.ADDED, newLine, null, newIdx + 1))
                newIdx++
            } else {
                break
            }
        }
        return diffList
    }

    private fun extractCodeBlock(markdown: String): String {
        val regex = Regex("```(?:[a-zA-Z0-9_-]+)?\\s*\\n([\\s\\S]*?)\\n```")
        val match = regex.find(markdown)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractExplanation(markdown: String): String {
        val withoutBlocks = markdown.replace(Regex("```[\\s\\S]*?```"), "").trim()
        return withoutBlocks.lines().filter { it.isNotBlank() }.take(5).joinToString("\n")
    }
}
