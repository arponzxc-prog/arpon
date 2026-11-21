package com.example.data.ai.agent

import com.example.data.ai.IdentityResponder

/**
 * Facade used by the UI: owns the session, the executor and the loop for one task.
 * [undo] restores every file changed by the last task (checkpoint taken lazily, copy-on-write).
 */
class NexaAgentRunner(
    private val transport: AgentTransport,
    private val host: AgentProjectHost,
    private val config: AgentConfig = AgentConfig()
) {
    val session = AgentSession()
    private var lastExecutor: ProjectToolExecutor? = null

    suspend fun send(
        prompt: String,
        events: AgentEvents = object : AgentEvents {},
        currentFile: String? = null,
        selectedSnippet: String? = null
    ): AgentRunResult {
        if (session.pendingAsk == null) {
            IdentityResponder.respond(prompt)?.let { reply ->
                return AgentRunResult(reply, AgentStopCause.COMPLETED, 0, emptyList(), emptyList(), emptyList(), 0)
            }
        }
        val executor = ProjectToolExecutor(host)
        lastExecutor = executor
        val loop = AgentLoop(transport, executor, config, events)
        return loop.run(
            session = session,
            userInput = prompt,
            systemProvider = {
                val project = host.currentProject()
                AgentContext.systemPrompt(project, AgentContext.readMemory(project), currentFile, selectedSnippet)
            },
            buildToolsAvailable = host.buildTools != null
        )
    }

    /** One-tap Undo of the last task. Returns restored relative paths. */
    fun undo(): List<String> = lastExecutor?.undoJournal()?.restore().orEmpty()

    fun canUndo(): Boolean = lastExecutor?.undoJournal()?.isEmpty == false

    fun reset() { session.messages.clear(); session.pendingAsk = null; lastExecutor = null }
}
