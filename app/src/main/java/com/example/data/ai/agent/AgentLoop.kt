package com.example.data.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** One model round-trip. Failover to backup models happens INSIDE this call, before any tool runs. */
fun interface AgentTransport {
    suspend fun complete(system: String, messages: List<AgentMessage>, tools: List<ToolSpec>): ModelTurn
}

data class AgentConfig(
    val maxSteps: Int = 30,
    val repeatLimit: Int = 3,
    val maxContinuations: Int = 3,
    val maxVerifyRounds: Int = 3,
    val historyBudgetChars: Int = AgentContext.DEFAULT_BUDGET_CHARS,
    val allowedTools: Set<String>? = null // null = all (Fix mode restricts this in Step 4)
)

/**
 * Native tool-calling agent loop: model asks for tools -> app runs them -> results go back -> repeat.
 * Guarantees: step cap, identical-call detection, Stop via coroutine cancellation, no silent drops of
 * truncated/malformed output, a tool call id is never executed twice.
 */
class AgentLoop(
    private val transport: AgentTransport,
    private val executor: ProjectToolExecutor,
    private val config: AgentConfig = AgentConfig(),
    private val events: AgentEvents = object : AgentEvents {}
) {
    private val executedIds = HashMap<String, ToolResult>()
    private val callCounts = HashMap<String, Int>()
    private val notices = mutableListOf<String>()
    private var failedCalls = 0

    suspend fun run(
        session: AgentSession,
        userInput: String,
        systemProvider: () -> String,
        buildToolsAvailable: Boolean
    ): AgentRunResult = try {
        runInternal(session, userInput, systemProvider, buildToolsAvailable)
    } catch (e: CancellationException) {
        repairAfterCancel(session)
        throw e
    }

    /** After Stop: every tool call in the transcript must have a result, or the next request is rejected by providers. */
    private fun repairAfterCancel(session: AgentSession) {
        val last = session.messages.lastOrNull()
        if (last is AgentMessage.Assistant && last.toolCalls.isNotEmpty()) {
            session.messages.add(AgentMessage.ToolResults(last.toolCalls.map { c ->
                executedIds[c.id] ?: ToolResult(c.id, c.name, "ERROR: cancelled by the user before this tool ran.", true)
            }))
        }
    }

    private suspend fun runInternal(
        session: AgentSession,
        userInput: String,
        systemProvider: () -> String,
        buildToolsAvailable: Boolean
    ): AgentRunResult {
        val pending = session.pendingAsk
        if (pending != null) {
            session.messages.add(AgentMessage.ToolResults(listOf(ToolResult(pending.callId, AgentToolSpecs.ASK_USER, "User answered: $userInput"))))
            session.pendingAsk = null
        } else {
            session.messages.add(AgentMessage.User(userInput))
        }

        val allTools = AgentToolSpecs.all(buildToolsAvailable).let { all ->
            config.allowedTools?.let { allowed -> all.filter { it.name in allowed } } ?: all
        }
        var steps = 0
        var continuations = 0
        var verifyRounds = 0
        var finalText = ""

        while (true) {
            coroutineContext.ensureActive()
            if (steps >= config.maxSteps) {
                return finish(finalText.ifBlank { "Stopped after ${config.maxSteps} steps to avoid an endless loop." }, AgentStopCause.MAX_STEPS, steps)
            }
            steps++
            events.onModelThinking(steps)

            val turn = try {
                transport.complete(systemProvider(), AgentContext.trim(session.messages, config.historyBudgetChars), allTools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return finish("Model request failed: ${e.message ?: e.javaClass.simpleName}", AgentStopCause.MODEL_ERROR, steps)
            }
            turn.failoverNotice?.let { notices.add(it); events.onNotice(it) }
            if (turn.text.isNotBlank()) events.onAssistantText(turn.text)

            session.messages.add(AgentMessage.Assistant(turn.text, turn.toolCalls))

            // ---- Output cut off (finish_reason = length): never drop it silently. ----
            if (turn.stopReason == StopReason.LENGTH) {
                val hasBroken = turn.toolCalls.any { it.argsError != null }
                if (turn.toolCalls.isEmpty() || hasBroken) {
                    continuations++
                    val note = "⚠️ Model output was cut off (length limit); continuing automatically ($continuations/${config.maxContinuations})."
                    notices.add(note); events.onNotice(note)
                    if (turn.text.isNotBlank()) finalText = turn.text
                    if (continuations > config.maxContinuations) {
                        return finish("The model kept hitting its output limit. Try a smaller request or a model with a larger output limit.", AgentStopCause.MODEL_ERROR, steps)
                    }
                    if (turn.toolCalls.isEmpty()) {
                        session.messages.add(AgentMessage.User("Your last reply was cut off by the length limit. Continue exactly where you stopped, and write large files in separate smaller tool calls."))
                        continue
                    }
                    // broken tool calls fall through: they receive explicit error results below
                }
            }

            if (turn.toolCalls.isEmpty()) {
                finalText = turn.text.ifBlank { finalText }
                // ---- Post-write verification (max N rounds) ----
                if (executor.touched.isNotEmpty() && verifyRounds < config.maxVerifyRounds) {
                    val problems = executor.collectProblems()
                    if (problems.isNotEmpty()) {
                        verifyRounds++
                        val msg = "Automatic check (round $verifyRounds/${config.maxVerifyRounds}) found problems:\n" +
                            ProjectToolExecutor.formatProblems(problems) + "\nFix them with edit_file, then reply with a short summary."
                        events.onNotice("🔍 যাচাই: ${problems.size}টি সমস্যা পাওয়া গেছে, ঠিক করা হচ্ছে / Verifying: ${problems.size} problem(s), fixing")
                        session.messages.add(AgentMessage.User(msg))
                        continue
                    }
                }
                val remaining = if (executor.touched.isNotEmpty()) executor.collectProblems() else emptyList()
                return finish(finalText, AgentStopCause.COMPLETED, steps, remaining)
            }

            // ---- Execute tool calls ----
            val results = mutableListOf<ToolResult>()
            var askRequest: AskUserRequest? = null
            for (call in turn.toolCalls) {
                coroutineContext.ensureActive()
                if (config.allowedTools != null && call.name !in config.allowedTools) {
                    results.add(ToolResult(call.id, call.name, "ERROR: tool '${call.name}' is not available in this mode.", true)); failedCalls++
                    continue
                }
                val cached = executedIds[call.id]
                if (cached != null) { results.add(cached); continue } // never run the same call twice

                if (call.name == AgentToolSpecs.ASK_USER && call.argsError == null) {
                    val req = parseAsk(call)
                    if (req == null) {
                        results.add(ToolResult(call.id, call.name, "ERROR: ask_user needs a non-empty 'questions' array with options.", true)); failedCalls++
                    } else if (askRequest != null) {
                        results.add(ToolResult(call.id, call.name, "ERROR: only one ask_user call per turn.", true)); failedCalls++
                    } else {
                        askRequest = req
                        results.add(ToolResult(call.id, call.name, "Waiting for the user's answers.", false))
                    }
                    continue
                }

                val signature = call.name + call.args.toString()
                val seen = (callCounts[signature] ?: 0) + 1
                callCounts[signature] = seen
                if (seen >= config.repeatLimit) {
                    val msg = "The same call to ${call.name} was made $seen times without progress. Stopped to avoid a loop."
                    session.messages.removeAt(session.messages.lastIndex) // drop assistant msg whose calls we won't answer
                    return finish(msg, AgentStopCause.REPEATED_CALL, steps)
                }

                events.onToolStarted(call)
                val result = executor.execute(call)
                executedIds[call.id] = result
                if (result.isError) failedCalls++
                events.onToolFinished(call, result)
                results.add(result)
            }
            session.messages.add(AgentMessage.ToolResults(results))

            if (askRequest != null) {
                // ask_user pairs its result with the user's next message (see top of run()).
                session.messages.removeAt(session.messages.lastIndex)
                val others = results.filter { it.callId != askRequest!!.callId }
                if (others.isNotEmpty()) session.messages.add(AgentMessage.ToolResults(others))
                session.pendingAsk = askRequest
                return finish(turn.text.ifBlank { "" }, AgentStopCause.WAITING_FOR_USER, steps, pending = askRequest)
            }
        }
    }

    private fun parseAsk(call: ToolCall): AskUserRequest? {
        val arr = call.args.optJSONArray("questions") ?: return null
        val qs = mutableListOf<AskQuestion>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("question").trim()
            val optsArr = o.optJSONArray("options")
            val opts = (0 until (optsArr?.length() ?: 0)).mapNotNull { optsArr!!.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            if (text.isNotEmpty()) qs.add(AskQuestion(o.optString("id", "q$i"), text, opts, o.optBoolean("multi_select", false)))
        }
        return if (qs.isEmpty()) null else AskUserRequest(call.id, qs.take(6))
    }

    private fun finish(text: String, cause: AgentStopCause, steps: Int, problems: List<Problem> = emptyList(), pending: AskUserRequest? = null) =
        AgentRunResult(
            finalText = text, cause = cause, steps = steps,
            filesWritten = executor.writtenFiles.toList(), filesEdited = executor.editedFiles.toList(),
            filesDeleted = executor.deletedFiles.toList(), failedToolCalls = failedCalls,
            pendingQuestion = pending, notices = notices.toList(), remainingProblems = problems
        )
}
