package com.example.data.ai.agent

import org.json.JSONObject

/** Provider-neutral conversation model used by the tool-calling agent loop. */
sealed class AgentMessage {
    data class User(val text: String) : AgentMessage()
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList()) : AgentMessage()
    data class ToolResults(val results: List<ToolResult>) : AgentMessage()
}

data class ToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
    /** Non-null when the provider returned arguments that could not be parsed (e.g. cut off by max_tokens). */
    val argsError: String? = null,
    /** Opaque provider data that must be echoed back (Gemini thoughtSignature). */
    val signature: String? = null
)

data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false
)

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON-schema object: {"type":"object","properties":{...},"required":[...]} (Gemini-compatible subset). */
    val parameters: JSONObject
)

enum class StopReason { END, TOOL_USE, LENGTH, OTHER }

data class ModelTurn(
    val text: String,
    val toolCalls: List<ToolCall>,
    val stopReason: StopReason,
    val modelLabel: String? = null,
    val failoverNotice: String? = null
)

/** A question the agent wants the user to answer via tappable cards (rendered fully in Step 2/3). */
data class AskQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val multiSelect: Boolean = false
)

data class AskUserRequest(val callId: String, val questions: List<AskQuestion>) {
    /** Plain-text rendering used until the card UI exists. */
    fun toDisplayText(): String = buildString {
        questions.forEachIndexed { i, q ->
            appendLine("${i + 1}. ${q.question}")
            q.options.forEach { appendLine("   • $it") }
        }
        append("(নিজে লিখুন বা \"তুমিই ঠিক করো\" বলুন / Answer in your own words, or say \"you decide\")")
    }.trim()
}

/** Normalised static-analysis finding. Same shape the Problems panel (Step 4) will consume. */
data class Problem(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val severity: Severity = Severity.ERROR,
    val source: String = "nexa-lint"
) {
    enum class Severity { ERROR, WARNING }
}

enum class AgentStopCause {
    COMPLETED, MAX_STEPS, REPEATED_CALL, CANCELLED, WAITING_FOR_USER, MODEL_ERROR
}

data class AgentRunResult(
    val finalText: String,
    val cause: AgentStopCause,
    val steps: Int,
    val filesWritten: List<String>,
    val filesEdited: List<String>,
    val filesDeleted: List<String>,
    val failedToolCalls: Int,
    val pendingQuestion: AskUserRequest? = null,
    val notices: List<String> = emptyList(),
    val remainingProblems: List<Problem> = emptyList()
) {
    val changedFileCount: Int get() = (filesWritten + filesEdited + filesDeleted).toSet().size
}

/** Live progress callbacks (UI timeline in Step 3). All callbacks are optional. */
interface AgentEvents {
    fun onModelThinking(step: Int) {}
    fun onAssistantText(text: String) {}
    fun onToolStarted(call: ToolCall) {}
    fun onToolFinished(call: ToolCall, result: ToolResult) {}
    fun onNotice(message: String) {}
}

/** Conversation state kept by the UI between user messages. */
class AgentSession {
    val messages: MutableList<AgentMessage> = mutableListOf()
    /** Set when the last run ended on ask_user; the next user input answers that call. */
    var pendingAsk: AskUserRequest? = null
}
