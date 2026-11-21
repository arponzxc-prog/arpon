package com.example.data.ai

/**
 * Local (no-API) answers for "what is your name" / "who made you".
 * The model must never call itself Gemini / GPT / Claude.
 */
object IdentityResponder {
    const val ASSISTANT_NAME = "Nexa Ai"
    const val DEVELOPER_NAME = "Developer Arpon"

    private const val MAX_LEN = 70

    private val bengaliRange = Regex("[\\u0980-\\u09FF]")

    private val nameQuestion = listOf(
        Regex("(তোমার|আপনার|তোর)\\s*(নাম|পরিচয়)\\s*(কি|কী|কিরে|কি রে)?"),
        Regex("^(তুমি|আপনি|তুই)\\s*কে\\s*$"),
        Regex("\\b(what is|what's|whats|tell me)\\s+your\\s+name\\b"),
        Regex("\\b(may i know|can i know|tell me)\\s+your\\s+name\\b"),
        Regex("\\byour\\s+name\\s+(please|pls)\\b"),
        Regex("^who\\s+are\\s+you\\s*$"),
        Regex("^what\\s+are\\s+you\\s*$")
    )

    private val creatorQuestion = listOf(
        Regex("(কে|কারা)\\s*(তোমাকে|আপনাকে|তোমায়|আপনাকে|এই অ্যাপ|অ্যাপটা|এটা)?\\s*(বানিয়েছে|বানিয়েছেন|বানালো|বানাল|তৈরি করেছে|তৈরি করেছেন|তৈরি করলো|ডেভেলপ করেছে|ডেভেলপ করেছেন)"),
        Regex("(তোমাকে|আপনাকে|তোমায়)\\s*(কে|কারা)\\s*(বানিয়েছে|বানিয়েছেন|বানালো|বানাল|তৈরি করেছে|তৈরি করেছেন|তৈরি করলো|ডেভেলপ করেছে|ডেভেলপ করেছেন)"),
        Regex("\\bwho\\s+(made|created|built|developed|designed|programmed|trained)\\s+you\\b"),
        Regex("\\bwho\\s+is\\s+your\\s+(creator|developer|maker|owner)\\b"),
        Regex("\\bwho\\s+(owns|created|made|built|developed)\\s+(this|nexa)\\s+(app|ide|assistant)\\b")
    )

    /** Messages about code/files are never identity questions ("এই ফাইল কে বানিয়েছে"). */
    private val codeContext = Regex("(ফাইল|কোড|ফাংশন|ক্লাস|প্রজেক্ট|file|code|function|class|variable|commit|repo)")

    private val modelIdentityQuestion = listOf(
        Regex("(তুমি|আপনি)\\s*কি\\s*(gemini|gpt|chatgpt|claude|openai|google|anthropic|জেমিনি|চ্যাটজিপিটি|ক্লড)"),
        Regex("\\b(are|r)\\s+(you|u)\\s+(gemini|gpt|chatgpt|claude|openai|llama|deepseek)\\b"),
        Regex("\\bwhich\\s+(model|llm|ai)\\s+(are\\s+you|is\\s+this)\\b"),
        Regex("\\bwhat\\s+(model|llm)\\s+are\\s+you\\b"),
        Regex("(তুমি|আপনি)\\s*কোন\\s*(মডেল|এআই|ai)")
    )

    /** Lowercases, strips punctuation (including the Bengali danda) and collapses spaces. */
    fun normalize(raw: String): String =
        raw.lowercase().replace(Regex("[?!.,।;:\"'`()\\[\\]{}]"), " ").replace(Regex("\\s+"), " ").trim()

    fun isBengali(raw: String): Boolean = bengaliRange.containsMatchIn(raw)

    /** Returns a local answer, or null when the message is not an identity question. */
    fun respond(raw: String): String? {
        if (raw.isBlank() || raw.contains("```") || raw.length > MAX_LEN * 2) return null
        val text = normalize(raw)
        if (text.length > MAX_LEN) return null
        if (codeContext.containsMatchIn(text)) return null

        val asksModel = modelIdentityQuestion.any { it.containsMatchIn(text) }
        val asksCreator = creatorQuestion.any { it.containsMatchIn(text) }
        val asksName = nameQuestion.any { it.containsMatchIn(text) }
        if (!asksModel && !asksCreator && !asksName) return null

        val bn = isBengali(raw)
        return when {
            asksModel -> if (bn) "আমি $ASSISTANT_NAME। আমাকে $DEVELOPER_NAME বানিয়েছেন।"
                         else "I am $ASSISTANT_NAME. I was created by $DEVELOPER_NAME."
            asksName && asksCreator -> if (bn) "আমার নাম $ASSISTANT_NAME। আমাকে $DEVELOPER_NAME বানিয়েছেন।"
                                       else "My name is $ASSISTANT_NAME, and I was created by $DEVELOPER_NAME."
            asksCreator -> if (bn) "$DEVELOPER_NAME আমাকে বানিয়েছেন।" else "$DEVELOPER_NAME created me."
            else -> if (bn) "আমার নাম $ASSISTANT_NAME।" else "My name is $ASSISTANT_NAME."
        }
    }

    /** Backup rule appended to every system prompt in case the local matcher misses a phrasing. */
    const val SYSTEM_PROMPT_RULE =
        " IDENTITY RULES: Your name is 'Nexa Ai'. If asked your name, answer that your name is Nexa Ai. " +
        "If asked who made/created/developed you, answer 'Developer Arpon'. " +
        "Never say you are Gemini, GPT, ChatGPT, Claude, or made by Google, OpenAI or Anthropic. " +
        "Answer such identity questions in the same language as the question."
}
