package com.example.data.ai.agent

import org.json.JSONArray
import org.json.JSONObject

/** An HTTP request ready to send; secrets only ever live in [headers]. */
data class ProviderRequest(val url: String, val headers: Map<String, String>, val body: String)

/** Translates the neutral conversation model to/from one provider's native function-calling wire format. */
interface AgentProtocol {
    fun buildRequest(
        baseUrl: String, model: String, apiKey: String,
        system: String, messages: List<AgentMessage>, tools: List<ToolSpec>, maxOutputTokens: Int
    ): ProviderRequest

    fun parseResponse(body: String): ModelTurn

    companion object {
        fun parseArgs(raw: String?): Pair<JSONObject, String?> {
            if (raw.isNullOrBlank()) return JSONObject() to null
            return try { JSONObject(raw) to null } catch (e: Exception) { JSONObject() to (e.message ?: "invalid JSON") }
        }
    }
}

object OpenAiProtocol : AgentProtocol {
    override fun buildRequest(baseUrl: String, model: String, apiKey: String, system: String,
                              messages: List<AgentMessage>, tools: List<ToolSpec>, maxOutputTokens: Int): ProviderRequest {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in messages) when (m) {
            is AgentMessage.User -> msgs.put(JSONObject().put("role", "user").put("content", m.text))
            is AgentMessage.Assistant -> {
                val o = JSONObject().put("role", "assistant")
                o.put("content", if (m.text.isBlank()) JSONObject.NULL else m.text)
                if (m.toolCalls.isNotEmpty()) {
                    o.put("tool_calls", JSONArray().apply {
                        m.toolCalls.forEach { c ->
                            put(JSONObject().put("id", c.id).put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString())))
                        }
                    })
                }
                msgs.put(o)
            }
            is AgentMessage.ToolResults -> m.results.forEach { r ->
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", r.callId).put("content", r.content))
            }
        }
        val body = JSONObject().put("model", model.trim()).put("messages", msgs).put("stream", false).put("max_tokens", maxOutputTokens)
        if (tools.isNotEmpty()) {
            body.put("tools", JSONArray().apply {
                tools.forEach { t ->
                    put(JSONObject().put("type", "function").put("function",
                        JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters)))
                }
            })
            body.put("tool_choice", "auto")
        }
        val clean = baseUrl.trimEnd('/')
        val url = if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
        val headers = linkedMapOf("Content-Type" to "application/json")
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        if (clean.contains("openrouter.ai", true)) { headers["HTTP-Referer"] = "https://nexaide.app"; headers["X-Title"] = "Nexa IDE" }
        return ProviderRequest(url, headers, body.toString())
    }

    override fun parseResponse(body: String): ModelTurn {
        val root = JSONObject(body)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("Provider returned no choices.")
        val message = choice.optJSONObject("message") ?: JSONObject()
        val text = message.optString("content", "").let { if (it == "null") "" else it }
        val calls = mutableListOf<ToolCall>()
        val arr = message.optJSONArray("tool_calls")
        if (arr != null) for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val fn = c.optJSONObject("function") ?: continue
            val (args, error) = AgentProtocol.parseArgs(fn.optString("arguments", ""))
            calls.add(ToolCall(c.optString("id").ifBlank { "call_${i}_${System.nanoTime()}" }, fn.optString("name"), args, error))
        }
        val reason = when (choice.optString("finish_reason")) {
            "length" -> StopReason.LENGTH
            "tool_calls", "function_call" -> StopReason.TOOL_USE
            "stop" -> if (calls.isNotEmpty()) StopReason.TOOL_USE else StopReason.END
            else -> if (calls.isNotEmpty()) StopReason.TOOL_USE else StopReason.OTHER
        }
        return ModelTurn(text.trim(), calls, reason)
    }
}

