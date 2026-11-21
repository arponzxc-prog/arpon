package com.example

import com.example.data.ai.AiFailoverPolicy
import com.example.data.ai.AiFailoverRunner
import com.example.data.ai.AiFailoverSettings
import com.example.data.ai.AiUsageTracker
import com.example.data.ai.ApiHttpException
import com.example.data.ai.FailureKind
import com.example.data.ai.IdentityResponder
import com.example.data.ai.ModelCooldownTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException

class Step3AiFailoverIdentityTest {

    // ---------- classification ----------
    @Test
    fun classifiesHttpCodes() {
        assertEquals(FailureKind.RATE_LIMIT, AiFailoverPolicy.classifyHttp(429))
        assertEquals(FailureKind.PAYMENT, AiFailoverPolicy.classifyHttp(402))
        assertEquals(FailureKind.AUTH, AiFailoverPolicy.classifyHttp(401))
        assertEquals(FailureKind.AUTH, AiFailoverPolicy.classifyHttp(403))
        assertEquals(FailureKind.NOT_FOUND, AiFailoverPolicy.classifyHttp(404))
        assertEquals(FailureKind.SERVER, AiFailoverPolicy.classifyHttp(503))
        assertEquals(FailureKind.OTHER, AiFailoverPolicy.classifyHttp(400))
    }

    @Test
    fun classifiesByTypeNotMessage() {
        // message text mentions 429 but the type is a plain exception -> must NOT fail over
        assertEquals(FailureKind.OTHER, AiFailoverPolicy.classify(IllegalStateException("Error 429 rate limit")))
        assertEquals(FailureKind.TIMEOUT, AiFailoverPolicy.classify(SocketTimeoutException("x")))
        assertEquals(FailureKind.RATE_LIMIT, AiFailoverPolicy.classify(ApiHttpException(429, "")))
    }

    // ---------- cooldown ----------
    @Test
    fun cooldownDurations() {
        assertEquals(60_000L, AiFailoverPolicy.cooldownMs(FailureKind.RATE_LIMIT, null))
        assertEquals(17_000L, AiFailoverPolicy.cooldownMs(FailureKind.RATE_LIMIT, 17))
        assertEquals(600_000L, AiFailoverPolicy.cooldownMs(FailureKind.AUTH))
        assertEquals(600_000L, AiFailoverPolicy.cooldownMs(FailureKind.PAYMENT))
        assertEquals(600_000L, AiFailoverPolicy.cooldownMs(FailureKind.NOT_FOUND))
        assertEquals(30_000L, AiFailoverPolicy.cooldownMs(FailureKind.SERVER))
        assertEquals(30_000L, AiFailoverPolicy.cooldownMs(FailureKind.TIMEOUT))
        assertEquals(3_600_000L, AiFailoverPolicy.cooldownMs(FailureKind.RATE_LIMIT, 999_999))
    }

