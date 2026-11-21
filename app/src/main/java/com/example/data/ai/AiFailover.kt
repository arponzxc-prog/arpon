package com.example.data.ai

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException

/** Typed HTTP error from an AI provider. Never parse the message text to decide behaviour. */
class ApiHttpException(
    val code: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
    message: String = "API Error ($code)"
) : IOException(message)

enum class FailureKind { RATE_LIMIT, PAYMENT, AUTH, NOT_FOUND, SERVER, TIMEOUT, OTHER }

data class FailoverShift(val fromId: String, val toId: String, val kind: FailureKind)

data class FailoverResult<T>(val value: T, val usedId: String, val shifts: List<FailoverShift>)

/** Receives events for usage statistics / notices. Never receives prompt or response content. */
interface AiRunObserver {
    fun onAttempt(modelId: String) {}
    fun onSuccess(modelId: String, kind: FailureKind? = null) {}
    fun onFailure(modelId: String, kind: FailureKind) {}
    fun onShift(shift: FailoverShift) {}
}

object AiFailoverPolicy {
    const val RATE_LIMIT_DEFAULT_MS = 60_000L
    const val RATE_LIMIT_MAX_MS = 60 * 60_000L
    const val HARD_FAIL_MS = 10 * 60_000L
    const val SOFT_FAIL_MS = 30_000L

    fun classify(t: Throwable): FailureKind = when {
        t is ApiHttpException -> classifyHttp(t.code)
        t is SocketTimeoutException -> FailureKind.TIMEOUT
        t is java.io.InterruptedIOException -> FailureKind.TIMEOUT
        t is java.net.ConnectException || t is java.net.UnknownHostException -> FailureKind.TIMEOUT
        else -> FailureKind.OTHER
    }

    fun classifyHttp(code: Int): FailureKind = when {
        code == 429 -> FailureKind.RATE_LIMIT
        code == 402 -> FailureKind.PAYMENT
        code == 401 || code == 403 -> FailureKind.AUTH
        code == 404 -> FailureKind.NOT_FOUND
        code == 408 -> FailureKind.TIMEOUT
        code in 500..599 -> FailureKind.SERVER
        else -> FailureKind.OTHER
    }

    /** Only these kinds justify shifting to a backup model. */
    fun shouldFailover(kind: FailureKind): Boolean = kind != FailureKind.OTHER

    fun cooldownMs(kind: FailureKind, retryAfterSeconds: Long? = null): Long = when (kind) {
        FailureKind.RATE_LIMIT ->
            if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                (retryAfterSeconds * 1000L).coerceAtMost(RATE_LIMIT_MAX_MS)
            } else RATE_LIMIT_DEFAULT_MS
        FailureKind.AUTH, FailureKind.PAYMENT, FailureKind.NOT_FOUND -> HARD_FAIL_MS
        FailureKind.SERVER, FailureKind.TIMEOUT -> SOFT_FAIL_MS
        FailureKind.OTHER -> 0L
    }

    /** Parses a numeric Retry-After header (seconds). HTTP-date form is ignored (default cooldown applies). */
    fun parseRetryAfter(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    fun reasonBn(kind: FailureKind): String = when (kind) {
        FailureKind.RATE_LIMIT -> "লিমিট শেষ"
        FailureKind.PAYMENT -> "ক্রেডিট/পেমেন্ট সমস্যা"
        FailureKind.AUTH -> "API Key সমস্যা"
        FailureKind.NOT_FOUND -> "মডেল পাওয়া যায়নি"
        FailureKind.SERVER -> "সার্ভার সমস্যা"
        FailureKind.TIMEOUT -> "টাইমআউট"
        FailureKind.OTHER -> "ত্রুটি"
    }

    fun noticeBn(fromName: String, toName: String, kind: FailureKind): String =
        "ℹ️ $fromName ব্যবহার করা যায়নি (${reasonBn(kind)}), তাই $toName মডেলে শিফট করা হয়েছে।"

    /**
     * Candidate order: primary first, then backups in [backupOrder] (unknown ids ignored), then any
     * remaining configured ids. Models in cooldown are skipped; if all are cooling down the one that
     * recovers soonest is tried first so the user still gets a real error/answer.
     */
    fun orderCandidates(
        primaryId: String,
        allConfiguredIds: List<String>,
        backupOrder: List<String>,
        failoverEnabled: Boolean,
        tracker: ModelCooldownTracker
    ): List<String> {
        if (!failoverEnabled) return listOf(primaryId)
        val ordered = LinkedHashSet<String>()
        ordered.add(primaryId)
        backupOrder.filter { it in allConfiguredIds }.forEach { ordered.add(it) }
        allConfiguredIds.forEach { ordered.add(it) }
        val list = ordered.toList()
        val available = list.filterNot { tracker.isCoolingDown(it) }
        return if (available.isNotEmpty()) available else list.sortedBy { tracker.remainingMs(it) }
    }
}

class ModelCooldownTracker(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val until = ConcurrentHashMap<String, Long>()

    fun markFailed(modelId: String, kind: FailureKind, retryAfterSeconds: Long? = null) {
        val ms = AiFailoverPolicy.cooldownMs(kind, retryAfterSeconds)
        if (ms > 0) until[modelId] = clock() + ms
    }

    fun remainingMs(modelId: String): Long = ((until[modelId] ?: 0L) - clock()).coerceAtLeast(0L)
    fun isCoolingDown(modelId: String): Boolean = remainingMs(modelId) > 0L
    fun clear(modelId: String) { until.remove(modelId) }
    fun clearAll() = until.clear()
}

/**
 * Runs [call] against candidates in order. Fails over only on [AiFailoverPolicy.shouldFailover] kinds.
 * Failover happens strictly inside the single request/response call, BEFORE any agent plan is executed,
 * so no file operation can run twice because of a shift.
 */
class AiFailoverRunner(
    private val tracker: ModelCooldownTracker,
    private val observer: AiRunObserver? = null
) {
    fun <T> run(candidates: List<String>, call: (String) -> T): FailoverResult<T> {
        require(candidates.isNotEmpty()) { "No AI model candidates." }
        val shifts = mutableListOf<FailoverShift>()
        var lastError: Throwable? = null
        for ((index, id) in candidates.withIndex()) {
            observer?.onAttempt(id)
            try {
                val value = call(id)
                tracker.clear(id)
                observer?.onSuccess(id)
                return FailoverResult(value, id, shifts)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val kind = AiFailoverPolicy.classify(t)
                observer?.onFailure(id, kind)
                lastError = t
                if (!AiFailoverPolicy.shouldFailover(kind)) throw t
                tracker.markFailed(id, kind, (t as? ApiHttpException)?.retryAfterSeconds)
                val next = candidates.getOrNull(index + 1) ?: throw t
                val shift = FailoverShift(id, next, kind)
                shifts.add(shift)
                observer?.onShift(shift)
            }
        }
        throw lastError ?: IllegalStateException("AI failover exhausted.")
    }
}