object AnthropicProtocol : AgentProtocol {
    override fun buildRequest(baseUrl: String, model: String, apiKey: String, system: String,
                              messages: List<AgentMessage>, tools: List<ToolSpec>, maxOutputTokens: Int): ProviderRequest {
        // Anthropic requires alternating roles: merge neighbours with the same role.
        data class Entry(val role: String, val blocks: JSONArray)
        val entries = mutableListOf<Entry>()
        fun add(role: String, block: JSONObject) {
            if (entries.isNotEmpty() && entries.last().role == role) entries.last().blocks.put(block)
            else entries.add(Entry(role, JSONArray().put(block)))
        }
        for (m in messages) when (m) {
            is AgentMessage.User -> add("user", JSONObject().put("type", "text").put("text", m.text))
            is AgentMessage.Assistant -> {
                if (m.text.isNotBlank()) add("assistant", JSONObject().put("type", "text").put("text", m.text))
                m.toolCalls.forEach { c ->
                    add("assistant", JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.args))
                }
                if (m.text.isBlank() && m.toolCalls.isEmpty()) add("assistant", JSONObject().put("type", "text").put("text", "…"))
            }
            is AgentMessage.ToolResults -> m.results.forEach { r ->
                add("user", JSONObject().put("type", "tool_result").put("tool_use_id", r.callId)
                    .put("content", r.content).put("is_error", r.isError))
            }
        }
        val msgs = JSONArray().apply { entries.forEach { put(JSONObject().put("role", it.role).put("content", it.blocks)) } }
        val body = JSONObject().put("model", model.trim()).put("max_tokens", maxOutputTokens)
            .put("system", system).put("messages", msgs)
        if (tools.isNotEmpty()) {
            body.put("tools", JSONArray().apply {
                tools.forEach { t -> put(JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.parameters)) }
            })
        }
        val clean = baseUrl.trimEnd('/')
        val url = if (clean.endsWith("/messages")) clean else "$clean/messages"
        return ProviderRequest(url, linkedMapOf(
            "x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "Content-Type" to "application/json"
        ), body.toString())
    }

    override fun parseResponse(body: String): ModelTurn {
        val root = JSONObject(body)
        val content = root.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> calls.add(ToolCall(b.optString("id"), b.optString("name"), b.optJSONObject("input") ?: JSONObject()))
            }
        }
        val reason = when (root.optString("stop_reason")) {
            "max_tokens" -> StopReason.LENGTH
            "tool_use" -> StopReason.TOOL_USE
            "end_turn", "stop_sequence" -> if (calls.isNotEmpty()) StopReason.TOOL_USE else StopReason.END
            else -> if (calls.isNotEmpty()) StopReason.TOOL_USE else StopReason.OTHER
        }
        // A tool_use cut off by max_tokens arrives with truncated/empty input: flag it so the model retries smaller.
        val fixed = if (reason == StopReason.LENGTH) calls.map { c ->
            if (c.args.length() == 0) c.copy(argsError = "output cut off (max_tokens)") else c
        } else calls
        return ModelTurn(text.toString().trim(), fixed, reason)
    }
}

object GeminiProtocol : AgentProtocol {
    override fun buildRequest(baseUrl: String, model: String, apiKey: String, system: String,
                              messages: List<AgentMessage>, tools: List<ToolSpec>, maxOutputTokens: Int): ProviderRequest {
        data class Entry(val role: String, val parts: JSONArray)
        val entries = mutableListOf<Entry>()
        fun add(role: String, part: JSONObject) {
            if (entries.isNotEmpty() && entries.last().role == role) entries.last().parts.put(part)
            else entries.add(Entry(role, JSONArray().put(part)))
        }
        for (m in messages) when (m) {
            is AgentMessage.User -> add("user", JSONObject().put("text", m.text))
            is AgentMessage.Assistant -> {
                if (m.text.isNotBlank()) add("model", JSONObject().put("text", m.text))
                m.toolCalls.forEach { c ->
                    val part = JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args))
                    if (!c.signature.isNullOrBlank()) part.put("thoughtSignature", c.signature)
                    add("model", part)
                }
                if (m.text.isBlank() && m.toolCalls.isEmpty()) add("model", JSONObject().put("text", "…"))
            }
            is AgentMessage.ToolResults -> m.results.forEach { r ->
                val key = if (r.isError) "error" else "result"
                add("user", JSONObject().put("functionResponse",
                    JSONObject().put("name", r.name).put("response", JSONObject().put(key, r.content))))
            }
        }
        val contents = JSONArray().apply { entries.forEach { put(JSONObject().put("role", it.role).put("parts", it.parts)) } }
        val body = JSONObject()
            .put("contents", contents)
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxOutputTokens))
        if (tools.isNotEmpty()) {
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
                tools.forEach { t -> put(JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters)) }
            })))
        }
        val clean = baseUrl.trimEnd('/')
        val name = model.trim().ifBlank { "gemini-2.5-flash" }
        // API key travels in a header, never in the URL, so it cannot leak into logs or error messages.
        return ProviderRequest("$clean/models/$name:generateContent",
            linkedMapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"), body.toString())
    }

    override fun parseResponse(body: String): ModelTurn {
        val root = JSONObject(body)
        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
        if (cand == null) {
            val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw IllegalStateException(if (block.isNotBlank()) "Gemini blocked the request ($block)." else "Gemini returned no candidates.")
        }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.has("text") && !p.optBoolean("thought", false)) text.append(p.optString("text"))
            val fc = p.optJSONObject("functionCall")
            if (fc != null) {
                calls.add(ToolCall("gem_${System.nanoTime()}_$i", fc.optString("name"), fc.optJSONObject("args") ?: JSONObject(),
                    signature = p.optString("thoughtSignature").takeIf { it.isNotBlank() }))
            }
        }
        val reason = when (cand.optString("finishReason")) {
            "MAX_TOKENS" -> StopReason.LENGTH
            "STOP", "" -> if (calls.isNotEmpty()) StopReason.TOOL_USE else StopReason.END
            else -> if (calls.isNotEmpty()) StopReason.TOOL_USE else StopReason.OTHER
        }
        return ModelTurn(text.toString().trim(), calls, reason)
    }
}
