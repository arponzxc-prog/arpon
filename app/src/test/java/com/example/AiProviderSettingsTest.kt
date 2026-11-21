package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiModelConfig
import com.example.data.ai.AiProviderType
import com.example.data.ai.GeminiAiService
import com.example.data.ai.PopularProvidersCatalog
import com.example.data.security.SecureTokenManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiProviderSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecureTokenManager.getAiModelConfigs(context).forEach {
            SecureTokenManager.deleteAiModelConfig(context, it.id)
        }
        SecureTokenManager.clearGeminiApiKey(context)
    }

    @Test
    fun `popular providers catalog contains requested providers`() {
        val names = PopularProvidersCatalog.providers.map { it.name }
        assertTrue(names.contains("OpenRouter"))
        assertTrue(names.contains("APIQIK"))
        assertTrue(names.contains("WusRouter"))
        assertTrue(names.contains("Xingchi"))
        assertTrue(names.contains("Ollama"))
        assertTrue(names.contains("LM Studio"))
        assertTrue(names.contains("Google Gemini"))
    }

    @Test
    fun `save switch and delete encrypted ai model configs`() {
        val openRouter = AiModelConfig(
            id = "or-1",
            displayName = "My OpenRouter",
            providerType = AiProviderType.OPENAI_COMPATIBLE.label,
            baseUrl = "https://openrouter.ai/api/v1",
            modelName = "openai/gpt-4o",
            supportsTools = true,
            supportsVision = true,
            streamingResponses = true,
            requiresApiKey = true
        )
        val secretKey = "sk-or-v1-supersecretkey9876"
        SecureTokenManager.saveAiModelConfig(context, openRouter, secretKey, setAsActive = true)

        val saved = SecureTokenManager.getAiModelConfigs(context)
        assertEquals(1, saved.size)
        assertEquals("My OpenRouter", saved.first().displayName)
        assertEquals(secretKey, SecureTokenManager.getAiModelApiKey(context, "or-1"))

        val aiService = GeminiAiService(context)
        assertEquals("My OpenRouter • openai/gpt-4o", aiService.getActiveProviderSummary())
        assertEquals(secretKey, aiService.getEffectiveApiKey())

        // Add local Ollama provider and activate it
        val ollama = AiModelConfig(
            id = "ol-1",
            displayName = "Local Ollama",
            providerType = AiProviderType.OLLAMA.label,
            baseUrl = "http://127.0.0.1:11434/v1",
            modelName = "llama3.2",
            requiresApiKey = false
        )
        SecureTokenManager.saveAiModelConfig(context, ollama, "", setAsActive = true)
        assertEquals("ol-1", SecureTokenManager.getActiveAiModelId(context))
        assertEquals("Local Ollama • llama3.2", aiService.getActiveProviderSummary())
        assertEquals("local-no-key-required", aiService.getEffectiveApiKey())

        // Delete active model falls back cleanly to remaining model
        SecureTokenManager.deleteAiModelConfig(context, "ol-1")
        assertEquals("or-1", SecureTokenManager.getActiveAiModelId(context))

        // Delete remaining model clears active state
        SecureTokenManager.deleteAiModelConfig(context, "or-1")
        assertTrue(SecureTokenManager.getAiModelConfigs(context).isEmpty())
        assertNull(SecureTokenManager.getAiModelApiKey(context, "or-1"))
        assertNull(SecureTokenManager.getActiveAiModelId(context))
    }
}
