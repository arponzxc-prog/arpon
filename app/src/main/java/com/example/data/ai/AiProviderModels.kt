package com.example.data.ai

import com.squareup.moshi.JsonClass
import java.util.UUID

enum class AiProviderType(val label: String) {
    OPENAI_COMPATIBLE("OpenAI / Compatible"),
    GOOGLE_GEMINI("Google Gemini"),
    ANTHROPIC("Anthropic Claude"),
    OLLAMA("Ollama (Local)");

    companion object {
        fun fromLabel(label: String): AiProviderType {
            return entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
                ?: OPENAI_COMPATIBLE
        }
    }
}

@JsonClass(generateAdapter = true)
data class AiModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val providerType: String = AiProviderType.OPENAI_COMPATIBLE.label,
    val baseUrl: String,
    val modelName: String,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val streamingResponses: Boolean = true,
    val requiresApiKey: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class PopularAiProvider(
    val id: String,
    val name: String,
    val description: String,
    val providerType: String,
    val defaultBaseUrl: String,
    val defaultModelName: String,
    val requiresApiKey: Boolean = true,
    val defaultSupportsTools: Boolean = true,
    val defaultSupportsVision: Boolean = false,
    val defaultStreaming: Boolean = true
)

object PopularProvidersCatalog {
    val providers: List<PopularAiProvider> = listOf(
        PopularAiProvider(
            id = "openrouter",
            name = "OpenRouter",
            description = "OpenRouter API (OpenAI, Anthropic, Gemini, etc.)",
            providerType = AiProviderType.OPENAI_COMPATIBLE.label,
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            defaultModelName = "openai/gpt-4o-mini",
            requiresApiKey = true,
            defaultSupportsTools = true,
            defaultSupportsVision = true,
            defaultStreaming = true
        ),
        PopularAiProvider(
            id = "apiqik",
            name = "APIQIK",
            description = "APIQIK High-Speed AI API Gateway",
            providerType = AiProviderType.OPENAI_COMPATIBLE.label,
            defaultBaseUrl = "https://api.apiqik.com/v1",
            defaultModelName = "gpt-4o",
            requiresApiKey = true,
            defaultSupportsTools = true,
            defaultSupportsVision = true,
            defaultStreaming = true
        ),
        PopularAiProvider(
            id = "wusrouter",
            name = "WusRouter",
            description = "WusRouter Free & Fast AI Gateway",
            providerType = AiProviderType.OPENAI_COMPATIBLE.label,
            defaultBaseUrl = "https://api.wusrouter.com/v1",
            defaultModelName = "gpt-4o-mini",
            requiresApiKey = true,
            defaultSupportsTools = true,
            defaultSupportsVision = false,
            defaultStreaming = true
        ),
        PopularAiProvider(
            id = "xingchi",
            name = "Xingchi",
            description = "Xingchi AI Provider",
            providerType = AiProviderType.OPENAI_COMPATIBLE.label,
            defaultBaseUrl = "https://api.xingchi.ai/v1",
            defaultModelName = "gpt-4o-mini",
            requiresApiKey = true,
            defaultSupportsTools = true,
            defaultSupportsVision = false,
            defaultStreaming = true
        ),
        PopularAiProvider(
            id = "ollama",
            name = "Ollama",
            description = "Local Ollama AI",
            providerType = AiProviderType.OLLAMA.label,
            defaultBaseUrl = "http://127.0.0.1:11434/v1",
            defaultModelName = "llama3.2",
            requiresApiKey = false,
            defaultSupportsTools = true,
            defaultSupportsVision = false,
            defaultStreaming = true
        ),
        PopularAiProvider(
            id = "lm_studio",
            name = "LM Studio",
            description = "Local LM Studio",
            providerType = AiProviderType.OPENAI_COMPATIBLE.label,
            defaultBaseUrl = "http://127.0.0.1:1234/v1",
            defaultModelName = "local-model",
            requiresApiKey = false,
            defaultSupportsTools = true,
            defaultSupportsVision = false,
            defaultStreaming = true
        ),
        PopularAiProvider(
            id = "google_gemini",
            name = "Google Gemini",
            description = "Google AI Studio Gemini API",
            providerType = AiProviderType.GOOGLE_GEMINI.label,
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModelName = "gemini-2.5-flash",
            requiresApiKey = true,
            defaultSupportsTools = true,
            defaultSupportsVision = true,
            defaultStreaming = true
        ),
        PopularAiProvider(
            id = "openai",
            name = "OpenAI",
            description = "Official OpenAI API (GPT-4o, o3-mini, etc.)",
            providerType = AiProviderType.OPENAI_COMPATIBLE.label,
            defaultBaseUrl = "https://api.openai.com/v1",
            defaultModelName = "gpt-4o",
            requiresApiKey = true,
            defaultSupportsTools = true,
            defaultSupportsVision = true,
            defaultStreaming = true
        ),
        PopularAiProvider(
            id = "anthropic",
            name = "Anthropic Claude",
            description = "Official Anthropic Messages API (Claude 3.5/3.7)",
            providerType = AiProviderType.ANTHROPIC.label,
            defaultBaseUrl = "https://api.anthropic.com/v1",
            defaultModelName = "claude-3-5-sonnet-20241022",
            requiresApiKey = true,
            defaultSupportsTools = true,
            defaultSupportsVision = true,
            defaultStreaming = true
        )
    )
}
