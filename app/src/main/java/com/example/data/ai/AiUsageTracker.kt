package com.example.data.ai

import java.util.concurrent.ConcurrentHashMap

/** Per-model counters. Contains NO prompt/response content - only numbers. Tokens are estimates (chars/4). */
data class AiModelUsage(
    val modelId: String,
    val requests: Long = 0,
    val failures: Long = 0,
    val failoversFrom: Long = 0,
    val estimatedTokens: Long = 0
)

class AiUsageTracker : AiRunObserver {
    private val map = ConcurrentHashMap<String, AiModelUsage>()

    private fun update(id: String, f: (AiModelUsage) -> AiModelUsage) {
        map.compute(id) { _, cur -> f(cur ?: AiModelUsage(id)) }
    }

    override fun onAttempt(modelId: String) = update(modelId) { it.copy(requests = it.requests + 1) }
    override fun onFailure(modelId: String, kind: FailureKind) = update(modelId) { it.copy(failures = it.failures + 1) }
    override fun onShift(shift: FailoverShift) = update(shift.fromId) { it.copy(failoversFrom = it.failoversFrom + 1) }

    fun addEstimatedTokens(modelId: String, promptChars: Int, responseChars: Int) {
        val tokens = ((promptChars.coerceAtLeast(0) + responseChars.coerceAtLeast(0)) / 4).toLong()
        update(modelId) { it.copy(estimatedTokens = it.estimatedTokens + tokens) }
    }

    fun snapshot(): List<AiModelUsage> = map.values.sortedByDescending { it.requests }
    fun restore(items: List<AiModelUsage>) { map.clear(); items.forEach { map[it.modelId] = it } }
    fun reset() = map.clear()

    /** "id|requests|failures|failovers|tokens" per line - simple, content-free persistence format. */
    fun serialize(): String = snapshot().joinToString("\n") {
        listOf(it.modelId, it.requests, it.failures, it.failoversFrom, it.estimatedTokens).joinToString("|")
    }

    companion object {
        fun parse(raw: String?): List<AiModelUsage> = raw.orEmpty().lines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 5 || p[0].isBlank()) return@mapNotNull null
            val n = p.drop(1).map { it.toLongOrNull() ?: return@mapNotNull null }
            AiModelUsage(p[0], n[0], n[1], n[2], n[3])
        }
    }
}

/** Process-wide AI state shared by every GeminiAiService instance. */
object AiRuntime {
    val cooldowns = ModelCooldownTracker()
    val usage = AiUsageTracker()
}
