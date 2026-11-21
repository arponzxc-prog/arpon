package com.example.data.ai.agent

/** Bilingual (Bengali / English) one-line status for the live progress label. */
object AgentStatusText {
    fun label(call: ToolCall): String {
        val path = call.args.optString("path", "").takeIf { it.isNotBlank() }?.substringAfterLast('/')
        return when (call.name) {
            AgentToolSpecs.LIST_FILES -> "ফাইল তালিকা দেখা হচ্ছে / Listing files"
            AgentToolSpecs.READ_FILE -> "ফাইল পড়া হচ্ছে / Reading ${path ?: "file"}"
            AgentToolSpecs.SEARCH -> "প্রজেক্টে খোঁজা হচ্ছে / Searching project"
            AgentToolSpecs.PROJECT_INFO -> "প্রজেক্টের তথ্য / Project info"
            AgentToolSpecs.CREATE_PROJECT -> "প্রজেক্ট তৈরি হচ্ছে / Creating project"
            AgentToolSpecs.WRITE_FILE -> "ফাইল লেখা হচ্ছে / Writing ${path ?: "file"}"
            AgentToolSpecs.EDIT_FILE -> "ফাইল এডিট হচ্ছে / Editing ${path ?: "file"}"
            AgentToolSpecs.DELETE_FILE -> "ফাইল মোছা হচ্ছে / Deleting ${path ?: "file"}"
            AgentToolSpecs.RENAME_FILE -> "ফাইলের নাম বদল / Renaming ${path ?: "file"}"
            AgentToolSpecs.DIAGNOSTICS -> "যাচাই করা হচ্ছে / Checking for errors"
            AgentToolSpecs.TRIGGER_BUILD -> "বিল্ড শুরু হচ্ছে / Starting build"
            AgentToolSpecs.BUILD_LOG -> "বিল্ড লগ পড়া হচ্ছে / Reading build log"
            else -> "কাজ চলছে / Working"
        }
    }

    fun summaryTitle(r: AgentRunResult): String = when (r.cause) {
        AgentStopCause.COMPLETED -> ""
        AgentStopCause.MAX_STEPS -> "⚠️ ধাপ সীমায় পৌঁছে থামানো হয়েছে / Stopped at the step limit"
        AgentStopCause.REPEATED_CALL -> "⚠️ একই কাজ বারবার হচ্ছিল, থামানো হয়েছে / Stopped: repeated identical call"
        AgentStopCause.CANCELLED -> "⏹ থামানো হয়েছে / Stopped"
        AgentStopCause.WAITING_FOR_USER -> ""
        AgentStopCause.MODEL_ERROR -> "⚠️ ত্রুটি / Error"
    }
}