    @Test
    fun retryAfterParsing() {
        assertEquals(30L, AiFailoverPolicy.parseRetryAfter(" 30 "))
        assertNull(AiFailoverPolicy.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(AiFailoverPolicy.parseRetryAfter(null))
    }

    @Test
    fun cooldownExpiresWithClock() {
        var now = 1_000L
        val t = ModelCooldownTracker { now }
        t.markFailed("a", FailureKind.RATE_LIMIT, null)
        assertTrue(t.isCoolingDown("a"))
        now += 59_999
        assertTrue(t.isCoolingDown("a"))
        now += 2
        assertFalse(t.isCoolingDown("a"))
    }

    // ---------- ordering ----------
    @Test
    fun candidateOrderSkipsCoolingModels() {
        val t = ModelCooldownTracker { 0L }
        t.markFailed("p", FailureKind.RATE_LIMIT)
        val order = AiFailoverPolicy.orderCandidates("p", listOf("p", "b", "c"), listOf("c", "b"), true, t)
        assertEquals(listOf("c", "b"), order)
    }

    @Test
    fun disabledFailoverUsesOnlyPrimary() {
        val order = AiFailoverPolicy.orderCandidates("p", listOf("p", "b"), emptyList(), false, ModelCooldownTracker())
        assertEquals(listOf("p"), order)
    }

    @Test
    fun allCoolingStillReturnsCandidates() {
        val t = ModelCooldownTracker { 0L }
        t.markFailed("a", FailureKind.AUTH)
        t.markFailed("b", FailureKind.SERVER)
        val order = AiFailoverPolicy.orderCandidates("a", listOf("a", "b"), emptyList(), true, t)
        assertEquals(listOf("b", "a"), order) // b recovers sooner
    }

    // ---------- runner ----------
    @Test
    fun primary429ShiftsToBackup() {
        val tracker = ModelCooldownTracker()
        val usage = AiUsageTracker()
        val calls = mutableListOf<String>()
        val r = AiFailoverRunner(tracker, usage).run(listOf("p", "b")) { id ->
            calls.add(id)
            if (id == "p") throw ApiHttpException(429, "limit", 5) else "ok-from-$id"
        }
        assertEquals("ok-from-b", r.value)
        assertEquals("b", r.usedId)
        assertEquals(listOf("p", "b"), calls)
        assertEquals(1, r.shifts.size)
        assertEquals(FailureKind.RATE_LIMIT, r.shifts[0].kind)
        assertTrue(tracker.isCoolingDown("p"))
        val p = usage.snapshot().first { it.modelId == "p" }
        assertEquals(1L, p.requests); assertEquals(1L, p.failures); assertEquals(1L, p.failoversFrom)
    }

    @Test
    fun nonFailoverErrorIsThrownImmediately() {
        val calls = mutableListOf<String>()
        try {
            AiFailoverRunner(ModelCooldownTracker()).run(listOf("p", "b")) { id ->
                calls.add(id); throw IllegalStateException("bad request")
            }
            fail("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals(listOf("p"), calls)
        }
    }

    @Test
    fun lastModelFailureIsPropagated() {
        try {
            AiFailoverRunner(ModelCooldownTracker()).run(listOf("p", "b")) { throw ApiHttpException(503, "down") }
            fail("expected exception")
        } catch (e: ApiHttpException) {
            assertEquals(503, e.code)
        }
    }

    @Test
    fun successClearsCooldown() {
        val t = ModelCooldownTracker()
        t.markFailed("p", FailureKind.SERVER)
        AiFailoverRunner(t).run(listOf("p")) { "ok" }
        assertFalse(t.isCoolingDown("p"))
    }

    @Test
    fun backupOrderMove() {
        assertEquals(listOf("b", "a", "c"), AiFailoverSettings.move(listOf("a", "b", "c"), "b", up = true))
        assertEquals(listOf("a", "c", "b"), AiFailoverSettings.move(listOf("a", "b", "c"), "b", up = false))
        assertEquals(listOf("a", "b"), AiFailoverSettings.move(listOf("a", "b"), "a", up = true))
    }

    // ---------- usage ----------
    @Test
    fun usageSerializationRoundTripHasNoContent() {
        val u = AiUsageTracker()
        u.onAttempt("m1"); u.onAttempt("m1")
        u.addEstimatedTokens("m1", 400, 400)
        val parsed = AiUsageTracker.parse(u.serialize())
        assertEquals(1, parsed.size)
        assertEquals(2L, parsed[0].requests)
        assertEquals(200L, parsed[0].estimatedTokens)
        assertTrue(AiUsageTracker.parse("garbage|x").isEmpty())
    }

    // ---------- identity ----------
    @Test
    fun nameQuestionsBengaliAndEnglish() {
        assertEquals("আমার নাম Nexa Ai।", IdentityResponder.respond("তোমার নাম কী?"))
        assertEquals("আমার নাম Nexa Ai।", IdentityResponder.respond("তোমার নাম কি"))
        assertEquals("আমার নাম Nexa Ai।", IdentityResponder.respond("আপনার নাম কী?"))
        assertEquals("My name is Nexa Ai.", IdentityResponder.respond("What is your name?"))
        assertEquals("My name is Nexa Ai.", IdentityResponder.respond("who are you"))
    }

    @Test
    fun creatorQuestions() {
        assertTrue(IdentityResponder.respond("কে বানিয়েছে?")!!.contains("Developer Arpon"))
        assertTrue(IdentityResponder.respond("তোমাকে কে বানিয়েছে")!!.contains("Developer Arpon"))
        assertTrue(IdentityResponder.respond("Who made you?")!!.contains("Developer Arpon"))
        assertTrue(IdentityResponder.respond("who created you")!!.contains("Developer Arpon"))
    }

    @Test
    fun modelIdentityNeverNamesVendors() {
        val r = IdentityResponder.respond("are you gemini?")
        assertNotNull(r)
        assertTrue(r!!.contains("Nexa Ai"))
        assertFalse(r.contains("Google", ignoreCase = true))
    }

    @Test
    fun falsePositivesDoNotTrigger() {
        assertNull(IdentityResponder.respond("আমার নাম কী"))
        assertNull(IdentityResponder.respond("আমার নাম আরপন"))
        assertNull(IdentityResponder.respond("what are you doing with this variable"))
        assertNull(IdentityResponder.respond("rename your name field in the form"))
        assertNull(IdentityResponder.respond("এই ফাইল কে বানিয়েছে"))
        assertNull(IdentityResponder.respond("who made this function"))
        assertNull(IdentityResponder.respond("```kotlin\nval name = \"your name\"\n```"))
        assertNull(IdentityResponder.respond("একটা লগইন স্ক্রিন বানাও"))
        assertNull(IdentityResponder.respond(""))
    }

    @Test
    fun systemPromptRuleMentionsRequiredAnswers() {
        assertTrue(IdentityResponder.SYSTEM_PROMPT_RULE.contains("Nexa Ai"))
        assertTrue(IdentityResponder.SYSTEM_PROMPT_RULE.contains("Developer Arpon"))
    }
}
