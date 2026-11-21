package com.example.builder

import java.io.File

data class BengaliBuildErrorSummary(
    val titleBn: String,
    val causeBn: String,
    val fixSuggestionBn: String,
    val offendingFileHint: String? = null,
    val lineNumber: Int? = null,
    val aiPrompt: String
)

/**
 * Pure Kotlin analyzer converting Gradle / Kotlin / AAPT / Signing errors into clear Bengali root-cause summaries
 * and actionable AI auto-fix prompts.
 */
object BuildErrorAnalyzer {

    private val ktLineRegex = Regex("""(?:e:\s*)?(?:file://)?([^\s:]+\.(?:kt|java|kts|xml)):(\d+)""")
    private val unresolvedRegex = Regex("""Unresolved reference:\s*([A-Za-z0-9_]+)""")
    private val resourceMissingRegex = Regex("""resource\s+([^\s]+)\s+not found""", RegexOption.IGNORE_CASE)

    fun analyzeError(rawError: String, rawLogs: String = ""): BengaliBuildErrorSummary {
        val combined = "$rawError\n$rawLogs".trim()
        val lineMatch = ktLineRegex.find(combined)
        val fileHint = lineMatch?.groupValues?.get(1)?.let { File(it).name }
        val lineNum = lineMatch?.groupValues?.get(2)?.toIntOrNull()
        val locationLabel = if (fileHint != null) {
            if (lineNum != null) "$fileHint (লাইন $lineNum)" else fileHint
        } else null

        val aiPrompt = buildString {
            appendLine("Ниচের Android / Gradle বিল্ড এররটি বিশ্লেষণ করে সম্পূর্ণ ঠিক করা কোড দাও:")
            if (locationLabel != null) appendLine("ফাইল: $fileHint (লাইন: ${lineNum ?: "?"})")
            appendLine("এরর:")
            appendLine(combined.take(2500))
        }.trim()

        return when {
            combined.contains("Pre-Build Validation Failed", ignoreCase = true) ||
                combined.contains("Malformed XML", ignoreCase = true) -> {
                BengaliBuildErrorSummary(
                    titleBn = "প্রি-বিল্ড ভ্যালিডেশন ব্যর্থ",
                    causeBn = "ক্লাউডে পাঠানোর আগেই প্রজেক্টের স্ট্রাকচার, Manifest বা XML ফাইলে ত্রুটি ধরা পড়েছে${locationLabel?.let { " ($it)" } ?: ""}।",
                    fixSuggestionBn = "AndroidManifest.xml বা XML ট্যাগগুলোর বন্ধনী/সিনট্যাক্স ঠিক করুন।",
                    offendingFileHint = fileHint ?: "AndroidManifest.xml",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }

            combined.contains("keystore", ignoreCase = true) &&
                (combined.contains("password was incorrect", ignoreCase = true) ||
                    combined.contains("Tampered", ignoreCase = true) ||
                    combined.contains("Alias", ignoreCase = true) ||
                    combined.contains("signingConfig", ignoreCase = true)) -> {
                BengaliBuildErrorSummary(
                    titleBn = "Release Keystore / Signing ত্রুটি",
                    causeBn = "রিলিজ APK/AAB সাইন করার সময় কীস্টোর ফাইল, পাসওয়ার্ড বা Alias মেলেনি।",
                    fixSuggestionBn = "Build স্ক্রিনে 'Release Signing' থেকে সঠিক কীস্টোর/পাসওয়ার্ড দিয়ে GitHub Secrets আপডেট করুন।",
                    offendingFileHint = "app/build.gradle.kts",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }

            combined.contains("Unresolved reference", ignoreCase = true) -> {
                val symbol = unresolvedRegex.find(combined)?.groupValues?.get(1)
                BengaliBuildErrorSummary(
                    titleBn = "অজানা সিম্বল বা মিসিং ইম্পোর্ট",
                    causeBn = "${locationLabel ?: "কোডে"} '${symbol ?: "একটি নাম"}' খুঁজে পাওয়া যায়নি (Unresolved reference)।",
                    fixSuggestionBn = "সংশ্লিষ্ট ফাংশন/ক্লাস ইম্পোর্ট করুন অথবা build.gradle.kts-এ প্রয়োজনীয় ডিপেনডেন্সি যোগ করুন।",
                    offendingFileHint = fileHint ?: "MainActivity.kt",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }

            resourceMissingRegex.containsMatchIn(combined) ||
                combined.contains("AAPT: error:", ignoreCase = true) -> {
                val resName = resourceMissingRegex.find(combined)?.groupValues?.get(1)
                BengaliBuildErrorSummary(
                    titleBn = "অ্যান্ড্রয়েড রিসোর্স (AAPT) ত্রুটি",
                    causeBn = "রিসোর্স প্রসেসিং ব্যর্থ হয়েছে${resName?.let { ": '$it' পাওয়া যায়নি" } ?: ""}।",
                    fixSuggestionBn = "res/values, drawable বা mipmap আইকন ফাইলগুলো ঠিক আছে কিনা যাচাই করুন।",
                    offendingFileHint = fileHint ?: "strings.xml",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }

            combined.contains("Duplicate class", ignoreCase = true) -> {
                BengaliBuildErrorSummary(
                    titleBn = "ডুপ্লিকেট ক্লাস / ডিপেনডেন্সি কনফ্লিক্ট",
                    causeBn = "দুটি লাইব্রেরির মধ্যে একই ক্লাস থাকায় R8/Dex মার্জ ব্যর্থ হয়েছে।",
                    fixSuggestionBn = "app/build.gradle.kts থেকে কনফ্লিক্টিং ট্রানজিটিভ মডিউল বাদ দিন।",
                    offendingFileHint = "app/build.gradle.kts",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }

            combined.contains("Manifest merger failed", ignoreCase = true) -> {
                BengaliBuildErrorSummary(
                    titleBn = "AndroidManifest মার্জ ব্যর্থ",
                    causeBn = "AndroidManifest.xml-এর অ্যাট্রিবিউট বা exported/minSdk সেটিংস কনফ্লিক্ট করছে।",
                    fixSuggestionBn = "AndroidManifest.xml-এ activity exported, tools:replace বা minSdk পরীক্ষা করুন।",
                    offendingFileHint = "AndroidManifest.xml",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }

            combined.contains("Minimum supported Gradle version", ignoreCase = true) -> {
                BengaliBuildErrorSummary(
                    titleBn = "Gradle ভার্সন অসামঞ্জস্য",
                    causeBn = "প্রজেক্টের Android Gradle Plugin (AGP)-এর জন্য আরও নতুন Gradle ভার্সন প্রয়োজন।",
                    fixSuggestionBn = "gradle-wrapper.properties-এ Gradle ভার্সন আপডেট করুন (যেমন 8.10.2+)।",
                    offendingFileHint = "gradle-wrapper.properties",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }

            combined.contains("OutOfMemoryError", ignoreCase = true) ||
                combined.contains("Metaspace", ignoreCase = true) -> {
                BengaliBuildErrorSummary(
                    titleBn = "Gradle বিল্ড মেমোরি শেষ",
                    causeBn = "কম্পাইলেশনের সময় JVM হেপ/মেটাস্পেস মেমোরি ফুরিয়ে গেছে।",
                    fixSuggestionBn = "Clean build দিয়ে আবার চেষ্টা করুন বা ডিপেনডেন্সি/R8 চাপ কমান।",
                    offendingFileHint = "gradle.properties",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }

            combined.contains("'workflow' scope", ignoreCase = true) ||
                combined.contains("HTTP 403", ignoreCase = true) -> {
                BengaliBuildErrorSummary(
                    titleBn = "GitHub টোকেন পারমিশন ত্রুটি",
                    causeBn = "GitHub Personal Access Token-এ 'repo' বা 'workflow' পারমিশন নেই।",
                    fixSuggestionBn = "GitHub Account থেকে 'repo' ও 'workflow' স্কোপসহ নতুন টোকেন যোগ করুন।",
                    offendingFileHint = null,
                    lineNumber = null,
                    aiPrompt = aiPrompt
                )
            }

            else -> {
                BengaliBuildErrorSummary(
                    titleBn = "কম্পাইলেশন বা বিল্ড ত্রুটি",
                    causeBn = "${locationLabel?.let { "$it ফাইলে " } ?: ""}বিল্ড চলাকালীন এরর পাওয়া গেছে: ${rawError.lines().firstOrNull()?.take(140) ?: "অজানা এরর"}",
                    fixSuggestionBn = "লগ দেখে কোডের সিনট্যাক্স ঠিক করুন অথবা নিচের AI ফিক্স বাটন চাপুন।",
                    offendingFileHint = fileHint ?: "MainActivity.kt",
                    lineNumber = lineNum,
                    aiPrompt = aiPrompt
                )
            }
        }
    }
}
